# 部署、运行与恢复

## 依赖

JDK 17（必须是完整 JDK，含 keytool 和编译器）、Maven 3.9+、Node.js 22 LTS。Node 26 也可运行，但 Ganache 原生 uWS 模块可能回退为 JS 实现，不影响本机功能。首次下载需可访问 Maven Central 和 npm；生产环境使用公司受信任的制品镜像。

运行步骤见 README。初始化幂等：已有的密钥、证书、数据库和链数据不覆盖。`node scripts/setup.mjs` 除了证书，还会用 `crypto.randomBytes(32)` 生成 **9 项密钥与口令**写入 `.runtime/secrets.json`（0600、被 `.gitignore` 忽略）——其中 `DB_CIPHER_KEY` 与 `DB_PASSWORD` 是 H2 **整库加密**的「文件口令 + 空格 + 用户口令」；口令变化时证书与信任库会自动重建。新机器必须重新执行 setup，不能复制旧机器的绝对路径配置；也**不能只复制数据库文件而不复制 `secrets.json`**，否则库文件打不开（90049）。前端 OCR 语言包来自锁定的 npm 包，不需要访问外部 CDN。

## 端口与工作目录

|进程|默认地址|入口|
|---|---|---|
|统一网关|127.0.0.1:8443 HTTPS|GatewayApplication|
|业务服务|127.0.0.1:9441 HTTPS|BusinessApplication|
|数据服务|127.0.0.1:9442 HTTPS|DataApplication|
|审计服务|127.0.0.1:9443 HTTPS|AuditApplication|
|EVM/LSTM 工具|127.0.0.1:9545 HTTPS|chain-worker/server.mjs|
|Vue 调试|127.0.0.1:5173 HTTPS|Vite，可选|

Java 工作目录设项目根；Node chain-worker 工作目录设 chain-worker。`scripts/start.ps1`（Windows）与 `scripts/start.sh`（macOS/Linux）都会先读 `.runtime/secrets.json`，把 9 项密钥**同时**注入为环境变量与 `-D` 启动参数，再启动 chain-worker 与四个 Java 服务，日志写入 `.logs/`。启动顺序为 chain-worker → gateway →（约 8 秒）data-service → audit-service →（约 12 秒）business-service；`start.ps1` 以独立隐藏窗口启动各进程，因此脚本本身可以退出，`start.sh` 则在前台等待、Ctrl+C 统一停止。建议为每个 Java 服务限制最大堆（如 `-Xmx384m`），另留 Node/WASM 及操作系统资源，整机建议至少 4 GB 可用内存。

`start.ps1` 会把 JVM 参数写进 `.logs/jvm.args`，其中两段式口令那一行必须带引号（`"-DDB_PASSWORD=<文件口令> <用户口令>"`）——JVM 的 argfile 解析器按空白拆分参数，不加引号会被拆成两个参数，第二个随后被当作主类，报 `ClassNotFoundException`。手工用 `java -jar` 启动时，可以直接不设任何密钥（`ConfigEnvironmentPostProcessor` 会从 `.runtime/secrets.json` 兜底），但**不能只设单段 `DB_PASSWORD`**，否则 H2 报 90050。

## IDEA 运行配置

导入根 Maven 项目，不要只打开某个 Java 文件。先执行 setup 和前端 build；运行链工具；为四个 Application 创建 Application 配置。

VM options（**密钥不必填**，由 `.runtime/secrets.json` 兜底；口令不要放进提交到 Git 的共享 IDEA 配置）：

```text
-Dcampus.runtime=项目绝对路径/.runtime
-Djavax.net.ssl.trustStore=项目绝对路径/.runtime/truststore.p12
-Djavax.net.ssl.trustStorePassword=本机 secrets.json 中的 TLS_PASSWORD
```

`-Djavax.net.ssl.trustStorePassword` 只影响 JVM 默认 SSLContext；`RpcClient` 自己构造 SSLContext 时从 `ConfigGuard.secret("TLS_PASSWORD")` 取口令，因此即使不设置这一项也能完成服务间调用。使用 `scripts/start.sh` 启动全套进程时以项目根为工作目录直接运行 JAR，无需手工设置这些 VM 参数，通常更为便捷。

