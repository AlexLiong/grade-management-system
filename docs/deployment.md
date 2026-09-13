# 部署、运行与恢复

## 依赖

JDK 17（必须是完整 JDK，含 keytool 和编译器）、Maven 3.9+、Node.js 22 LTS。Node 26 也可运行，但 Ganache 原生 uWS 模块可能回退为 JS 实现，不影响本机功能。首次下载需可访问 Maven Central 和 npm；生产环境使用公司受信任的制品镜像。

运行步骤见 README。初始化幂等：已有随机配置、数据库和证书不覆盖。新机器必须重新执行 setup，不能复制旧机器的绝对路径配置。前端 OCR 语言包来自锁定的 npm 包，不需要访问外部 CDN。

## 端口与工作目录

|进程|默认地址|入口|
|---|---|---|
|统一网关|127.0.0.1:8443 HTTPS|GatewayApplication|
|业务服务|127.0.0.1:9441 HTTPS|BusinessApplication|
|数据服务|127.0.0.1:9442 HTTPS|DataApplication|
|审计服务|127.0.0.1:9443 HTTPS|AuditApplication|
|EVM/LSTM 工具|127.0.0.1:9545 HTTPS|chain-worker/server.mjs|
|Vue 调试|127.0.0.1:5173 HTTPS|Vite，可选|

Java 工作目录设项目根；Node chain-worker 工作目录设 chain-worker。`start.mjs` 自动处理这些目录，并将 JAR 复制到独立 `.runtime/run-*` 目录运行，避免重新编译破坏在途类加载。旧运行副本可在全部服务停止后清理。每个服务 JVM 最大堆 384 MB，另留 Node/WASM 及操作系统资源，建议至少 4 GB 可用内存。

## IDEA 运行配置

导入根 Maven 项目，不要只打开某个 Java 文件。先执行 setup 和前端 build；运行链工具；为四个 Application 创建 Application 配置。

VM options：

```text
-Dcampus.runtime=项目绝对路径/.runtime
-Djavax.net.ssl.trustStore=项目绝对路径/.runtime/truststore.p12
-Djavax.net.ssl.trustStorePassword=本机配置中的TLS_PASSWORD
```

密码不能放进提交到 Git 的共享 IDEA 配置。可以从 IDE 的私有环境/运行参数填写。`start.mjs` 已自动读取配置并设置，通常更便于启动全套进程。

## Windows

使用 PowerShell 执行 README 的 node/npm/mvn 命令。不要依赖 `.sh`、`chmod` 或 Unix 路径；源码的 Node 脚本使用 `path` 生成跨平台路径。`setup.mjs` 直接按参数数组调用 keytool，避免含空格目录被 shell 拆分。使用 Windows 信任管理器导入本机证书并仅信任本地测试用途。NTFS ACL 需要限制 `.runtime` 为当前服务用户可访问。

当前开发机器是 macOS ARM64，已测试 macOS；Windows 操作系统级证书、文件锁和进程停止行为未实测。部署验收须在目标 Windows 环境运行相同的 Java/API/浏览器测试，不以脚本可移植性替代实测。

## 数据库切换

|数据库|DB_URL 示例|参数|
|---|---|---|
|默认 H2|自动生成 `jdbc:h2:file:...;CIPHER=AES;DB_CLOSE_ON_EXIT=FALSE`|密码由文件密钥和用户密码组合|
|MySQL 8|`jdbc:mysql://db.internal:3306/campus?sslMode=VERIFY_IDENTITY`|DB_USER/DB_PASSWORD；服务端证书须受信任|
|SQL Server|`jdbc:sqlserver://db.internal:1433;databaseName=campus;encrypt=true;trustServerCertificate=false`|DB_USER/DB_PASSWORD|
|Oracle|`jdbc:oracle:thin:@tcps://db.internal:2484/CAMPUS`|DB_USER/DB_PASSWORD；配置 Oracle wallet/信任库|

