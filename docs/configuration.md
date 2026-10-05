# 环境安装与配置说明

本文档说明知序高校成绩管理系统在**不同操作系统**（Windows / macOS / Linux）和**不同运行环境**（本地开发、IDE 调试、测试、生产部署）下的完整配置方法。架构与故障排查细节见 [架构文档](architecture.md) 与 [部署文档](deployment.md)，本文聚焦"装什么、配什么、怎么改"。

## 目录

1. [软件依赖](#1-软件依赖)
2. [配置加载机制](#2-配置加载机制)
3. [配置项完整清单](#3-配置项完整清单)
   - [3.3 安全密钥与整库加密](#33-安全密钥与整库加密)：[加密总览](#331-数据库整库加密h2-cipheraes)、[九项密钥](#332-九项密钥清单)、[注入优先级](#333-注入方式与优先级)、[生产档校验](#334-生产档校验拒绝启动)、[轮换后果](#335-密钥轮换的后果整库重建)、[常见报错 90050/90049](#336-常见报错与处置)
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

系统**不使用 Spring Profile**（`CAMPUS_PROFILE=prod` 不是 Spring Profile，只被 `ConfigGuard` 用来切换「密钥缺失即拒绝启动」的严格档，见 [3.3.4](#334-生产档校验拒绝启动)），配置通过统一的 `Settings.get(key)`（`common/src/main/java/edu/campus/common/Settings.java:39`）按以下优先级读取，**高优先级覆盖低优先级**：

```text
环境变量（如 GATEWAY_KEY）> JVM 系统属性（-DGATEWAY_KEY=...）> application.yml 的 campus.<key> > yml 中 ${VAR:默认值} 的内置默认值
```

要点：

- 4 个 Java 服务各有独立的 `application.yml`（位于各模块 `src/main/resources/`），其中端口、证书密码、数据库连接等以 `${环境变量:默认值}` 形式声明；`campus.*` 段下的键值对同样可被同名环境变量覆盖。
- **所有密钥类配置（`GATEWAY_KEY`、`TLS_PASSWORD`、`DB_PASSWORD` 等）在 yml 里一律写成空占位 `${VAR:}`，源代码与配置文件里没有任何真实密钥**。它们由 `scripts/setup.mjs` 随机生成到 `.runtime/secrets.json`，再由启动脚本注入为环境变量与 `-D` 启动参数；`ConfigEnvironmentPostProcessor` 在配置绑定之前把密钥与数据源凭据注入 Environment（环境变量 / `-D` 优先，密钥文件兜底），因此 IDEA 里直接跑主类也能起来。**这个注入发生在 `Settings.get(key)` 取值之前**，所以「环境变量 > 系统属性 > yml」的既有优先级不变，只是 yml 那一层不再提供任何默认密钥。详见 [3.3 安全密钥与整库加密](#33-安全密钥与整库加密)。
- 唯一仍保留字面量的口令是各服务 `application.yml` 里的 `server.ssl.key-store-password: ${TLS_PASSWORD:campus-dev-tls-2024}`：`TLS_PASSWORD` 未设置时会回落到这个开发证书口令，只用于本机演示证书；生产档下它属于占位值，`ConfigGuard` 会**拒绝启动**（见 [3.3.4](#334-生产档校验拒绝启动)）。
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

### 3.3 安全密钥与整库加密

#### 3.3.1 数据库整库加密（H2 `CIPHER=AES`）

数据服务默认连接本机 H2 文件库，并**开启整库加密**。真实取值来自 `data-service/src/main/resources/application.yml` 与 `common/src/main/java/edu/campus/common/DbCredentials.java`：

```text
jdbc:h2:file:./.runtime/database/campus;CIPHER=AES;AUTO_SERVER=TRUE
```

|项目|说明|
|---|---|
|加密范围|整个库文件（H2 文档：所有数据库文件，含备份脚本文件，均可用 AES 加密）。H2 的 AES 实现是 AES-128|
|文件口令|`DB_CIPHER_KEY`，随机生成|
|用户口令|`DB_PASSWORD`，随机生成|
|会话口令格式|**「文件口令 + 空格 + 用户口令」两段式**（H2 规定：文件口令放在口令字段里、位于用户口令之前，用一个空格分隔；文件口令自身不得含空格）。两段都是 64 位十六进制，都不含空格，因此格式恒定|
|口令来源|`DB_CIPHER_KEY` 与 `DB_PASSWORD` 各自独立，且都不写在 yml 里；由 `DbCredentials.password()` 解析后交给 `DataSourceConfig` 显式构造 Hikari 数据源|
|用户名|`DB_USER`，默认 `sa`|
|实测库文件头|`H2encrypt`（明文库为 `H:2,block:...`）|

原生口令解析顺序（`DbCredentials`）：

```text
DB_PASSWORD 环境变量 / -D 启动参数（两段式整串）> 由 ConfigGuard 从 secrets.json 取 DB_CIPHER_KEY + " " + DB_PASSWORD 拼成
```

`DataSourceConfig` 不依赖 Spring Boot 的自动配置取值链路（`spring.datasource.password` → 配置绑定 → `DataSourceProperties`），而是由 Java 直接取值并显式构造 `HikariDataSource`：一旦取到单段口令，H2 只会报 `Wrong password format`，很难定位。启动日志只打印**口令来源 + 长度 + 空格数**（`DbCredentials.describePassword()`），不输出口令内容。

**数据库口令与用户登录密码没有关系**：整库加密口令是文件级 / 进程级的，演示账号的登录密码仍然是 `passwd`，**没有变化**。

**明文库迁移**：开启 `CIPHER=AES` 后，H2 打不开此前生成的明文库文件（报 `File corrupted while reading record` 这类难以定位的异常）。`DatabaseBootstrap.prepare()` 会在 Spring 启动**之前**按文件头判断并清理：加密库文件头为 `H2encrypt`，明文库为 `H2:`，命中明文库就删除并以加密库重建（演示数据由 `DemoInitializer` 重新灌入），日志形如：

```text
[SchemaCatalog] 检测到未加密的 H2 明文库，已删除并改为加密库重建：<路径>（演示数据由初始化器重新灌入）
[DatabaseBootstrap] 明文库已清理，将以加密库重新初始化演示数据。
```

#### 3.3.2 九项密钥清单

密钥**全部由 `scripts/setup.mjs` 用 `crypto.randomBytes(32)` 随机生成**（64 位十六进制），写入 `.runtime/secrets.json`，权限尽力设为 `0600`（POSIX；Windows 上依赖 NTFS ACL），该文件已被 `.gitignore` 忽略，不进版本库。

|环境变量 / `-D` 属性|用途|来源|
|---|---|---|
|`GATEWAY_KEY`|网关内部调用签名（HMAC-SHA256）密钥|`crypto.randomBytes(32)`|
|`BUSINESS_KEY`|业务服务签名密钥|同上|
|`DATA_KEY`|数据服务签名密钥 + 成绩字段加密密钥|同上|
|`AUDIT_KEY`|审计服务签名密钥；也是 chain-worker 校验请求来源的密钥|同上|
|`LEDGER_KEY`|账本密钥；其 SHA-256 派生 Ganache 测试链账户种子，决定链上签名账户|同上|
|`AUDIT_DATA_KEY`|审计数据加密密钥|同上|
|`TLS_PASSWORD`|`.runtime/localhost.p12` 密钥库与 `truststore.p12` 信任库口令|同上|
|`DB_CIPHER_KEY`|H2 整库加密的**文件口令**（加密整库文件）|同上|
|`DB_PASSWORD`|H2 整库加密的**用户口令**（`sa` 的认证口令）|同上（注意与上一条是两项独立密钥）|

`ConfigGuard.REQUIRED_KEYS` 是与上表一一对应的清单（`ConfigGuardTest` 断言其数量为 9，增删密钥必须同步 `setup.mjs` 的 `SECRET_KEYS`、启动脚本的 `secretNames` 与本文表格）。口令变化时 `setup.mjs` 会**自动重新生成 TLS 证书与信任库**（用新口令尝试打开 `localhost.p12`，打不开就删掉重建，而不是靠时间戳判断）。

密钥文件路径可用 `CAMPUS_SECRETS` 环境变量或 `-Dcampus.secrets=` 覆盖，默认 `.runtime/secrets.json`（`Settings.root()` 默认即工作目录下的 `.runtime`）。

#### 3.3.3 注入方式与优先级

同一项密钥可能有四处来源，取值时按下面的顺序命中，**第一个非空值生效**：

```text
环境变量  >  JVM 系统属性（-D）  >  .runtime/secrets.json  >  开发档兜底（仅 TLS_PASSWORD 有）
```

注入动作本身在多个入口重复发生，互为兜底（任一入口成功，后面的入口都不会改变已生效的值）：

|入口|行为|
|---|---|
|`scripts/start.ps1` / `scripts/start.sh`|读 `secrets.json`，把 9 项**同时**导出为环境变量与 `-D` 启动参数；`secrets.json` 不存在时脚本先自动调用 `setup.mjs`|
|`DatabaseBootstrap.prepare()`|四个主类 `main` 的**第一行**调用：加载/生成密钥 → 注入系统属性 → 检查明文库 → 才 `SpringApplication.run`|
|`ConfigEnvironmentPostProcessor`|`EnvironmentPostProcessor`，挂在配置绑定之前；环境变量 / `-D` 已给出的值不覆盖，只在缺省时用密钥文件兜底，并注入 `spring.datasource.url/username/password`|
|`Settings.Loader`（`@Component`）|幂等的第二次保险|

启动脚本实际写出的 `-D` 参数（`.logs/jvm.args`，节选自本机一次真实启动）：

```text
-Dfile.encoding=UTF-8
-Dcampus.runtime=E:\seniorp1\grade-management-system-main\.runtime
-Djavax.net.ssl.trustStore=E:\seniorp1\grade-management-system-main\.runtime\truststore.p12
-Djavax.net.ssl.trustStorePassword=<TLS_PASSWORD>
-DGATEWAY_KEY=<64 位十六进制>
-DBUSINESS_KEY=<…>
-DDATA_KEY=<…>
-DAUDIT_KEY=<…>
-DLEDGER_KEY=<…>
-DAUDIT_DATA_KEY=<…>
-DTLS_PASSWORD=<…>
-DDB_PASSWORD=<secrets.json 里的 DB_PASSWORD 单段值>
-DDB_CIPHER_KEY=<…>
"-DDB_PASSWORD=<DB_CIPHER_KEY> <DB_PASSWORD>"
```

两点必须注意：

- **`-DDB_CIPHER_KEY` 的环境变量形态**：`start.ps1` / `start.sh` 会把 `DB_PASSWORD` **环境变量**设为两段式整串（`DB_CIPHER_KEY + ' ' + DB_PASSWORD`），因为 `DbCredentials.password()` 对 `DB_PASSWORD` 的取值就是「整串会话口令」。若运维只要单独设置文件口令，则应改用 `-DDB_CIPHER_KEY` 或直接用两段式的 `DB_PASSWORD`。
- **两段式 `-DDB_PASSWORD` 在 `@argfile` 里必须加引号**：JVM 的 argfile 解析器按空白拆分，不引起来会变成两个参数，第二个被当成主类，报 `ClassNotFoundException`。`start.ps1` 里对应的一行是 `$arguments += ('"-DDB_PASSWORD=' + $dbPassword + '"')`，`start.sh` 用 bash 数组天然保留空格。所以上面最后一行带引号、倒数第三行不带引号，这是刻意的。

`chain-worker` 不走 Java 注入：`chain-worker/server.mjs` 的 `loadSecrets()` 从**环境变量或 `.runtime/secrets.json`** 读取 `LEDGER_KEY` / `AUDIT_KEY`；缺失、长度 < 16 或仍是占位值（`KEY` / `passwd` / `changeme`）时打印提示并 `process.exit(1)`，不会「看起来在跑、其实密钥是默认值」。

#### 3.3.4 生产档校验（拒绝启动）

设置 `CAMPUS_PROFILE=prod` 或 `-Dcampus.profile=prod` 后，`ConfigGuard.load()` 不再兜底生成密钥：

|判据|结果|
|---|---|
|缺少任何一项密钥|拒绝启动，打印「缺少密钥：…」清单|
|值是占位值 `KEY` / `passwd` / `password` / `changeme` / `campus-dev-tls-2024`|拒绝启动，打印「仍是占位值或长度不足 16：…」清单|
|值长度 < 16|同上|
|异常文本|`配置校验未通过，拒绝启动（CAMPUS_PROFILE=prod）。` + 缺失/薄弱清单 + 处理方式（`node scripts/setup.mjs` 或由密钥管理系统注入）|

开发档（未设置该变量）下，缺密钥会**就地生成并落盘**到 `.runtime/secrets.json` 并打印 `[ConfigGuard] 已生成开发密钥文件：…`，以便「clone 下来直接跑」；四类进程与 chain-worker 因此拿到同一份值。`TLS_PASSWORD` 在开发档还有一层兜底：缺失时回落到证书脚本里的开发口令 `campus-dev-tls-2024`（仅用于本机演示证书；该值在生产档会被判为占位值）。

#### 3.3.5 密钥轮换的后果：整库重建

`schema_meta` 表的 `version_value` 列存的不是单纯的版本号，而是 **`结构版本:密钥指纹`**：

- 指纹 = `ConfigGuard.dataFingerprint()` = SHA-256(按清单顺序拼接的 `KEY=value` 行) 的前 8 字节十六进制（16 字符）。它可被用于比对，但无法反推出任何密钥（`ConfigGuardTest` 断言指纹稳定、对任一密钥变化敏感、且不包含密钥内容）。
- 启动时 `SchemaCatalog.init()` 整串比对「结构版本 + 密钥指纹」。**不一致就删除全部业务表并重建**，随后 `DemoInitializer` 重灌演示数据、审计服务重置账本与链锚点。日志形如：

```text
[SchemaCatalog] 结构版本 3:1a2b3c4d5e6f7788 与目标 3（密钥指纹 9f8e7d6c5b4a3210）不一致：删除全部业务表后重建（原有数据：有，将被清空）。
```

原因是成绩 payload、审计发件箱与账本区块都是用**当时那组密钥**加密的：密钥一换，旧密文就解不开了。启动时比对指纹、不一致就重建，好过运行到读某一行时才报完整性失败。因此：

- **轮换密钥 = 丢弃并重建业务库**。轮换前必须备份 `.runtime/database/`，并按 [部署文档](deployment.md#备份与恢复) 的流程处理账本、锚点与链数据。
- `node scripts/setup.mjs --reset` 会重新生成全部 9 项密钥与 TLS 证书，等价于一次完整轮换；只删掉 `.runtime/secrets.json` 再 `node scripts/setup.mjs` 效果相同。
- 只改 `DATA_KEY` 一项同样会改变指纹、触发重建（旧成绩密文不可解密）。
- 生产环境请把密钥交给 KMS / Vault 或秘密挂载管理，并接受「换密钥即重建」这一后果，或自行实现带密钥版本的密文迁移。

#### 3.3.6 常见报错与处置

|现象|原因|处置|
|---|---|---|
|`Wrong password format, must be: file password <space> user password [90050-224]`|只给了单段口令——常见于手工 `java -jar` 时把 `DB_PASSWORD` 设成了 `secrets.json` 里的单段值，或 `@argfile` 里两段式口令没加引号被拆成两个参数|不要手工拼口令：用 `scripts/start.ps1` / `start.sh` 启动，或让 `DbCredentials` 自行从 `secrets.json` 拼两段式；确认日志里的「空格数 1」|
|`Encryption error in file ... [90049-224]`|会话口令里的**文件口令**（`DB_CIPHER_KEY`）错，与库文件创建时用的不是同一把|恢复对应的 `secrets.json` 或备份；若确实已丢失文件口令，库文件无法解密，只能删除 `.runtime/database/` 后以新密钥重建（演示数据可重建）|
|`File corrupted while reading record`|用开启 `CIPHER=AES` 的配置去打开旧的**明文库**|无需手工删库：`DatabaseBootstrap.prepare()` 会按文件头识别并清理后重建；也可手动删除 `.runtime/database/campus.mv.db`|
|`ClassNotFoundException: .../encoding=UTF-8` 或主类名后面多出一串十六进制|PowerShell 拆坏了 `-D` 参数，或两段式 `-DDB_PASSWORD` 未加引号|用 `scripts/start.ps1`；脚本已把参数写进 `.logs/jvm.args` 并对两段式口令加引号|
|`[chain-worker] 缺少可用密钥 LEDGER_KEY：请先运行 node scripts/setup.mjs …`|环境变量未注入且 `secrets.json` 缺失/为占位值|`node scripts/setup.mjs`，或由启动脚本注入（chain-worker 读环境变量或密钥文件）|
|生产档启动即退出并打印「配置校验未通过，拒绝启动（CAMPUS_PROFILE=prod）」|环境变量/`-D` 里缺密钥，或仍是 `KEY` / `passwd` / `campus-dev-tls-2024` 这类占位值|按清单补齐注入；确认没有把示例配置直接搬上生产（见 [3.3.4](#334-生产档校验拒绝启动)）|

### 3.4 数据库

数据源凭据由 `DbCredentials` 解析（详见 [3.3.1](#331-数据库整库加密h2-cipheraes)）：

|环境变量|默认值|说明|
|---|---|---|
|`DB_URL`|`jdbc:h2:file:./.runtime/database/campus;CIPHER=AES;AUTO_SERVER=TRUE`|JDBC 连接串；路径相对于**数据服务工作目录**（必须为项目根）|
|`DB_USER`|`sa`|数据库用户|
|`DB_PASSWORD`|（无默认值）|整库加密的**两段式会话口令**「文件口令 + 空格 + 用户口令」；由密钥表拼成，不写在 yml 里|
|`DB_CIPHER_KEY`|（无默认值）|整库加密的**文件口令**单独项，用于拼接会话口令|

连接池为 HikariCP，`maximum-pool-size: 8`，由 `data-service/.../DataSourceConfig.java` 显式构造（`@ConfigurationProperties("spring.datasource.hikari")` 仍生效）。外部数据库连接串示例见 [8.3 数据库切换](#83-数据库切换)；换成 MySQL / SQL Server / Oracle 时 `CIPHER=AES` 与两段式口令都不再适用，按目标库的账号口令填写 `DB_USER` / `DB_PASSWORD` 即可。

### 3.5 JVM 系统属性

|属性|用途|
|---|---|
|`-Dfile.encoding=UTF-8`|保证跨平台中文读写一致，`scripts/start.sh` 已设置|
|`-Djavax.net.ssl.trustStore=<项目根>/.runtime/truststore.p12`|服务间 HTTPS 调用的信任库（IDE 直启时需要，见 [6. IDEA 调试配置](#6-idea-调试配置)）|
|`-Djavax.net.ssl.trustStorePassword=<TLS_PASSWORD>`|信任库密码；`RpcClient` 自己构造 SSLContext 时则从 `ConfigGuard.secret("TLS_PASSWORD")` 取，不依赖该属性|
|`-Dcampus.runtime=<项目根>/.runtime`|显式指定运行时目录（`Settings.root()` 默认取工作目录下 `.runtime`，等价于 `CAMPUS_RUNTIME`）|
|`-Dcampus.secrets=<路径>`|密钥文件路径覆盖（等价于环境变量 `CAMPUS_SECRETS`），默认 `<运行时目录>/secrets.json`|
|`-Dcampus.profile=prod`|把进程判为生产档（等价于环境变量 `CAMPUS_PROFILE=prod`），缺密钥/占位值即拒绝启动|
|`-DGATEWAY_KEY` … `-DDB_CIPHER_KEY`|9 项密钥的 `-D` 注入形态，由 `scripts/start.ps1` / `start.sh` 写入 `.logs/jvm.args`；手工启动时也可自行传递|
|`-Dcampus.reset-db=true`|显式整库重建开关（等价于 `start.ps1 -ResetDb`）|
|`-Dcampus.trace.repo=true` / `-Dcampus.trace.sql=true`|排障开关：分别打印每次 `repo.find` 与数据服务查询/解密的耗时|

### 3.6 前端与测试

|配置|位置|说明|
|---|---|---|
|Vite 调试端口 5173 / HTTPS|`frontend/vite.config.js`|自动读取 `.runtime/localhost.key\|.crt`；`/api`、`/health` 代理到 `https://localhost:8443`|
|`BROWSER_EXECUTABLE`|环境变量|Playwright 使用自定义浏览器可执行文件路径（`frontend/playwright.config.js:17`）|
|e2e 基准地址 `https://localhost:8443`|`frontend/playwright.config.js`|要求全套服务已启动；串行执行（`workers: 1`）|

## 4. 首次初始化

任何新机器（任何操作系统）克隆源码后，按以下顺序执行：

```bash
# 1. 安装 Node 依赖（chain-worker 与前端；frontend 的 node-forge 还被 setup 脚本复用）
npm --prefix chain-worker install
npm --prefix frontend install

# 2. 生成开发 TLS 证书、信任库，以及全部密钥与数据库口令（幂等，已存在则复用；--reset 强制重建）
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
- `secrets.json` — **9 项密钥与口令**（`GATEWAY_KEY`、`BUSINESS_KEY`、`DATA_KEY`、`AUDIT_KEY`、`LEDGER_KEY`、`AUDIT_DATA_KEY`、`TLS_PASSWORD`、`DB_PASSWORD`、`DB_CIPHER_KEY`），每项 32 字节随机值的十六进制表示；`0600` 权限、已被 `.gitignore` 忽略

证书库与信任库使用 `secrets.json` 里的 `TLS_PASSWORD` 作口令；口令变化时会自动重新生成证书与信任库。脚本结束时打印两行确认：

```text
✓ TLS 证书就绪（.runtime/localhost.p12，口令为 secrets.json 中的 TLS_PASSWORD）
✓ 数据库整库加密已开启（CIPHER=AES），口令为 secrets.json 中的 DB_CIPHER_KEY + DB_PASSWORD
```

完整密钥清单、注入优先级与轮换后果见 [3.3 安全密钥与整库加密](#33-安全密钥与整库加密)。

**浏览器首次访问需信任 `.runtime/localhost.crt`**（双击导入到受信任的根证书颁发机构，或在浏览器中确认安全例外）。正式部署必须换用组织 CA 签发的证书，不能关闭证书验证。

## 5. 本地开发环境配置

最简方式（零配置，全部使用默认值）：

### macOS / Linux

```bash
./scripts/start.sh    # 启动 chain-worker 与 4 个 Java 服务，日志写入 .logs/，Ctrl+C 全部停止
```

`start.sh` 自己负责把 `secrets.json` 注入为环境变量与 `-D` 参数（两段式 `DB_PASSWORD` 用 bash 数组保留空格），因此不需要任何手工配置。它是 bash 脚本，Windows 上需通过 Git Bash 执行，或按下一节用 `scripts/start.ps1`。

### Windows（PowerShell）

**推荐直接一键启动**：

```powershell
.\scripts\start.ps1              # chain-worker + 4 个 Java 服务，各自独立隐藏窗口，脚本可退出
.\scripts\start.ps1 -NoChain     # 只启动 4 个 Java 服务
.\scripts\start.ps1 -ResetDb     # 先整库重建再启动
```

脚本会读 `.runtime/secrets.json`（不存在则先自动跑 `setup.mjs`），把 9 项密钥同时注入为环境变量与 `-D` 启动参数，把 JVM 参数写进 `.logs/jvm.args` 并对两段式 `-DDB_PASSWORD` 加引号，然后以独立隐藏窗口启动各服务。可用 `-JavaHome <JDK17 目录>` 覆盖 JDK 路径（默认取 `JAVA_HOME`，未设置时回落到开发机的 `D:\JAVA\jdk-jb-17`）。

**手工分窗口启动**时，密钥不会自动注入。最省事的做法其实是**什么都不设**——`ConfigEnvironmentPostProcessor` 会在配置绑定之前从 `.runtime/secrets.json` 兜底加载密钥与两段式数据源口令，因此只要工作目录是项目根、且 `.runtime/secrets.json` 已由 `setup.mjs` 生成，直接 `java -jar …` 就能起来（生产档 `CAMPUS_PROFILE=prod` 没有兜底，缺密钥直接拒绝启动）。

若确实要显式注入（例如密钥文件在别处、或想复现生产注入形态），可以先把密钥载入当前会话，再在同一会话里启动服务：

```powershell
# 0) 载入密钥到当前会话（读 secrets.json；DB_PASSWORD 需要拼成两段式）
$s = Get-Content .runtime\secrets.json -Raw | ConvertFrom-Json
foreach ($n in 'GATEWAY_KEY','BUSINESS_KEY','DATA_KEY','AUDIT_KEY','LEDGER_KEY','AUDIT_DATA_KEY','TLS_PASSWORD','DB_CIPHER_KEY') {
  Set-Item -Path ("Env:" + $n) -Value $s.$n
}
$env:DB_PASSWORD = $s.DB_CIPHER_KEY + ' ' + $s.DB_PASSWORD

# 窗口1：EVM/LSTM 工具（必须最先启动，工作目录 chain-worker/）
cd chain-worker; node server.mjs

# 窗口2-5：Java 服务（项目根目录下执行，继承上面注入的环境变量）
java -Dfile.encoding=UTF-8 -jar gateway/target/gateway-1.0.0.jar
java -Dfile.encoding=UTF-8 -jar data-service/target/data-service-1.0.0.jar
java -Dfile.encoding=UTF-8 -jar audit-service/target/audit-service-1.0.0.jar
java -Dfile.encoding=UTF-8 -jar business-service/target/business-service-1.0.0.jar
```

注意：`Set-Item Env:` 只对**当前 PowerShell 会话**及其子进程有效。用「新建 5 个独立窗口」的方式启动时，每个新窗口都不会继承这些变量，此时应依赖密钥文件兜底（即上面的「什么都不设」），或改用 `scripts/start.ps1`。

启动顺序与 `scripts/start.ps1` 一致：chain-worker → gateway →（约 8 秒）data-service → audit-service →（约 12 秒）business-service。脚本里的等待是经验值（gateway 需要先完成服务发现注册，business-service 依赖数据与审计服务）；服务未就绪时注册会自动重试，但 chain-worker 必须先于审计服务可用。

启动后访问 `https://localhost:8443`。首次运行（或结构版本 / 密钥指纹变化、显式 `-ResetDb`、业务表为空）时 `DemoInitializer` 自动重建演示数据：
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

**启动即清理测试残留**：每次启动时、早于灌数，`DemoInitializer.purgeTestArtifacts()` 会清掉端到端脚本留下的测试教学班与测试选课批次（判据：名称含「测试」或「低人数」，或学期/批次不在演示数据声明的学期集合内），级联删除对应的选课、成绩、选课流水与分析。日志形如 `[DemoInitializer] 已清除 20 门测试课程、48 条选课、0 条成绩、0 个测试选课批次（另有 96 条选课流水）。`；没有残留时打印「未发现测试课程/测试选课批次残留」。因此跑完测试**重启一次即可恢复干净界面**，不必再 `-ResetDb`（本项目最近一次按 `-ResetDb` 整库重建的完整五条日志与复核见 [测试文档](testing.md) 的「演示数据恢复」）。

**排障开关（默认关闭）**：`-Dcampus.trace.repo=true` 打印每次 `repo.find` 的耗时（定位业务侧往返次数），`-Dcampus.trace.sql=true` 打印数据服务每次查询/解密的耗时。例如：
```powershell
java -Dcampus.trace.repo=true -Dcampus.trace.sql=true -jar business-service/target/business-service-1.0.0.jar
```
Windows 下推荐用 `.\scripts\start.ps1` 一键启动（避免 PowerShell 拆坏 `-D` 参数），需要手动整库重建时加 `-ResetDb`。

### 修改默认端口/密钥（本地多实例场景）

端口环境变量照旧。**密钥不要临时改成短口令**——`ConfigGuard.load()` 的长度与占位值判据只在**生产档**生效（`production && (占位值 || 长度 < 16)`）：生产档下短口令会直接拒绝启动，而开发档若手工注入了短值会被原样采用（开发档的兜底只负责「缺失时生成」，不负责「太短时替换」）。因此自定义密钥请写进密钥文件，或使用 64 位十六进制：

PowerShell 示例（为当前进程设置环境变量）：

```powershell
$env:BUSINESS_SERVICE_PORT="9451"
$env:GATEWAY_KEY="0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
$s = Get-Content .runtime\secrets.json -Raw | ConvertFrom-Json
$env:DB_PASSWORD = $s.DB_CIPHER_KEY + ' ' + $s.DB_PASSWORD   # 整库加密的两段式会话口令
java -Dfile.encoding=UTF-8 -jar business-service/target/business-service-1.0.0.jar
```

bash 示例：

```bash
BUSINESS_SERVICE_PORT=9451 \
GATEWAY_KEY=0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef \
  java -Dfile.encoding=UTF-8 -jar business-service/target/business-service-1.0.0.jar
```

注意：**签名密钥必须在所有服务间保持一致**（如改了 `DATA_KEY`，gateway/business/data/audit 四个进程都要用同一个值），否则内部调用验签失败返回 401/403。整库加密的口令还要遵守两段式格式，见 [3.3.1](#331-数据库整库加密h2-cipheraes)。

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
4. 每个运行配置的 **Working directory 设为项目根目录**（`$ProjectFileDir$`），否则找不到 `.runtime/localhost.p12`、`.runtime/secrets.json` 和 H2 数据库文件。
5. **密钥不需要在运行配置里填**：`ConfigEnvironmentPostProcessor` 在配置绑定之前从环境变量 / `-D` / `.runtime/secrets.json` 取密钥与两段式数据源口令。启动日志会打印一行不含口令内容的诊断（三处来源分别是「环境变量」「-D 启动参数」「密钥文件（两段式）」；下面这条是本机 `scripts/start.ps1` 启动时的原文）：

```text
[ConfigGuard] 数据源口令来源：环境变量，长度 129，空格数 1
[DataSourceConfig] 数据库 jdbc:h2:file:./.runtime/database/campus;CIPHER=AES;AUTO_SERVER=TRUE；会话口令：环境变量，长度 129，空格数 1
```

在 IDEA 里直接跑主类时（不设任何环境变量）这两行会显示「密钥文件（两段式）」。`空格数 1`、`长度 129`（64 + 1 + 64）是两段式口令的正确形态，也是排查 90050 的第一手证据。

6. 可选的 VM options（只有需要显式指定运行目录或信任库时才加；**不要**把任何口令写进共享的 `.idea/runConfigurations`）：

```text
-Dfile.encoding=UTF-8
-Dcampus.runtime=项目绝对路径/.runtime
-Djavax.net.ssl.trustStore=项目绝对路径/.runtime/truststore.p12
```

Windows 路径示例：`-Dcampus.runtime=E:/seniorp1/grade-management-system-main/.runtime`（正斜杠即可，无需转义反斜杠）。

若确实要在 IDEA 里显式给信任库口令，用 `-Djavax.net.ssl.trustStorePassword=$(读取 secrets.json 的 TLS_PASSWORD)` 的值，而**不是**固定的 `campus-dev-tls-2024`——那个值只在「`TLS_PASSWORD` 完全缺失且非生产档」时作为开发兜底存在。

7. 想验证「生产档缺密钥拒绝启动」时，可在任一运行配置里加环境变量 `CAMPUS_PROFILE=prod` 并临时把 `campus.secrets` 指向一个不存在的路径，进程会在 `SpringApplication.run` 之前抛出配置校验异常。验证完记得去掉，否则后续启动都会失败。

## 7. 测试环境配置

### 单元测试

```bash
mvn -o test          # 离线跑全部模块的单元测试（当前 258 项）
mvn verify           # 校验 + 测试
```

无需启动任何服务，也不需要 `.runtime/secrets.json`：单元测试用 H2 内存库（`jdbc:h2:mem:...`），不影响 `.runtime` 数据。`common` 模块的 `ConfigGuardTest` 覆盖密钥清单、随机性与长度、指纹稳定性/敏感性/不泄漏、生产档占位值判据与两段式口令契约；`data-service` 的 `SchemaCatalogTest` 覆盖「结构版本落后」与「密钥指纹变化」两种整库重建触发条件。

### 集成与端到端测试

要求全套服务已在后台运行（[第 5 节](#5-本地开发环境配置)），且**依次串行执行，不要并发**：

```text
node scripts/feature-test.mjs          # 端到端功能测试（201 项）
node scripts/browser-check.mjs         # 界面浏览器检查（57 项，需前端开发服务器在 5173）
node scripts/verify-sidebar-resize.mjs # 侧栏拖动专项（28 项）
node scripts/ocr-voice-unit.mjs        # OCR/语音纯函数单元（76 项，无需服务）
node scripts/ocr-voice-check.mjs       # OCR/语音浏览器端到端（40 项，需服务与前端服务器）
node scripts/junit-summary.mjs         # 汇总 surefire 报告为 .runtime/logs/junit-summary.json
node scripts/generate-docs.mjs --check # 生成式文档与源码一致（当前 69 个 Java 命名类型）
```

Playwright 首次运行前在 `frontend/` 下执行 `npx playwright install chromium`。如果机器上已有 Chrome/Edge 等浏览器，可用 `BROWSER_EXECUTABLE` 环境变量指定可执行文件路径跳过下载（Windows 示例：`$env:BROWSER_EXECUTABLE="C:/Program Files/Google/Chrome/Application/chrome.exe"`）。

集成测试会写入专用测试课程与账号数据，**只能在演示数据库上运行**，严禁对真实教务数据执行。跑完 `feature-test.mjs` 后 `2027-2` 会留下测试教学班与测试批次（系统没有课程/批次的删除接口），**重启一次服务**即可由 `DemoInitializer.purgeTestArtifacts()` 自动清掉，不必 `-ResetDb`——完整说明与实测日志见 [测试报告](testing.md)。

> 原始交付记录里的 `scripts/api-test.mjs`、`workflow-test.mjs`、`tamper-test.mjs`、`scale-test.mjs` 与 `docs/evidence/` 在当前源码树中**不存在**，`npm --prefix frontend run test:e2e` 所需的 Playwright 用例也未随源码提供；当前可执行的验证命令就是上面这一段，证据文件在 `.runtime/logs/`。

## 8. 生产环境配置

### 8.1 与开发环境的关键差异清单

|项目|开发默认|生产要求|
|---|---|---|
|TLS 证书|自签名 `localhost.p12`|组织 CA 签发，SAN 含正式域名；替换 `.runtime/localhost.p12` 并重建 `truststore.p12`|
|全部 `*_KEY`、`TLS_PASSWORD`、`DB_CIPHER_KEY`、`DB_PASSWORD`|由 `setup.mjs` 随机生成在 `.runtime/secrets.json`（唯一字面量是 TLS 的开发兜底值 `campus-dev-tls-2024`，见 3.2）|通过环境变量 / `-D` 启动参数或受控密钥管理（KMS/Vault/秘密挂载）注入，**禁止写入 Git**；设置 `CAMPUS_PROFILE=prod` 让缺密钥与占位值直接拒绝启动|
|数据库静态加密|H2 整库加密（`CIPHER=AES`）|保留或改用目标库的加密能力；换库后两段式口令不再适用|
|`BIND_ADDRESS`|`127.0.0.1`|`0.0.0.0` 或内网网卡地址，配合防火墙白名单|
|`PUBLIC_ORIGIN`|`https://localhost:8443`|正式对外来源，如 `https://grade.example.edu`|
|`GATEWAY_URL` / `CHAIN_URL`|localhost|各服务实际地址|
|`SERVICE_HOSTS`|localhost 白名单|加入所有服务主机名/IP|
|数据库|H2 文件库（加密）|MySQL 8 / SQL Server / Oracle（见 8.3）|
|演示数据|自动注入|删除/禁用 seed 后使用正式迁移脚本初始化 schema|

### 8.2 生产启动示例（Linux，systemd 风格环境注入）

```bash
# /etc/campus/env  （权限 0600，不入库）
CAMPUS_PROFILE=prod
GATEWAY_KEY=...
BUSINESS_KEY=...
DATA_KEY=...
AUDIT_KEY=...
LEDGER_KEY=...
AUDIT_DATA_KEY=...
TLS_PASSWORD=...
DB_CIPHER_KEY=...
DB_PASSWORD=...
BIND_ADDRESS=10.0.1.10
PUBLIC_ORIGIN=https://grade.example.edu
GATEWAY_URL=https://10.0.1.10:8443
CHAIN_URL=https://10.0.1.11:9545
SERVICE_HOSTS=10.0.1.10,10.0.1.11,10.0.1.12
DB_URL=jdbc:mysql://db.internal:3306/campus?sslMode=VERIFY_IDENTITY
DB_USER=campus_app
```

`CAMPUS_PROFILE=prod` 下 `ConfigGuard` 不再兜底：上面 9 项缺任何一项、或值仍是 `KEY`/`passwd`/`campus-dev-tls-2024` 这类占位值、或长度 < 16，进程都会在启动最早期抛出配置校验异常并打印缺失清单。切换到外部数据库后 `DB_CIPHER_KEY` / 两段式口令不再适用：把 `DB_PASSWORD` 设为该库账号的真实口令即可（`DbCredentials.password()` 对「环境变量 / `-D` 已给出 `DB_PASSWORD`」是直接采用、不再拼接）。

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
|一键启动|`.\scripts\start.ps1`（另有 Git Bash 执行 `scripts/start.sh` 与手工分窗口两条路径，见第 5 节）|`./scripts/start.sh`|同 macOS|
|环境变量语法|PowerShell `$env:VAR="值"`；CMD `set VAR=值`|`export VAR=值` 或行内 `VAR=值 cmd`|同 macOS|
|证书信任|双击 `.runtime/localhost.crt` → 安装到"受信任的根证书颁发机构"（仅限本机测试用途）|钥匙串访问导入并设为始终信任|按发行版放入 `ca-certificates` 并 `update-ca-certificates`|
|路径写法|Node/Java 均接受正斜杠；IDEA VM options 用 `E:/...` 形式|原生 Unix 路径|同 macOS|
|文件权限|**POSIX 位不生效**：`setup.mjs` 的 0700/0600 与 `ConfigGuard.restrictPermissions()` 在 Windows 上被忽略，须用 NTFS ACL 限制 `.runtime`（尤其 `secrets.json`）仅服务账户可读|`setup.mjs` 自动设置 0700/0600；`secrets.json` 由 `ConfigGuard` 尽力设为 `rw-------`|同 macOS|
|参数拆分|`-D` 参数含空格时必须引号，否则被拆成多个参数；`start.ps1` 用 `.logs/jvm.args` 规避|bash 数组天然保留空格|同 macOS|
|换行/编码|Git 建议 `core.autocrlf=input`；JVM 已强制 `-Dfile.encoding=UTF-8`|默认 UTF-8|默认 UTF-8|

源码内所有 Node 脚本使用 `path` 模块拼路径，`setup.mjs` 以参数数组直接调用 `keytool`（不经过 shell），安装路径含空格（如 `C:\Program Files\Java\...`）不会出问题。**本轮已在 Windows + JDK 17 上实测**：`mvn -o test` 258 项全绿、`scripts/start.ps1` 一键启动四个服务与 chain-worker、H2 整库加密库文件创建与打开、`scripts/*.mjs` 的四层前端检查全部跑过；记录见 [测试报告](testing.md) 的「第八轮」一节。开发机的另一套记录来自 macOS ARM64，历史小节里的 Windows 未实测表述已按本轮结果更新。

Node 26 上 Ganache 的原生 uWS 模块可能回退为 JS 实现，仅影响性能不影响功能；如追求稳定可用 Node 22 LTS。

## 10. 运行时目录结构

所有运行期数据都在 `.runtime/`（相对各进程工作目录，故 Java 服务工作目录必须是项目根）：

```text
.runtime/
├── secrets.json       # 9 项密钥与数据库口令（0600；Windows 用 ACL），已被 .gitignore 忽略
├── localhost.p12      # TLS 密钥库（Java 服务）
├── localhost.crt      # PEM 证书（浏览器信任、Vite、chain-worker）
├── localhost.key      # PEM 私钥（chain-worker、Vite），0600
├── truststore.p12     # Java 服务间调用信任库
├── database/          # H2 数据文件（data-service），整库加密，文件名 campus.mv.db
├── ledger/            # 审计账本 events.jsonl（audit-service）
├── ethereum/          # Ganache LevelDB 链数据（chain-worker）
├── backup/            # 手工/脚本备份落点（若已创建）
├── anchors.json       # 账本哈希 ↔ EVM 交易锚点
└── logs/              # 运行日志与测试证据 JSON
```

`.logs/` 存放 `start.ps1` / `start.sh` 捕获的各进程 stdout/stderr（另有 JVM 参数文件 `.logs/jvm.args`）。备份与恢复的完整要求（停写、SHA-256 校验、先跑完整性验证再恢复写入）见 [部署文档](deployment.md#备份与恢复)。**不要通过删除 `.runtime` 来"修复"审计报错**，那会销毁原始证据——尤其会一并丢掉 `secrets.json`，导致加密库文件再也无法解开。

## 11. 常见问题

|现象|原因与处理|
|---|---|
|启动报 `Missing setting: XXX`|该键既没有环境变量 / `-D`，也没有 yml 默认值。密钥类键由 `ConfigGuard` 统一注入，出现此错通常是新加了一个键却忘了同步 `ConfigGuard.REQUIRED_KEYS` / `setup.mjs` 的 `SECRET_KEYS` / 启动脚本，检查 [第 3 节](#3-配置项完整清单)|
|`trustAnchors must be non-empty`|信任库缺失或路径错误：重跑 `node scripts/setup.mjs`，IDE 启动时确认 `-Djavax.net.ssl.trustStore` 指向 `truststore.p12`（不是 `localhost.p12`）|
|服务间调用 401/403|各进程密钥不一致（如只给某一个服务设了环境变量，其余进程仍读密钥文件）：确认所有进程用的是同一份 `secrets.json` 或同一组注入值|
|`Wrong password format, must be: file password <space> user password [90050-224]`|整库加密口令只给了一段，或 `@argfile` 里两段式 `-DDB_PASSWORD` 未加引号被拆开；见 [3.3.6](#336-常见报错与处置)|
|`Encryption error in file ... [90049-224]`|库文件的**文件口令**（`DB_CIPHER_KEY`）与创建时不一致；见 [3.3.6](#336-常见报错与处置)|
|8443 拒绝连接|查看 `.logs/gateway.log`；常见为端口占用或证书未生成|
|浏览器提示证书不受信|未导入 `.runtime/localhost.crt`，见 [第 9 节](#9-各操作系统差异)|
|H2 报"数据库已占用"|另一个 data-service 实例或数据库 GUI 正打开同一文件，H2 文件模式不允许多开|
|前端 5173 无法启动|未运行 `setup.mjs` 导致缺少 `localhost.key/.crt`，或 5173 被占（`strictPort: true`）|
|换了任一密钥后旧成绩读不出来 / 启动日志说「整库重建」|这是设计行为：`schema_meta` 存的是「结构版本:密钥指纹」，指纹变化即整库重建；轮换前先备份 `.runtime/database/`，见 [3.3.5](#335-密钥轮换的后果整库重建)|
|Playwright 找不到浏览器|运行 `npx playwright install chromium`，或设置 `BROWSER_EXECUTABLE`|

更完整的故障排查（审计 503、EVM gas、OCR 加载等）见 [部署文档 §故障排查](deployment.md#故障排查)。
