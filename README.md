# 安全分布式高校成绩管理系统

本项目依据《软件开发综合能力实践（网络软件与安全）要求说明》实现。系统包含 Vue 3 浏览器端、Spring Boot Web/RMI 客户端、独立 Java RMI 数据服务和关系数据库四个运行边界，覆盖教师、学生、管理员三类角色的成绩业务、分布式对象调用和分层安全控制。

## 1. 核心能力

- 教师：课程与往年成绩查询、动态百分制权重、正考与补考录入、暂存、提交、撤回、统计分析、打印、CSV/粘贴/语音/OCR 辅助录入。
- 学生：本学期与在校成绩、`正考/补考计分` 展示、补考 60 分封顶、排名、加权平均、学分、挂科红灯、打印和本人学业风险分析。
- 管理员：人员与组织维护、角色权限和个人权限覆盖、成绩筛选与跨页选择、审计与告警、小撤销、大撤销双人复核、复核信息追踪、外部证据校验、原始成绩预览与恢复。
- 智能分析：均值、分位数和 `3 sigma` 异常检测；至少三学年样本上的多元最小二乘回归；可解释 CART 决策树；预测只在响应中展示，不写正式成绩表。
- 分布式后台：查询、操纵、健康和完整性四个 RMI 对象；结构化值传递；允许列表 SQL；参数绑定；多命令事务；幂等与乐观锁。
- 安全：BCrypt、HttpOnly JWT Cookie、CSRF、登录限速、RBAC/细粒度权限/资源所有权、HTTPS、RMI HMAC/时间窗/nonce/JEP 290/TLS、AES-256-GCM、成绩 HMAC、数据库审计和数据库外哈希链。

需求到代码和测试证据的对应关系见 [需求与验收追踪](docs/01-需求与验收追踪.md)。

## 2. 架构与模块

```text
Vue 3 browser
    -> HTTPS REST + CSRF/JWT Cookie
Spring Boot Web backend (RMI client)
    -> signed Java RMI value objects
Spring Boot RMI data server
    -> PreparedStatement transaction -> database
    -> encrypted snapshot -> external hash-chain ledger
```

| 目录 | 说明 |
| --- | --- |
| `web-frontend` | Vue 3、TypeScript、Vite、Pinia、Vue Router、Element Plus |
| `web-backend` | REST、安全会话、领域规则、成绩加密、统计和 AI、RMI 客户端 |
| `rmi-contract` | Remote 接口、可序列化 DTO、规范化签名和共享成绩密文格式 |
| `rmi-server` | RMI 导出、远程授权、安全 SQL、事务、数据库、审计证据与恢复 |
| `docs` | 需求追踪、整体架构、逐类说明、安全测试、模型图和 API 说明 |
| `scripts` | 本地密钥生成、验证、HTTPS 一键启动与停止 |

Web 后端没有 JDBC 依赖，数据库连接只存在于 RMI 数据服务。浏览器和 REST API 都不能提交裸 SQL。

## 3. 环境要求

- JDK 17
- Maven 3.9+
- Node.js 20.19+（已验证 Node 26）
- npm 10+
- OpenSSL 3.x（仅一键生成本地开发证书时需要）

当前默认数据源是 H2 文件数据库，不需要预装数据库。首次运行会创建三学年以上的演示课程和成绩；之后重启不会覆盖已经持久化的数据。

## 4. 一键运行

在项目根目录执行：

```bash
./scripts/start-local.sh
```

脚本将完成以下工作：

1. 在被 Git 忽略的 `runtime/` 生成相互独立的随机运行时密钥；
2. 生成带 `localhost` 和 `127.0.0.1` SAN 的本地自签名证书；
3. 构建并启动独立 RMI JVM、HTTPS Web JVM 和 HTTPS Vite 进程；
4. 等待健康检查通过并把 PID、日志写入 `runtime/`。

`runtime/` 包含本机生成的 JWT、成绩、RMI 密钥和 TLS 私钥/证书以及演示数据库，已由 `.gitignore` 排除。提交 Git 或压缩课程源码时不要包含该目录；目标机器首次运行会自动生成自己的运行状态。

启动成功后访问：

```text
https://127.0.0.1:5173
```

