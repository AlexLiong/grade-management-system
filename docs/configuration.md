# 环境安装与配置说明

本文档说明知序高校成绩管理系统在**不同操作系统**（Windows / macOS / Linux）和**不同运行环境**（本地开发、IDE 调试、测试、生产部署）下的完整配置方法。架构与故障排查细节见 [架构文档](architecture.md) 与 [部署文档](deployment.md)，本文聚焦"装什么、配什么、怎么改"。

## 目录

1. [软件依赖](#1-软件依赖)
2. [配置加载机制](#2-配置加载机制)
3. [配置项完整清单](#3-配置项完整清单)
4. [首次初始化](#4-首次初始化)
5. [本地开发环境配置](#5-本地开发环境配置)
6. [IDEA 调试配置](#6-idea-调试配置)
7. [测试环境配置](#7-测试环境配置)
8. [生产环境配置](#8-生产环境配置)
9. [各操作系统差异](#9-各操作系统差异)
10. [运行时目录结构](#10-运行时目录结构)
11. [常见问题](#11-常见问题)

---

## 1. 软件依赖

|软件|版本要求|用途|验证命令|
|---|---|---|---|
|JDK|17（完整 JDK，非 JRE）|运行 4 个 Java 服务；`keytool` 用于生成证书|`java -version` / `keytool`|
|Maven|3.9+|构建打包|`mvn -version`|
|Node.js|22 LTS（Node 26 已验证可运行）|chain-worker（Ganache + LSTM）、前端构建与调试、初始化脚本|`node -v` / `npm -v`|
|Git|任意近期版本|获取源码|`git --version`|

所有可执行文件（`java`、`keytool`、`mvn`、`node`、`npm`）必须加入 `PATH`。首次构建需要联网访问 Maven Central 和 npm 仓库。

主要技术栈版本（来自 `pom.xml` 与各 `package.json`，不要在不验证的情况下升级）：

- Spring Boot 3.2.6，Java 17，Spotless（google-java-format 1.22.0）
- Vue 3.5.13、Vite 6.4.3、Playwright 1.63.0、tesseract.js 6.0.1（浏览器端 OCR）
- Ganache 7.9.2（本机 EVM 测试链，chainId 1337）、TensorFlow.js 4.22.0（LSTM）
- 默认数据库 H2（文件模式），可切换 MySQL 8 / SQL Server / Oracle，见 [生产环境配置](#8-生产环境配置)

## 2. 配置加载机制

系统**不使用** Spring Profile，配置通过统一的 `Settings.get(key)`（`common/src/main/java/edu/campus/common/Settings.java:33`）按以下优先级读取，**高优先级覆盖低优先级**：

```text
环境变量（如 GATEWAY_KEY）> JVM 系统属性（-DGATEWAY_KEY=...）> application.yml 的 campus.<key> > yml 中 ${VAR:默认值} 的内置默认值
```

要点：

- 4 个 Java 服务各有独立的 `application.yml`（位于各模块 `src/main/resources/`），其中端口、证书密码、数据库连接等以 `${环境变量:默认值}` 形式声明；`campus.*` 段下的键值对同样可被同名环境变量覆盖。
- **所有密钥类配置（`GATEWAY_KEY`、`TLS_PASSWORD` 等）默认值都是开发占位值（`KEY` / `campus-dev-tls-2024`），生产环境必须通过环境变量覆盖**，不得写入提交到 Git 的文件。
- 每个服务通过 `campus.service` 系统属性标识自身（`gateway` / `business` / `data` / `audit`，在各 Application 主类中硬编码设置），用于服务注册与内部签名校验，**不需要也不应该手动修改**。
- chain-worker（Node）通过 `CAMPUS_RUNTIME` 环境变量定位运行时目录，默认 `../.runtime`（即工作目录为 `chain-worker/` 时指向项目根下 `.runtime`）。

## 3. 配置项完整清单

### 3.1 端口与监听地址

|环境变量|默认值|作用范围|说明|
|---|---|---|---|
|`GATEWAY_PORT`|`8443`|gateway|统一入口 HTTPS 端口，浏览器访问此端口|
|`BUSINESS_SERVICE_PORT`|`9441`|business-service|业务服务端口|
|`DATA_SERVICE_PORT`|`9442`|data-service|数据服务端口|
|`AUDIT_SERVICE_PORT`|`9443`|audit-service|审计服务端口|
|`BIND_ADDRESS`|`127.0.0.1`|全部 Java 服务|监听地址；跨机器部署时改为 `0.0.0.0` 或具体网卡 IP|
|`CAMPUS_RUNTIME`|`../.runtime`|chain-worker|运行时目录（证书、链数据、锚点文件）所在位置|

chain-worker 固定监听 `127.0.0.1:9545`（硬编码于 `chain-worker/server.mjs`），前端 Vite 调试端口固定 `5173`（`frontend/vite.config.js`）。

### 3.2 服务间通信地址

|环境变量|默认值|说明|
|---|---|---|
|`GATEWAY_URL`|`https://localhost:8443`|业务/数据/审计服务向网关注册心跳与内部调用的地址。跨机器部署时指向网关实际地址|
|`CHAIN_URL`|`https://localhost:9545`|审计、业务、网关访问 EVM 锚定与 LSTM 分类服务的地址|
|`PUBLIC_ORIGIN`|`https://localhost:8443`|网关校验浏览器请求 `Origin` 头的允许值。更换对外域名/端口时必须同步修改|
|`SERVICE_HOSTS`|`**`,localhost,127.0.0.1`|网关允许注册的服务主机白名单（逗号分隔）。跨机器部署时加入各服务主机名/IP|

服务发现机制：业务、数据、审计服务启动后每 5 秒向 `GATEWAY_URL + /internal/register` 发送心跳（`common/src/main/java/edu/campus/common/ServiceHeartbeat.java`），注册地址由本服务 `server.address` + `server.port` 自动生成；20 秒未续租的实例被网关移出轮询列表。因此**多实例扩容时只需为副本设置不同的端口环境变量**（如 `BUSINESS_SERVICE_PORT=9451`），无需额外注册配置。

### 3.3 安全密钥（生产必须全部修改）

|环境变量|默认值|用途|
|---|---|---|
|`TLS_PASSWORD`|`campus-dev-tls-2024`|`.runtime/localhost.p12` 密钥库与 `truststore.p12` 信任库密码|
|`GATEWAY_KEY`|`KEY`|网关内部调用签名（HMAC-SHA256）密钥|
|`BUSINESS_KEY`|`KEY`|业务服务签名密钥|
|`DATA_KEY`|`KEY`|数据服务签名密钥 + 成绩字段加密密钥（修改后旧密文不可解密，变更前必须备份并做数据迁移）|
|`AUDIT_KEY`|`KEY`|审计服务签名密钥；也是 chain-worker 校验请求来源的密钥|
|`LEDGER_KEY`|`KEY`|账本密钥；其 SHA-256 派生 Ganache 测试链账户种子，决定链上签名账户|
|`AUDIT_DATA_KEY`|`KEY`|审计数据加密密钥|

注意：chain-worker 中 `LEDGER_KEY`/`AUDIT_KEY` 为源码内置的 `"KEY"`（`chain-worker/server.mjs:10`），**修改这两个环境变量时，必须同步修改 `server.mjs` 中的 `config` 对象**，否则审计服务与链工具之间签名校验失败。修改 `LEDGER_KEY` 会使链账户变化，历史锚定交易仍存在于旧账户下，需按部署文档的备份恢复流程处理。

### 3.4 数据库

|环境变量|默认值|说明|
|---|---|---|
|`DB_URL`|`jdbc:h2:file:./.runtime/database/campus;AUTO_SERVER=TRUE`|JDBC 连接串；路径相对于**数据服务工作目录**（必须为项目根）|
|`DB_USER`|`sa`|数据库用户|
|`DB_PASSWORD`|空|数据库密码|

连接池为 HikariCP，`maximum-pool-size: 8`。外部数据库连接串示例见 [8.3 数据库切换](#83-数据库切换)。

### 3.5 JVM 系统属性

|属性|用途|
|---|---|
|`-Dfile.encoding=UTF-8`|保证跨平台中文读写一致，`scripts/start.sh` 已设置|
|`-Djavax.net.ssl.trustStore=<项目根>/.runtime/truststore.p12`|服务间 HTTPS 调用的信任库（IDE 直启时需要，见 [6. IDEA 调试配置](#6-idea-调试配置)）|
|`-Djavax.net.ssl.trustStorePassword=<TLS_PASSWORD>`|信任库密码|
|`-Dcampus.runtime=<项目根>/.runtime`|显式指定运行时目录（部署文档建议；`Settings.root()` 默认取工作目录下 `.runtime`）|

### 3.6 前端与测试

|配置|位置|说明|
|---|---|---|
|Vite 调试端口 5173 / HTTPS|`frontend/vite.config.js`|自动读取 `.runtime/localhost.key|.crt`；`/api`、`/health` 代理到 `https://localhost:8443`|
|`BROWSER_EXECUTABLE`|环境变量|Playwright 使用自定义浏览器可执行文件路径（`frontend/playwright.config.js:17`）|
|e2e 基准地址 `https://localhost:8443`|`frontend/playwright.config.js`|要求全套服务已启动；串行执行（`workers: 1`）|

## 4. 首次初始化

任何新机器（任何操作系统）克隆源码后，按以下顺序执行：

```bash
# 1. 安装 Node 依赖（chain-worker 与前端；frontend 的 node-forge 还被 setup 脚本复用）
npm --prefix chain-worker install
npm --prefix frontend install

# 2. 生成开发 TLS 证书与信任库（幂等，已存在则不覆盖；--reset 强制重建）
node scripts/setup.mjs

# 3. 构建 Java 服务（首次需联网下载依赖）
mvn -q -DskipTests clean package

# 4. 构建前端静态资源（交付页面由网关直接提供）
npm --prefix frontend run build
```

`setup.mjs` 在 `.runtime/` 下创建 `database/`、`ledger/`、`logs/` 子目录（权限 0700），生成：

- `localhost.p12` — RSA 3072 自签名证书（CN=localhost，SAN 含 `localhost` 与 `127.0.0.1`，有效期 825 天，PKCS12）
- `localhost.key` / `localhost.crt` — PEM 格式私钥与证书（chain-worker 与 Vite 使用）
- `truststore.p12` — Java 服务间 HTTPS 调用的信任库

**浏览器首次访问需信任 `.runtime/localhost.crt`**（双击导入到受信任的根证书颁发机构，或在浏览器中确认安全例外）。正式部署必须换用组织 CA 签发的证书，不能关闭证书验证。

## 5. 本地开发环境配置

最简方式（零配置，全部使用默认值）：

### macOS / Linux

```bash
./scripts/start.sh    # 依次启动 chain-worker 与 4 个 Java 服务，日志写入 .logs/，Ctrl+C 全部停止
```

`start.sh` 是 bash 脚本，Windows 上需通过 Git Bash 执行，或按下节手动启动。

### Windows（PowerShell 手动启动）

Windows 不能原生执行 `.sh`，按依赖顺序在 **5 个独立 PowerShell 窗口**（或 5 个后台作业）中启动。工作目录除 chain-worker 外均为**项目根目录**：

```powershell
# 窗口1：EVM/LSTM 工具（必须最先启动）
cd chain-worker; node server.mjs

# 窗口2-5：Java 服务（项目根目录下执行）
java -Dfile.encoding=UTF-8 -jar gateway/target/gateway-1.0.0.jar
java -Dfile.encoding=UTF-8 -jar business-service/target/business-service-1.0.0.jar
java -Dfile.encoding=UTF-8 -jar audit-service/target/audit-service-1.0.0.jar
java -Dfile.encoding=UTF-8 -jar data-service/target/data-service-1.0.0.jar
```

启动顺序要求：chain-worker → gateway → business-service → audit-service → data-service。服务未就绪时注册会自动重试，但 chain-worker 必须先于审计服务可用。

启动后访问 `https://localhost:8443`。首次运行（或结构版本变化）时 `DemoInitializer` 自动重建演示数据：
**4 所学院 / 8 个专业 / 21 个班级 / 205 个账号 / 155 个教学班 / 1354 条选课 / 1354 条成绩 / 1 个进行中的选课批次**，密码统一 `passwd`。启动日志会打印实际条数：
```text
[DemoInitializer] 数据已写入：4 个学院、8 个专业、21 个班级、205 个账号、155 个教学班、1354 条选课、1354 条成绩、1 个选课批次。
[DemoInitializer] 成绩单自检通过：5 名重修学生（3 个课程代码）、184 名学生，无「已通过重选」的跨学期重复课程代码。
[DemoInitializer] 学业预警覆盖自检通过：64 门有选课的正课全部可预测。
```
账号构成：3 名教务管理员（`admin` / `jw001` / `jw002`，不归属任何组织）、15 名教师与 187 名学生。教师 = 11 名演示教师（`t1101`、`t1102`、`t1201`、`t1202`、`t2101`、`t2201`、`t2202`、`t3101`、`t3201`、`t4101`、`t4201`）＋ 4 名**不可登录的史料教师**（`ht2020`/`ht2021`/`ht2022`/`ht2023`，`enabled=0`，只为历史样本教学班提供真实归属）；学生 = 144 名正课学生 ＋ 40 名历史样本学生 ＋ 3 名待分班。教学班 155 个 = 64 个正课 + 91 个历史样本教学班（2020-1 至 2023-2）。
演示数据里还有**挂科后重修的学生**（覆盖 `CS102`/`MG101`/`CE101` 等课程代码：一部分已在此前学期重修通过，一部分正在当前学期重修、成绩未提交）。重修不体现在课程名上：同一课程号的课程只是开设学年不同，课程名保持一致；「谁在重修」由历史成绩推导并以 `retake` 字段下发。`verifyTranscriptIntegrity()` 会在灌数后校验「同一课程号最多两次修读、前一次是已提交的挂科、后一次已提交则必须通过、至少 3 名重修学生」，不满足就抛异常中止启动（当前启动日志：5 名重修学生、3 个课程代码、184 名学生）。
**学业预警开箱可用**：演示数据为每门有选课的正课铺开满足 `predict` 门槛的训练样本（同一课程代码 ≥3 个更早年份、≥24 条含平时/实验/期末的已提交成绩），并给每门已提交成绩的正课补 1 名「有平时与实验、缺期末」的**缓考样本学生**（暂存 `DRAFT` 成绩）作为预测对象。两处数据设计：

- **历史样本教学班有完整名单与成绩**：每班 8 条 `ACTIVE` 选课 + 8 条成绩（共 728 条样本成绩），任课教师就是上面那 4 个不可登录的史料教师账号。施工时曾担心「样本班有选课就会自己需要 3 个更早年样本」而只登记成绩，但 2020-1 等本身就是最早期次、没有更早学期可查，顾虑不成立。样本学生的成绩会进入他们自己的学业记录（与真实学生一致）。
- **缓考样本池**：为每门已提交成绩的正课补 1 名缺期末的学生，保证每门课打开学业预警都有预测行。

`verifyPredictionCoverage()` 在启动时校验正课的覆盖情况：「≥3 个更早年份 + ≥24 条样本 + 本班至少 1 人缺期末」，不满足即中止启动（遍历时显式排除历史样本教学班——它们没有更早年份可查）；当前启动日志：`学业预警覆盖自检通过：64 门有选课的正课全部可预测。` 暂存成绩不算通过/挂科，也不进入学业记录与重修判定。注意**预测只对进行中的课程有意义**：对已出分的历史课程调用 `/predict` 会返回 200 但 `results` 为空，前端会提示改选当前学期（2026-1）的课程。

**启动即清理测试残留**：每次启动时、早于灌数，`DemoInitializer.purgeTestArtifacts()` 会清掉端到端脚本留下的测试教学班与测试选课批次（判据：名称含「测试」或「低人数」，或学期/批次不在演示数据声明的学期集合内），级联删除对应的选课、成绩、选课流水与分析。日志形如 `已清除 3 门测试课程、12 条选课、12 条成绩、2 个测试选课批次`；没有残留时打印「未发现测试课程/测试选课批次残留」。因此跑完测试**重启一次即可恢复干净界面**，不必再 `-ResetDb`。

**排障开关（默认关闭）**：`-Dcampus.trace.repo=true` 打印每次 `repo.find` 的耗时（定位业务侧往返次数），`-Dcampus.trace.sql=true` 打印数据服务每次查询/解密的耗时。例如：
```powershell
java -Dcampus.trace.repo=true -Dcampus.trace.sql=true -jar business-service/target/business-service-1.0.0.jar
```
Windows 下推荐用 `.\scripts\start.ps1` 一键启动（避免 PowerShell 拆坏 `-D` 参数），需要手动整库重建时加 `-ResetDb`。

### 修改默认端口/密钥（本地多实例场景）

PowerShell 示例（为当前进程设置环境变量）：

```powershell
$env:BUSINESS_SERVICE_PORT="9451"
$env:GATEWAY_KEY="my-dev-secret"
java -Dfile.encoding=UTF-8 -jar business-service/target/business-service-1.0.0.jar
```

bash 示例：

```bash
BUSINESS_SERVICE_PORT=9451 GATEWAY_KEY=my-dev-secret \
  java -Dfile.encoding=UTF-8 -jar business-service/target/business-service-1.0.0.jar
```

注意：**签名密钥必须在所有服务间保持一致**（如改了 `DATA_KEY`，gateway/business/data/audit 四个进程都要用同一个值），否则内部调用验签失败返回 401/403。

### 前端热更新调试（可选）

```bash
npm --prefix frontend run dev    # https://localhost:5173，/api 自动代理到 8443
```

前置条件：已执行 `setup.mjs`（Vite 需要 `.runtime/localhost.key|.crt`），且网关已启动。最终交付页面由网关直接托管 `frontend/dist`，无需同时启动 Vite。

## 6. IDEA 调试配置

1. 用 IDEA 打开**根 `pom.xml`**（不要只打开单个模块或 Java 文件），Project SDK 选 JDK 17。
2. 先完成 [第 4 节](#4-首次初始化)的 setup 与 build，再启动 chain-worker（`npm start`，工作目录 `chain-worker/`）。
3. 为 4 个主类各建一个 Application 运行配置：
   `GatewayApplication`、`BusinessApplication`、`AuditApplication`、`DataApplication`。
4. 每个运行配置的 **Working directory 设为项目根目录**（`$ProjectFileDir$`），否则找不到 `.runtime/localhost.p12` 和 H2 数据库文件。
5. VM options（密码不要提交进共享的 `.idea/runConfigurations`）：

```text
-Dfile.encoding=UTF-8
-Dcampus.runtime=项目绝对路径/.runtime
-Djavax.net.ssl.trustStore=项目绝对路径/.runtime/truststore.p12
-Djavax.net.ssl.trustStorePassword=campus-dev-tls-2024
```

Windows 路径示例：`-Dcampus.runtime=E:/seniorp1/grade-management-system-main/.runtime`（正斜杠即可，无需转义反斜杠）。

## 7. 测试环境配置

### 单元测试

```bash
mvn verify
```

无需启动任何服务；H2 使用独立测试配置，不影响 `.runtime` 数据。

### 集成与端到端测试

要求全套服务已在后台运行（[第 5 节](#5-本地开发环境配置)），且**依次串行执行，不要并发**：

```bash
node scripts/api-test.mjs
node scripts/workflow-test.mjs
node scripts/tamper-test.mjs
node scripts/scale-test.mjs
npm --prefix frontend run test:e2e
node scripts/generate-docs.mjs --check
```

Playwright 首次运行前在 `frontend/` 下执行 `npx playwright install chromium`。如果机器上已有 Chrome/Edge 等浏览器，可用 `BROWSER_EXECUTABLE` 环境变量指定可执行文件路径跳过下载（Windows 示例：`$env:BROWSER_EXECUTABLE="C:/Program Files/Google/Chrome/Application/chrome.exe"`）。

集成测试会写入专用测试课程与账号数据，**只能在演示数据库上运行**，严禁对真实教务数据执行。

## 8. 生产环境配置

### 8.1 与开发环境的关键差异清单

|项目|开发默认|生产要求|
|---|---|---|
|TLS 证书|自签名 `localhost.p12`|组织 CA 签发，SAN 含正式域名；替换 `.runtime/localhost.p12` 并重建 `truststore.p12`|
|全部 `*_KEY`、`TLS_PASSWORD`|占位值 `KEY` / `campus-dev-tls-2024`|通过环境变量或受控密钥管理注入，**禁止写入 Git**|
|`BIND_ADDRESS`|`127.0.0.1`|`0.0.0.0` 或内网网卡地址，配合防火墙白名单|
|`PUBLIC_ORIGIN`|`https://localhost:8443`|正式对外来源，如 `https://grade.example.edu`|
|`GATEWAY_URL` / `CHAIN_URL`|localhost|各服务实际地址|
|`SERVICE_HOSTS`|localhost 白名单|加入所有服务主机名/IP|
|数据库|H2 文件库|MySQL 8 / SQL Server / Oracle（见 8.3）|
|演示数据|自动注入|删除/禁用 seed 后使用正式迁移脚本初始化 schema|

### 8.2 生产启动示例（Linux，systemd 风格环境注入）

```bash
# /etc/campus/env  （权限 0600，不入库）
GATEWAY_KEY=...
BUSINESS_KEY=...
DATA_KEY=...
AUDIT_KEY=...
LEDGER_KEY=...
AUDIT_DATA_KEY=...
TLS_PASSWORD=...
BIND_ADDRESS=10.0.1.10
PUBLIC_ORIGIN=https://grade.example.edu
GATEWAY_URL=https://10.0.1.10:8443
CHAIN_URL=https://10.0.1.11:9545
SERVICE_HOSTS=10.0.1.10,10.0.1.11,10.0.1.12
DB_URL=jdbc:mysql://db.internal:3306/campus?sslMode=VERIFY_IDENTITY
DB_USER=campus_app
DB_PASSWORD=...
```

启动命令与开发相同（`java -jar ...`），环境变量由 systemd `EnvironmentFile` 注入。每个 Java 服务 JVM 建议最大堆 384 MB（`-Xmx384m`），另为 Node/WASM 与操作系统预留内存，整机建议至少 4 GB 可用内存。

### 8.3 数据库切换

|数据库|`DB_URL` 示例|附加要求|
|---|---|---|
|H2（默认）|自动生成，`CIPHER=AES` 加密|密码由文件密钥与用户密码组合；不支持多进程同时打开同一文件|
|MySQL 8|`jdbc:mysql://db.internal:3306/campus?sslMode=VERIFY_IDENTITY`|服务端证书须受信任|
|SQL Server|`jdbc:sqlserver://db.internal:1433;databaseName=campus;encrypt=true;trustServerCertificate=false`|—|
|Oracle|`jdbc:oracle:thin:@tcps://db.internal:2484/CAMPUS`|配置 Oracle wallet/信任库|

原则：数据库账号只允许访问本系统 schema（不使用 root/sa/SYS）；生产先执行审阅过的迁移脚本建表，再收回 DDL 权限。`SchemaCatalog` 已适配 MySQL `LONGTEXT`、SQL Server `VARCHAR(MAX)`、H2/Oracle `CLOB`。注意外部数据库的兼容性尚未做完整连接与事务验收，上线前需自行验证。

### 8.4 业务服务横向扩容

业务服务是唯一已验证可多实例的组件：复制 JAR，用不同端口启动即可，5 秒内自动注册进网关轮询：

```bash
BUSINESS_SERVICE_PORT=9451 java -Dfile.encoding=UTF-8 -jar business-service/target/business-service-1.0.0.jar
```

所有副本须使用**相同的全套密钥与 GATEWAY_URL**。H2 文件模式不支持数据服务多开；数据/审计服务的横向扩容本版本未验证，不要直接上线。

## 9. 各操作系统差异

|事项|Windows|macOS|Linux|
|---|---|---|---|
|一键启动|无 `.sh`，用 Git Bash 执行 `scripts/start.sh` 或按 5.2 手动启动|`./scripts/start.sh`|同 macOS|
|环境变量语法|PowerShell `$env:VAR="值"`；CMD `set VAR=值`|`export VAR=值` 或行内 `VAR=值 cmd`|同 macOS|
|证书信任|双击 `.runtime/localhost.crt` → 安装到"受信任的根证书颁发机构"（仅限本机测试用途）|钥匙串访问导入并设为始终信任|按发行版放入 `ca-certificates` 并 `update-ca-certificates`|
|路径写法|Node/Java 均接受正斜杠；IDEA VM options 用 `E:/...` 形式|原生 Unix 路径|同 macOS|
|文件权限|用 NTFS ACL 限制 `.runtime` 仅服务账户可读|`setup.mjs` 自动设置 0700/0600|同 macOS|
|换行/编码|Git 建议 `core.autocrlf=input`；JVM 已强制 `-Dfile.encoding=UTF-8`|默认 UTF-8|默认 UTF-8|

源码内所有 Node 脚本使用 `path` 模块拼路径，`setup.mjs` 以参数数组直接调用 `keytool`（不经过 shell），安装路径含空格（如 `C:\Program Files\Java\...`）不会出问题。开发机为 macOS ARM64，**Windows 的证书导入、文件锁、进程停止行为尚未实测**，在 Windows 部署验收前须完整跑一遍单元/集成/浏览器测试。

Node 26 上 Ganache 的原生 uWS 模块可能回退为 JS 实现，仅影响性能不影响功能；如追求稳定可用 Node 22 LTS。

## 10. 运行时目录结构

所有运行期数据都在 `.runtime/`（相对各进程工作目录，故 Java 服务工作目录必须是项目根）：

```text
.runtime/
├── localhost.p12      # TLS 密钥库（Java 服务）
├── localhost.crt      # PEM 证书（浏览器信任、Vite、chain-worker）
├── localhost.key      # PEM 私钥（chain-worker、Vite），0600
├── truststore.p12     # Java 服务间调用信任库
├── database/          # H2 数据文件（data-service）
├── ledger/            # 审计账本 events.jsonl（audit-service）
├── ethereum/          # Ganache LevelDB 链数据（chain-worker）
├── anchors.json       # 账本哈希 ↔ EVM 交易锚点
└── logs/              # 运行日志
```

`.logs/` 存放 `start.sh` 捕获的各进程 stdout/stderr。备份与恢复的完整要求（停写、SHA-256 校验、先跑完整性验证再恢复写入）见 [部署文档](deployment.md#备份与恢复)。**不要通过删除 `.runtime` 来"修复"审计报错**，那会销毁原始证据。

## 11. 常见问题

|现象|原因与处理|
|---|---|
|启动报 `Missing setting: XXX`|对应环境变量/属性未设置且该 yml 无默认值，检查 [第 3 节](#3-配置项完整清单)|
|`trustAnchors must be non-empty`|信任库缺失或路径错误：重跑 `node scripts/setup.mjs`，IDE 启动时确认 `-Djavax.net.ssl.trustStore` 指向 `truststore.p12`（不是 `localhost.p12`）|
|服务间调用 401/403|各进程密钥不一致，或 chain-worker 的 `config` 未与环境变量同步（见 3.3）|
|8443 拒绝连接|查看 `.logs/gateway.log`；常见为端口占用或证书未生成|
|浏览器提示证书不受信|未导入 `.runtime/localhost.crt`，见 [第 9 节](#9-各操作系统差异)|
|H2 报"数据库已占用"|另一个 data-service 实例或数据库 GUI 正打开同一文件，H2 文件模式不允许多开|
|前端 5173 无法启动|未运行 `setup.mjs` 导致缺少 `localhost.key/.crt`，或 5173 被占（`strictPort: true`）|
|修改 `DATA_KEY` 后历史成绩无法解密|加密密钥变更必须配合数据迁移，变更前备份 `.runtime/database/`|
|Playwright 找不到浏览器|运行 `npx playwright install chromium`，或设置 `BROWSER_EXECUTABLE`|

更完整的故障排查（审计 503、EVM gas、OCR 加载等）见 [部署文档 §故障排查](deployment.md#故障排查)。