外部数据库账号只允许访问本系统 schema，不使用 root/sa/SYS。开发初始化会创建表和索引，生产应先执行经过审阅的迁移，然后收回 DDL 权限。`SchemaCatalog` 对 MySQL LONGTEXT、SQL Server VARCHAR(MAX)、H2/Oracle CLOB 有适配；查询分页使用 JDBC 游标。尚未在 MySQL/SQL Server/Oracle 实例上进行连接和事务兼容性验收。

## 服务扩缩容

业务服务是优先可扩容组件：复制 business-service JAR，以不同 PORT 和 ADVERTISE_URL 启动。所有副本指向同一网关和数据服务，使用相同会话存储。

PowerShell：

```powershell
$env:PORT="9451"
$env:ADVERTISE_URL="https://localhost:9451"
java "-Dcampus.runtime=C:/work/campus-grade-system/.runtime" "-Djavax.net.ssl.trustStore=C:/work/campus-grade-system/.runtime/truststore.p12" "-Djavax.net.ssl.trustStorePassword=本机TLS密码" -jar business-service/target/business-service-1.0.0.jar
```

macOS/Linux 同样设置 PORT/ADVERTISE_URL 后运行。新副本 5 秒内注册，20 秒未续租则不再参与轮询。连接失败会返回 503，不对未知是否成功的写操作自动重试。重试时必须刷新版本。

跨机器必须配置 BIND_ADDRESS、ADVERTISE_URL、GATEWAY_URL、SERVICE_HOSTS、目标主机证书 SAN；设置防火墙 allowlist。默认 localhost 证书不适用于其他主机。当前注册中心单实例，生产多网关需共享注册后端；升级 Spring Cloud 组件的决策见架构文档。

H2 文件模式不支持多个独立数据服务同时打开同一数据库。外部数据库下可再评估数据服务扩容，但审计发送顺序与跨副本排他需要补充协调。本版本只验证业务服务多实例，不宣称数据和审计横向扩容已可直接无损上线。

## 备份与恢复

停写后备份数据库、ledger/events.jsonl、ethereum 目录、anchors.json，以及单独加密保管的 config 和证书。备份时停止进程避免 H2/LevelDB 文件不一致。为每份备份记录 SHA-256，恢复后先运行 `/integrity` 和 `/audit` 验证，再恢复敏感写入。

不要删除 `.runtime` 来“修复”审计错误；这会丢失原始证据。演示测试中的 tamper-test 使用明确的原始密文备份并自动恢复，只对本工程的合成数据执行，不能用于真实教务数据库。

## 故障排查

|现象|定位与处理|
|---|---|
|8443 无法连接|检查 `.runtime/logs/supervisor.log`，确认 Java/Node PATH 和端口占用|
|trustAnchors must be non-empty|重新运行 setup，使用 truststore.p12，不要把私钥库当证书信任库|
|登录服务未就绪|查看 /health，等待三个 Java 服务完成注册和 seed；不要反复错误登录|
|503 审计同步中|查看 data-service.log 的 outbox 错误；检查链工具启动及文件权限|
|EVM insufficient funds|测试账户应稳定且自动补充测试 gas，检查是否误替换本机密钥配置|
|OCR 模型加载失败|运行 assets.mjs 后重新 build；检查本机 OCR 路径、CSP 和证书|
|课程 409|刷新版本；若提示完整性错误，应按安全应急流程处理|
|数据库已占用|确认没有第二个数据服务或数据库 GUI 打开 H2 文件|
|移动端表格超宽|表格容器支持横向滚动，页面本身不应出现横向溢出|

## 交付目录对应

用户要求先不做答辩资料，因此源码工程保留标准 Maven/Vue 目录。课程 PDF 的“4 系统代码/Web前端网络设计”对应 frontend；“远程方法客户端”对应 gateway/business/common；“远程方法服务端”对应 data-service；“安全设计”对应 common/audit/chain 与 security.md；“5 模型文件”对应 docs/models.md 与 class-diagrams.md。取得小组编号后可调整压缩包名，当前不虚构“第 X 组”信息。