本地证书不是公共 CA 签发，浏览器首次会显示证书警告。只应在本机课程演示环境接受该证书；生产环境必须替换为受信任证书。

停止三个本地进程：

```bash
./scripts/stop-local.sh
```

端口可通过 `RMI_REGISTRY_PORT`、`RMI_SERVICE_PORT`、`WEB_PORT`、`VITE_PORT` 覆盖。默认值依次是 `1199`、`1200`、`8443`、`5173`。

## 5. 演示账号

首次创建的所有演示账号密码均为 `password`。这些口令只用于本机演示，生产部署不得加载 `demo-data.sql`。

| 角色 | 用户名 | 推荐演示内容 |
| --- | --- | --- |
| 管理员 A | `admin` | 用户、权限、审计、发起高风险操作 |
| 管理员 B | `admin02` | 使用另一浏览器会话完成双人复核 |
| 教师 | `teacher01` | 当前学期课程、成绩录入、统计与预测 |
| 教师 | `teacher02` | 第二位教师的资源隔离验证 |
| 学生 | `student01` | 本学期/在校成绩、排名和预警 |
| 学生 | `student02` | 学生之间的水平越权反向验证 |

`web-backend` 是内部 RMI 主体，不能交互登录。数据库中的内部角色也不能通过管理员页面分配给人员账号。
`auditor` 仅用于验证非交互角色的登录拒绝，不能进入 Web 工作台。

## 6. IDEA 与 Node 分开启动

需要调试源码时，可按以下顺序启动：

1. 运行 `./scripts/generate-local-secrets.sh`。
2. 在 IDEA 中先运行 `RmiDataServerApplication`，工作目录设为项目根目录。
3. 再运行 `GradeWebApplication`。不启用 profile 时为便于调试使用 `http://127.0.0.1:8080`；启用 `local` profile 时还需提供 `TLS_KEYSTORE` 和 `TLS_KEYSTORE_PASSWORD`，一键脚本已自动处理这些变量。
4. 在 `web-frontend` 执行 `npm ci`，再执行 `VITE_API_TARGET=http://127.0.0.1:8080 npm run dev`。

RMI 服务必须先启动。Web 后端不会在 RMI 不可用时回退到本地数据库，而会返回明确的 `503 RMI_UNAVAILABLE`。

## 7. 验证

运行完整 Java、前端单元测试和生产构建：

```bash
./scripts/verify.sh
```

也可分别执行：

```bash
mvn test
cd web-frontend
npm test
npm run build
```

手工安全用例、预期结果和应急步骤见 [安全测试与应急说明](docs/04-安全测试与应急.md)。

## 8. 关键业务规则

- 所有成绩分项都是百分制，范围 `0..100`；评分权重总和必须精确等于 `100`。
- 正考总评按评分方案计算。补考保留卷面原始分，计分使用 `min(卷面分, 60)`。
- 教师只能操作本人开课班；学生 API 不接受目标学生编号，只从登录身份确定本人。
- 已提交成绩不能直接覆盖。小撤销执行 `SUBMITTED -> DRAFT`；大撤销执行受双人复核的业务记录删除，外部证据不删除。
- 成绩草稿与评分方案更新携带期望版本并采用乐观锁；其余写操作按各自状态、审批和幂等约束执行。批量写在 RMI 服务端同一数据库事务执行。结果未知的重试保留原 UUID，Web 以稳定业务输入生成 SHA-256 语义指纹，并在业务状态校验前查询 RMI 数据库中的事务记录；同主体、同键、同语义的已提交事务直接复用结果，同键异语义拒绝，因此不会重复落库，也不受重新加密产生的新 nonce/密文影响。
- 权限配置由服务端校验依赖关系，例如成绩写入/提交需要课程与成绩读取，风险分析需要相应成绩读取；前端的勾选联动只是交互提示。角色和个人权限变更还会在 RMI 事务内串行校验，任何最终状态都必须保留至少一名启用且同时具备用户管理、权限管理能力的管理员。
- AI 结果是辅助信息，不会自动改分，也不会写入正式成绩表。

## 9. 配置与生产部署

仓库不包含真实秘密。主要环境变量如下：