## Windows

使用 PowerShell 执行 README 的 node/npm/mvn 命令。不要依赖 `.sh`、`chmod` 或 Unix 路径；源码的 Node 脚本使用 `path` 生成跨平台路径。`setup.mjs` 直接按参数数组调用 keytool，避免含空格目录被 shell 拆分。使用 Windows 信任管理器导入本机证书并仅信任本地测试用途。**POSIX 位在 Windows 上不生效**：`.runtime`（尤其 `secrets.json`）必须用 NTFS ACL 限制为当前服务用户可访问，`setup.mjs` 的 0700/0600 与 `ConfigGuard.restrictPermissions()` 在这里会被忽略。

Windows 推荐用 `.\scripts\start.ps1` 一键启动（`-NoChain` 只启动 Java 服务、`-ResetDb` 先整库重建、`-JavaHome` 指定 JDK）。本轮已在 Windows + JDK 17 上实测：`mvn -o test` 258 项全绿、一键启动全套服务、整库加密库文件的创建与打开、四层前端检查全部通过（记录见 [测试报告](testing.md) 的「第八轮」）。macOS ARM64 是原始交付机的记录环境；跨平台差异请以 [配置说明第 9 节](configuration.md#9-各操作系统差异) 为准。

## 数据库切换

|数据库|DB_URL 示例|参数|
|---|---|---|
|默认 H2|`jdbc:h2:file:./.runtime/database/campus;CIPHER=AES;AUTO_SERVER=TRUE`（由 `DbCredentials.url()` 提供）|**整库加密**：会话口令是「文件口令 + 空格 + 用户口令」两段式，两段分别来自 `secrets.json` 的 `DB_CIPHER_KEY` 与 `DB_PASSWORD`，不写在 yml 里；换库时用 `DB_URL` / `DB_USER` / `DB_PASSWORD` 覆盖|
|MySQL 8|`jdbc:mysql://db.internal:3306/campus?sslMode=VERIFY_IDENTITY`|DB_USER/DB_PASSWORD；服务端证书须受信任；H2 的两段式口令不再适用|
|SQL Server|`jdbc:sqlserver://db.internal:1433;databaseName=campus;encrypt=true;trustServerCertificate=false`|DB_USER/DB_PASSWORD|
|Oracle|`jdbc:oracle:thin:@tcps://db.internal:2484/CAMPUS`|DB_USER/DB_PASSWORD；配置 Oracle wallet/信任库|

外部数据库账号只允许访问本系统 schema，不使用 root/sa/SYS。开发初始化会创建表和索引，生产应先执行经过审阅的迁移，然后收回 DDL 权限。`SchemaCatalog` 对 MySQL LONGTEXT、SQL Server VARCHAR(MAX)、H2/Oracle CLOB 有适配；查询分页使用 JDBC 游标。尚未在 MySQL/SQL Server/Oracle 实例上进行连接和事务兼容性验收。

## 服务扩缩容

业务服务是优先可扩容组件：复制 business-service JAR，以不同 `BUSINESS_SERVICE_PORT` 启动。所有副本指向同一网关和数据服务，使用相同的全套密钥与 `GATEWAY_URL`。注册地址无需手工指定：副本启动后通过心跳（`ServiceHeartbeat`）自动以自身监听地址和端口向网关注册。

PowerShell：

```powershell
$env:BUSINESS_SERVICE_PORT="9451"
java -Dfile.encoding=UTF-8 -jar business-service/target/business-service-1.0.0.jar
```

macOS/Linux 同样设置 `BUSINESS_SERVICE_PORT` 后运行（工作目录为项目根）。新副本 5 秒内注册，20 秒未续租则不再参与轮询。连接失败会返回 503，不对未知是否成功的写操作自动重试。重试时必须刷新版本。副本不连数据库，因此不需要 `DB_CIPHER_KEY` / `DB_PASSWORD`，但必须拿到与本机其它进程**完全相同**的签名密钥——最省事的做法仍是让工作目录下的 `.runtime/secrets.json` 兜底，或由部署系统注入同一组环境变量。

跨机器必须配置 BIND_ADDRESS、GATEWAY_URL、PUBLIC_ORIGIN、SERVICE_HOSTS、目标主机证书 SAN；设置防火墙 allowlist。默认 localhost 证书不适用于其他主机。当前注册中心单实例，生产多网关需共享注册后端；升级 Spring Cloud 组件的决策见架构文档。

H2 文件模式不支持多个独立数据服务同时打开同一数据库。外部数据库下可再评估数据服务扩容，但审计发送顺序与跨副本排他需要补充协调。本版本只验证业务服务多实例，不宣称数据和审计横向扩容已可直接无损上线。

## 备份与恢复

停写后备份数据库、ledger/events.jsonl、ethereum 目录、anchors.json，以及单独加密保管的 `secrets.json`、证书与信任库。备份时停止进程避免 H2/LevelDB 文件不一致。为每份备份记录 SHA-256，恢复后先运行 `/integrity` 和 `/audit` 验证，再恢复敏感写入。

**密钥备份与数据备份是一对**：H2 库文件是整库加密的，只有配套的 `secrets.json`（`DB_CIPHER_KEY` + `DB_PASSWORD`）能打开它；密钥丢失时库文件等于不可读，且重建密钥会让 `schema_meta` 的密钥指纹失配并触发整库重建（旧成绩密文与账本区块同样解不开）。因此两者必须一起备份、分开保管，并验证恢复流程。

不要删除 `.runtime` 来“修复”审计错误；这会丢失原始证据，并且会一并丢掉 `secrets.json`。演示测试中的 tamper-test 使用明确的原始密文备份并自动恢复，只对本工程的合成数据执行，不能用于真实教务数据库。

## 故障排查

|现象|定位与处理|
|---|---|
|8443 无法连接|检查 `.logs/gateway.log`，确认 Java/Node PATH 和端口占用；`start.ps1` 的日志在 `.logs/`（`.runtime/logs/` 只放测试证据 JSON）|
|trustAnchors must be non-empty|重新运行 setup，使用 truststore.p12，不要把私钥库当证书信任库|
|`Wrong password format, must be: file password <space> user password [90050-224]`|整库加密口令只给了一段，或 `.logs/jvm.args` 里两段式 `-DDB_PASSWORD` 没加引号被拆开；见 [配置说明 3.3.6](configuration.md#336-常见报错与处置)|
|`Encryption error in file ... [90049-224]`|库文件与 `DB_CIPHER_KEY` 不是同一把；恢复配套的 `secrets.json` 或备份|
|生产档启动即退出并打印「配置校验未通过，拒绝启动（CAMPUS_PROFILE=prod）」|缺密钥或仍是占位值；按清单补齐环境变量 / `-D` 注入|
|登录服务未就绪|查看 /health，等待四个 Java 服务完成注册和 seed；不要反复错误登录|
|503 审计同步中|查看 data-service.log 的 outbox 错误；检查链工具启动及文件权限|
|EVM insufficient funds|测试账户应稳定且自动补充测试 gas，检查是否误替换本机密钥配置|
|OCR 模型加载失败|确认 `frontend/public/ocr/`（worker.min.js、core、lang）完整后重新 build；检查本机 OCR 路径、CSP 和证书|
|课程 409|刷新版本；若提示完整性错误，应按安全应急流程处理|
|数据库已占用|确认没有第二个数据服务或数据库 GUI 打开 H2 文件|
|移动端表格超宽|表格容器支持横向滚动，页面本身不应出现横向溢出|

## 交付目录对应

用户要求先不做答辩资料，因此源码工程保留标准 Maven/Vue 目录。课程 PDF 的“4 系统代码/Web前端网络设计”对应 frontend；“远程方法客户端”对应 gateway/business/common；“远程方法服务端”对应 data-service；“安全设计”对应 common/audit/chain 与 security.md；“5 模型文件”对应 docs/models.md 与 class-diagrams.md。取得小组编号后可调整压缩包名，当前不虚构“第 X 组”信息。