| 变量 | 用途 |
| --- | --- |
| `JWT_SECRET` / `JWT_KEY_FILE` | Web JWT 签名密钥 |
| `GRADE_MASTER_KEY` / `GRADE_KEY_FILE` | Web 成绩主密钥 |
| `GRADE_DATA_KEY` / `GRADE_DATA_KEY_FILE` | RMI 成绩主密钥，必须与 Web 一致 |
| `RMI_HMAC_SECRET` / `RMI_HMAC_KEY_FILE` | Web 与 RMI 的调用签名密钥 |
| `GRADE_DB_URL`、`GRADE_DB_USERNAME`、`GRADE_DB_PASSWORD`、`GRADE_DB_DRIVER` | RMI 数据源 |
| `GRADE_LEDGER_FILE` | 数据库外完整性账本 |
| `TLS_KEYSTORE`、`TLS_KEYSTORE_PASSWORD` | Web HTTPS 证书 |
| `OCR_API_URL`、`OCR_API_TOKEN` | 可选 OCR 服务 |
| `RMI_TIMEOUT` | Web 后端查找 RMI registry 时的连接/读取超时，默认 `PT5S` |
| `RMI_CLIENT_CONNECT_TIMEOUT_MILLIS` | RMI 服务端写入 registry 与远程对象 stub 的客户端连接超时，默认 `5000` ms |
| `RMI_CLIENT_READ_TIMEOUT_MILLIS` | RMI 服务端写入 registry 与远程对象 stub 的客户端读取超时，默认 `5000` ms |

生产部署使用 `prod` profile。它强制 Web HTTPS 和 RMI TLS，并要求外部密钥。RMI TLS 使用 JSSE keystore/truststore，注册端口和对象端口都只应允许 Web 主机访问。推荐拓扑和网络隔离见 [整体架构说明](docs/02-整体架构说明.md)。

SQL 方言已隔离 H2、MySQL/MariaDB、SQL Server 和 Oracle 的分页差异。H2 是本项目自动化验证的数据源；其他数据库需要提供对应 JDBC 驱动、预建 schema，并在目标环境补做集成测试，不能把“方言已实现”理解为“所有厂商版本均已认证”。

## 10. 课程版边界

- 外部账本是带 HMAC 的追加式私有哈希链，只校验当前文件中现存条目的内容、序号和前后链接，不会自动把数据库逐行与账本对账。数据库成绩密文、nonce 或完整性标签被改时会在读取解密时失败关闭；数据库行删除或未纳入成绩 HMAC 的元数据修改不会由链校验自动发现。缺少外部 head/count 锚点时，整份账本删除或合法尾部截断也可能表现为有效空链/短链；生产应把链头与条目数锚定并将账本异地复制到不可变对象存储或真正的联盟链。
- 操作序列异常检测采用可解释的时间、频率和所有权规则。仓库没有伪装成已训练的 LSTM/Transformer；在没有经过脱敏和标注的日志语料时训练此类模型不会形成可信结果。
- OCR 是显式配置的受控适配器；未配置时 CSV、粘贴和浏览器语音仍可用，OCR 返回明确的不可用状态。
- 登录限速和 nonce 防重放存储适合单实例课程演示。多实例生产部署应迁移到 Redis 等共享存储。
- 注销后的 JWT `jti` 拒绝表也保存在 Web JVM 内存中；Web 重启会丢失尚未到期的撤销记录，多实例之间亦不共享，生产必须使用带 TTL 的共享存储。
- 成绩变更、数据库历史和双人审批消费位于同一个数据库事务；数据库外账本采用失败关闭的同步追加，但不参加 XA。极少数“账本已追加、随后数据库最终提交失败”场景可能留下无对应业务提交的孤立账本项，生产应使用事务 outbox 和异地追加器消除此窗口。
- 本项目不内置 WAF、IDS、MFA、公共 CA 证书和异地备份平台，但给出了边界、配置与应急接入方式。

## 11. 文档索引

- [需求与验收追踪](docs/01-需求与验收追踪.md)
- [整体架构说明](docs/02-整体架构说明.md)
- [各类详细说明](docs/03-类详细说明.md)
- [安全测试与应急说明](docs/04-安全测试与应急.md)
- [系统模型图](docs/05-模型图.md)
- [REST 与 RMI API](docs/06-API接口说明.md)
