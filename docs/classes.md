# 各类详细功能说明

由 `node scripts/generate-docs.mjs` 调用 JDK 编译器语法树生成成员清单。此处列出的均为 `src/main/java` 实际声明类型，包含 record 与命名内部类，不把第三方模型或数据库表伪写成本项目 Java 类。构造器以 `<init>` 表示。

## edu.campus.audit.AuditApplication

源码：[audit-service/src/main/java/edu/campus/audit/AuditApplication.java](../audit-service/src/main/java/edu/campus/audit/AuditApplication.java)。类型：CLASS。

独立审计进程启动入口，默认 9443；不依赖业务数据库。

方法及构造器：

- `main(String[] args) : void`

## edu.campus.audit.AuditController

源码：[audit-service/src/main/java/edu/campus/audit/AuditController.java](../audit-service/src/main/java/edu/campus/audit/AuditController.java)。类型：CLASS。

签名审计 Webservice：追加、健康校验、读取已验证账本和主动 LSTM 分类。

字段：

- `ledger : LedgerService`

方法及构造器：

- `<init>(LedgerService ledger) : constructor`
- `append(Protocol.AuditEvent event) : Map<String, Object>`
- `check() : Map<String, Object>`
- `ledger() : Map<String, Object>`
- `classify() : Map<String, Object>`

## edu.campus.audit.LedgerService

源码：[audit-service/src/main/java/edu/campus/audit/LedgerService.java](../audit-service/src/main/java/edu/campus/audit/LedgerService.java)。类型：CLASS。

独立文件账本与 EVM 双重验证。AES-GCM 保护快照，哈希/HMAC 绑定顺序；完整校验后读写，事件 ID 幂等；文件落盘 fsync；输出原始证据与日志分类。

字段：

- `file : Path`
- `chain : RpcClient`

方法及构造器：

- `<init>() : constructor`
- `verify() : List<Block>`
- `decode(Block b) : Protocol.AuditEvent`
- `append(Protocol.AuditEvent event) : Map<String, Object>`
- `read() : Map<String, Object>`
- `classify() : Map<String, Object>`

## edu.campus.audit.LedgerService.Block

源码：[audit-service/src/main/java/edu/campus/audit/LedgerService.java](../audit-service/src/main/java/edu/campus/audit/LedgerService.java)。类型：RECORD。

独立文件账本与 EVM 双重验证。AES-GCM 保护快照，哈希/HMAC 绑定顺序；完整校验后读写，事件 ID 幂等；文件落盘 fsync；输出原始证据与日志分类。

此命名内部类型属于上面的组件职责；字段即值传递载荷或内部状态，成员清单以编译器解析结果为准。

字段：

- `index : long`
- `previous : String`
- `ciphertext : String`
- `hash : String`
- `signature : String`
- `transaction : String`

## edu.campus.business.AdminService

源码：[business-service/src/main/java/edu/campus/business/AdminService.java](../business-service/src/main/java/edu/campus/business/AdminService.java)。类型：CLASS。

人员组织与角色权限管理、账号停用/重置、审计复核和数据库完整性核对。独立账本快照提供数据库之外的原始成绩；禁止管理员获得个人预测结果。

字段：

- `repo : RemoteRepository`
- `DEMO_USERNAMES : Set<String>`

方法及构造器：

- `<init>(RemoteRepository repo) : constructor`
- `users(Models.User u) : List<Map<String, Object>>`
- `saveUser(Models.User u, Map<String, Object> b) : void`
- `integrity(Models.User u) : Map<String, Object>`
- `review(Models.User u, Map<String, Object> b) : void`
- `demoPassword(String username) : Optional<String>`

## edu.campus.business.AnalyticsService

源码：[business-service/src/main/java/edu/campus/business/AnalyticsService.java](../business-service/src/main/java/edu/campus/business/AnalyticsService.java)。类型：CLASS。

成绩统计、教学分析、异常检测和学业预测。使用 Weka 成熟模型，隔离训练历史与当前数据；三年门槛、留后验证和隐私过滤；预测不落库。

字段：

- `repo : RemoteRepository`
- `courses : CourseService`

方法及构造器：

- `<init>(RemoteRepository repo, CourseService courses) : constructor`
- `anomalies(String id) : List<Map<String, Object>>`
- `statistics(Models.User u, String id) : Map<String, Object>`
- `analysis(Models.User u, Map<String, Object> b) : void`
- `predict(Models.User u, String id) : Map<String, Object>`
- `clamp(double x) : double`
- `instances(List<double[]> samples) : Instances`
- `instance(Instances data, double regular, double lab) : Instance`

## edu.campus.business.ApiController

源码：[business-service/src/main/java/edu/campus/business/ApiController.java](../business-service/src/main/java/edu/campus/business/ApiController.java)。类型：CLASS。

网关内部业务信封路由。登录之外全部验证共享会话，POST 校验 CSRF，转入领域服务；拒绝访问形成独立审计事件；页面查询统一分页。

字段：

- `auth : AuthService`
- `courses : CourseService`
- `grades : GradeService`
- `analytics : AnalyticsService`
- `admin : AdminService`
- `repo : RemoteRepository`

方法及构造器：

- `<init>(AuthService auth, CourseService courses, GradeService grades, AnalyticsService analytics, AdminService admin, RemoteRepository repo) : constructor`
- `dispatch(Map<String, String> envelope, HttpServletRequest request, HttpServletResponse response) : Object`
- `page(List<Map<String, Object>> all, Map<String, String> query) : Map<String, Object>`

## edu.campus.business.AuthService

源码：[business-service/src/main/java/edu/campus/business/AuthService.java](../business-service/src/main/java/edu/campus/business/AuthService.java)。类型：CLASS。

BCrypt 密码认证、失败锁定、随机会话、CSRF、Cookie、停用校验和密码修改。会话存储在数据服务，可被多个业务副本共享；密码或权限更新撤销旧会话。

字段：

- `PASSWORDS : BCryptPasswordEncoder`
- `repo : RemoteRepository`

方法及构造器：

- `<init>(RemoteRepository repo) : constructor`
- `login(Map<String, Object> body, HttpServletRequest request, HttpServletResponse response) : Map<String, Object>`
- `authenticate(HttpServletRequest request, boolean mutation) : Models.User`
- `logout(HttpServletRequest request, HttpServletResponse response, Models.User u) : void`
- `changePassword(Models.User u, Map<String, Object> body) : void`
- `validatePassword(String p) : void`
- `demoValidatePassword(String p) : void`
- `cookie(HttpServletRequest r, String name) : String`
- `cookie(HttpServletResponse r, String name, String value, boolean httpOnly, long age) : void`

## edu.campus.business.BusinessApplication

源码：[business-service/src/main/java/edu/campus/business/BusinessApplication.java](../business-service/src/main/java/edu/campus/business/BusinessApplication.java)。类型：CLASS。

业务服务启动入口，默认端口 9441；无 JDBC 配置和数据库驱动依赖。

方法及构造器：

- `main(String[] args) : void`

## edu.campus.business.CourseService

源码：[business-service/src/main/java/edu/campus/business/CourseService.java](../business-service/src/main/java/edu/campus/business/CourseService.java)。类型：CLASS。

课程归属授权、学期/授课人/学分管理、六项权重及选课维护。权重改变拒绝破坏补考资格，课程版本与成绩操作协调。

字段：

- `repo : RemoteRepository`

方法及构造器：

- `<init>(RemoteRepository repo) : constructor`
- `access(Models.User user, String id, String permission) : Map<String, Object>`
- `list(Models.User u) : List<Map<String, Object>>`
- `roster(Models.User u, String id) : List<Map<String, Object>>`
- `weights(Models.User u, Map<String, Object> body) : void`
- `saveCourse(Models.User u, Map<String, Object> b) : void`
- `enroll(Models.User u, Map<String, Object> b) : void`
- `defaultWeights() : Map<String, Object>`

## edu.campus.business.DemoSeeder

源码：[business-service/src/main/java/edu/campus/business/DemoSeeder.java](../business-service/src/main/java/edu/campus/business/DemoSeeder.java)。类型：CLASS。

可关闭的合成演示数据初始化。固定随机种子生成三年同课程历史和当前未完成成绩；账号密码来自运行配置，初始化一次后不覆盖已有数据。

字段：

- `repo : RemoteRepository`
- `done : boolean`

方法及构造器：

- `<init>(RemoteRepository repo) : constructor`
- `seed() : void`
- `addUser(List<Protocol.Operation> ops, String id, String username, String name, String role, String password) : void`

## edu.campus.business.GradeService

源码：[business-service/src/main/java/edu/campus/business/GradeService.java](../business-service/src/main/java/edu/campus/business/GradeService.java)。类型：CLASS。

成绩写入与状态机。只允许授课教师录入，校验选课、重复学生、范围、完整性、补考资格；课程/成绩双版本保护；学生成绩单仅本人已提交数据。

字段：

- `repo : RemoteRepository`
- `courses : CourseService`
- `analytics : AnalyticsService`

方法及构造器：

- `<init>(RemoteRepository repo, CourseService courses, AnalyticsService analytics) : constructor`
- `list(Models.User u, String courseId) : List<Map<String, Object>>`
- `save(Models.User u, Map<String, Object> b) : Map<String, Object>`
- `validateScores(Map<String, Object> s, Map<String, Object> weights, boolean complete) : void`
- `transition(Models.User u, Map<String, Object> b) : void`
- `transcript(Models.User u) : List<Map<String, Object>>`

## edu.campus.business.Models

源码：[business-service/src/main/java/edu/campus/business/Models.java](../business-service/src/main/java/edu/campus/business/Models.java)。类型：CLASS。

领域基础规则与转换。限定角色权限集合、有限数值、长度验证、成绩解析、加权总评和封顶补考有效分；User.require 进行功能权限断言。

字段：

- `COMPONENTS : List<String>`
- `PERMISSIONS : Map<String, Set<String>>`

方法及构造器：

- `<init>() : constructor`
- `integer(Object x) : int`
- `number(Object x) : double`
- `object(Object value) : Map<String, Object>`
- `text(Map<String, Object> m, String key, int max) : String`
- `publicUser(Map<String, Object> user) : Map<String, Object>`
- `grade(Map<String, Object> row) : Map<String, Object>`
- `total(Map<String, Object> scores, Map<String, Object> weights) : Double`
- `effective(Map<String, Object> scores, Map<String, Object> weights) : Double`

## edu.campus.business.Models.User

源码：[business-service/src/main/java/edu/campus/business/Models.java](../business-service/src/main/java/edu/campus/business/Models.java)。类型：RECORD。

领域基础规则与转换。限定角色权限集合、有限数值、长度验证、成绩解析、加权总评和封顶补考有效分；User.require 进行功能权限断言。

此命名内部类型属于上面的组件职责；字段即值传递载荷或内部状态，成员清单以编译器解析结果为准。

字段：

- `id : String`
- `username : String`
- `name : String`
- `role : String`
- `permissions : Set<String>`
- `version : int`

方法及构造器：

- `require(String permission) : void`

## edu.campus.business.RemoteRepository

源码：[business-service/src/main/java/edu/campus/business/RemoteRepository.java](../business-service/src/main/java/edu/campus/business/RemoteRepository.java)。类型：CLASS。

领域服务访问数据层的唯一客户端。维护字段顺序、循环分页、记录存在检查和操作构造；调用独立审计服务。

字段：

- `rpc : RpcClient`
- `FIELDS : Map<String, List<String>>`

方法及构造器：

- `find(String table, Map<String, Object> where) : List<Map<String, Object>>`
- `one(String table, String id) : Map<String, Object>`
- `mutate(List<Protocol.Operation> ops, String actor, String action, String resource) : void`
- `insert(String table, Map<String, Object> data) : Protocol.Operation`
- `update(String table, String id, Map<String, Object> values, Object version) : Protocol.Operation`
- `delete(String table, String id, Object version) : Protocol.Operation`
- `status() : Map<String, Object>`
- `ledger() : Map<String, Object>`
- `logModel() : Map<String, Object>`
- `securityEvent(String actor, String action, String resource) : void`

## edu.campus.common.ApiException

源码：[common/src/main/java/edu/campus/common/ApiException.java](../common/src/main/java/edu/campus/common/ApiException.java)。类型：CLASS。

可预期的业务异常，携带 HTTP 状态、稳定错误码和可展示说明。require 将业务前置条件变成失败响应。

字段：

- `status : int`
- `code : String`

方法及构造器：

- `<init>(int status, String code, String message) : constructor`
- `require(boolean ok, int status, String message) : void`

## edu.campus.common.Crypto

源码：[common/src/main/java/edu/campus/common/Crypto.java](../common/src/main/java/edu/campus/common/Crypto.java)。类型：CLASS。

加密与完整性原语。AES-GCM 使用随机 96 位 nonce；AAD 绑定成绩身份、状态和版本；HMAC-SHA256 认证内部请求和账本；解密失败统一返回完整性错误。

方法及构造器：

- `<init>() : constructor`
- `random() : String`
- `hash(String text) : String`
- `hmac(String key, String text) : String`
- `equal(String a, String b) : boolean`
- `encrypt(String key, String aad, String plain) : String`
- `decrypt(String key, String aad, String cipher) : String`

## edu.campus.common.ErrorAdvice

源码：[common/src/main/java/edu/campus/common/ErrorAdvice.java](../common/src/main/java/edu/campus/common/ErrorAdvice.java)。类型：CLASS。

跨控制器异常映射。业务错误保留状态；格式错误返回 400；内部错误仅暴露追踪编号，避免堆栈与 SQL 泄漏。

方法及构造器：

- `missing(Exception e) : ResponseEntity<?>`
- `api(ApiException e) : ResponseEntity<?>`
- `invalid(Exception e) : ResponseEntity<?>`
- `other(Exception e) : ResponseEntity<?>`

## edu.campus.common.InternalSecurity

源码：[common/src/main/java/edu/campus/common/InternalSecurity.java](../common/src/main/java/edu/campus/common/InternalSecurity.java)。类型：CLASS。

所有 /internal 路径的服务间鉴权过滤器。限定调用方角色、30 秒时间窗、一次性 nonce、请求体大小与 HMAC；缓存请求体后继续 MVC 解析。

字段：

- `nonces : Map<String, Long>`

方法及构造器：

- `shouldNotFilter(HttpServletRequest r) : boolean`
- `doFilterInternal(HttpServletRequest r, HttpServletResponse s, FilterChain chain) : void`

## edu.campus.common.Protocol

源码：[common/src/main/java/edu/campus/common/Protocol.java](../common/src/main/java/edu/campus/common/Protocol.java)。类型：CLASS。

远程值传递契约容器。查询与操纵分开定义，字段顺序显式传递，不允许浏览器携带 SQL。

方法及构造器：

- `<init>() : constructor`

## edu.campus.common.Protocol.Selection

源码：[common/src/main/java/edu/campus/common/Protocol.java](../common/src/main/java/edu/campus/common/Protocol.java)。类型：RECORD。

远程值传递契约容器。查询与操纵分开定义，字段顺序显式传递，不允许浏览器携带 SQL。

此命名内部类型属于上面的组件职责；字段即值传递载荷或内部状态，成员清单以编译器解析结果为准。

字段：

- `table : String`
- `fields : List<String>`
- `where : Map<String, Object>`
- `orderBy : String`
- `offset : int`
- `limit : int`

## edu.campus.common.Protocol.Operation

源码：[common/src/main/java/edu/campus/common/Protocol.java](../common/src/main/java/edu/campus/common/Protocol.java)。类型：RECORD。

远程值传递契约容器。查询与操纵分开定义，字段顺序显式传递，不允许浏览器携带 SQL。

此命名内部类型属于上面的组件职责；字段即值传递载荷或内部状态，成员清单以编译器解析结果为准。

字段：

- `type : String`
- `table : String`
- `values : Map<String, Object>`
- `where : Map<String, Object>`
- `expectedCount : Integer`

## edu.campus.common.Protocol.Mutation

源码：[common/src/main/java/edu/campus/common/Protocol.java](../common/src/main/java/edu/campus/common/Protocol.java)。类型：RECORD。

远程值传递契约容器。查询与操纵分开定义，字段顺序显式传递，不允许浏览器携带 SQL。

此命名内部类型属于上面的组件职责；字段即值传递载荷或内部状态，成员清单以编译器解析结果为准。

字段：

- `operations : List<Operation>`
- `actor : String`
- `action : String`
- `resource : String`
- `requestId : String`

## edu.campus.common.Protocol.Registration

源码：[common/src/main/java/edu/campus/common/Protocol.java](../common/src/main/java/edu/campus/common/Protocol.java)。类型：RECORD。

远程值传递契约容器。查询与操纵分开定义，字段顺序显式传递，不允许浏览器携带 SQL。

此命名内部类型属于上面的组件职责；字段即值传递载荷或内部状态，成员清单以编译器解析结果为准。

字段：

- `service : String`
- `instance : String`
- `url : String`

## edu.campus.common.Protocol.AuditEvent

源码：[common/src/main/java/edu/campus/common/Protocol.java](../common/src/main/java/edu/campus/common/Protocol.java)。类型：RECORD。

远程值传递契约容器。查询与操纵分开定义，字段顺序显式传递，不允许浏览器携带 SQL。

此命名内部类型属于上面的组件职责；字段即值传递载荷或内部状态，成员清单以编译器解析结果为准。

字段：

- `id : String`
- `actor : String`
- `action : String`
- `resource : String`
- `time : String`
- `changes : List<Map<String, Object>>`

## edu.campus.common.Protocol.SelectInterface

源码：[common/src/main/java/edu/campus/common/Protocol.java](../common/src/main/java/edu/campus/common/Protocol.java)。类型：INTERFACE。

远程值传递契约容器。查询与操纵分开定义，字段顺序显式传递，不允许浏览器携带 SQL。

此命名内部类型属于上面的组件职责；字段即值传递载荷或内部状态，成员清单以编译器解析结果为准。

方法及构造器：

- `select(Selection selection) : String[][]`

## edu.campus.common.Protocol.ManipulationInterface

源码：[common/src/main/java/edu/campus/common/Protocol.java](../common/src/main/java/edu/campus/common/Protocol.java)。类型：INTERFACE。

远程值传递契约容器。查询与操纵分开定义，字段顺序显式传递，不允许浏览器携带 SQL。

此命名内部类型属于上面的组件职责；字段即值传递载荷或内部状态，成员清单以编译器解析结果为准。

方法及构造器：

- `manipulate(Mutation mutation) : boolean`

## edu.campus.common.RpcClient

源码：[common/src/main/java/edu/campus/common/RpcClient.java](../common/src/main/java/edu/campus/common/RpcClient.java)。类型：CLASS。

Webservice 远程调用客户端。HTTPS 使用可信证书校验，连接/请求超时有上限；服务发现后签名调用；远端业务错误保持 HTTP 语义。

字段：

- `caller : String`
- `client : HttpClient`

方法及构造器：

- `<init>(String caller) : constructor`
- `raw(String url, String method, String body, Map<String, String> extra) : HttpResponse<String>`
- `postUrl(String url, Object data, Class<T> type) : T`
- `discover(String service) : String`
- `post(String service, String path, Object data, Class<T> type) : T`

## edu.campus.common.ServiceHeartbeat

源码：[common/src/main/java/edu/campus/common/ServiceHeartbeat.java](../common/src/main/java/edu/campus/common/ServiceHeartbeat.java)。类型：CLASS。

每五秒向网关续租，进程实例 UUID 区分副本；注册失败后下个周期重试，不阻止已有进程继续启动。

字段：

- `port : int`
- `id : String`

方法及构造器：

- `beat() : void`

## edu.campus.common.Settings

源码：[common/src/main/java/edu/campus/common/Settings.java](../common/src/main/java/edu/campus/common/Settings.java)。类型：CLASS。

运行配置读取器。环境变量优先，缺少配置时拒绝启动；初始化工具生成随机密钥和密码。server 配置 TLS 和回环地址，json 统一序列化。

字段：

- `JSON : ObjectMapper`

方法及构造器：

- `<init>() : constructor`
- `get(String key) : String`
- `root() : Path`
- `json(Object value) : String`

## edu.campus.data.DataApplication

源码：[data-service/src/main/java/edu/campus/data/DataApplication.java](../data-service/src/main/java/edu/campus/data/DataApplication.java)。类型：CLASS。

独立数据访问进程入口。默认启动加密 H2 文件库，根据 DB_URL/DB_USER/DB_PASSWORD 切换外部数据库。

方法及构造器：

- `main(String[] args) : void`

## edu.campus.data.DataRpcController

源码：[data-service/src/main/java/edu/campus/data/DataRpcController.java](../data-service/src/main/java/edu/campus/data/DataRpcController.java)。类型：CLASS。

实现 SelectInterface 和 ManipulationInterface，分别暴露二维字符串查询和布尔事务操纵接口；内部签名过滤器先行鉴权。

字段：

- `service : TransactionService`

方法及构造器：

- `<init>(TransactionService service) : constructor`
- `select(Protocol.Selection s) : String[][]`
- `manipulate(Protocol.Mutation m) : boolean`
- `status() : Map<String, Object>`

## edu.campus.data.SchemaCatalog

源码：[data-service/src/main/java/edu/campus/data/SchemaCatalog.java](../data-service/src/main/java/edu/campus/data/SchemaCatalog.java)。类型：CLASS。

数据库结构、列名和类型白名单。主键、唯一索引、厂商大文本类型初始化；外部生产数据库建议通过迁移管理工具预建结构。

字段：

- `tables : Map<String, LinkedHashMap<String, String>>`
- `jdbc : JdbcTemplate`

方法及构造器：

- `<init>(JdbcTemplate jdbc) : constructor`
- `add(String table, String fields) : void`
- `columns(String table) : LinkedHashMap<String, String>`
- `init() : void`
- `index(String name, String target) : void`

## edu.campus.data.SqlCompiler

源码：[data-service/src/main/java/edu/campus/data/SqlCompiler.java](../data-service/src/main/java/edu/campus/data/SqlCompiler.java)。类型：CLASS。

将 Selection/Operation 编译为 SQL 与绑定参数列表。仅标识符进入 SQL 文本，按元数据把数字转换成整数/Decimal；更新删除必须有主键。

字段：

- `catalog : SchemaCatalog`

方法及构造器：

- `<init>(SchemaCatalog catalog) : constructor`
- `value(String table, String field, Object value) : Object`
- `where(String table, Map<String, Object> where, List<Object> args) : String`
- `select(Protocol.Selection s) : Statement`
- `mutate(Protocol.Operation op) : Statement`

## edu.campus.data.SqlCompiler.Statement

源码：[data-service/src/main/java/edu/campus/data/SqlCompiler.java](../data-service/src/main/java/edu/campus/data/SqlCompiler.java)。类型：RECORD。

将 Selection/Operation 编译为 SQL 与绑定参数列表。仅标识符进入 SQL 文本，按元数据把数字转换成整数/Decimal；更新删除必须有主键。

此命名内部类型属于上面的组件职责；字段即值传递载荷或内部状态，成员清单以编译器解析结果为准。

字段：

- `sql : String`
- `parameters : List<Object>`

## edu.campus.data.TransactionService

源码：[data-service/src/main/java/edu/campus/data/TransactionService.java](../data-service/src/main/java/edu/campus/data/TransactionService.java)。类型：CLASS。

数据事务协调器。查询成绩时验证 AAD；批量维护时检查 expectedCount，写前/写后快照与数据库发件箱同事务持久化；flush 网络同步且按事件 ID 重试。

字段：

- `jdbc : JdbcTemplate`
- `compiler : SqlCompiler`
- `catalog : SchemaCatalog`
- `tx : TransactionTemplate`
- `audit : RpcClient`

方法及构造器：

- `<init>(JdbcTemplate jdbc, SqlCompiler compiler, SchemaCatalog catalog, PlatformTransactionManager manager) : constructor`
- `select(Protocol.Selection s) : String[][]`
- `row(String table, Object id) : Map<String, Object>`
- `manipulate(Protocol.Mutation m) : boolean`
- `flush() : void`
- `status() : Map<String, Object>`

## edu.campus.gateway.GatewayApplication

源码：[gateway/src/main/java/edu/campus/gateway/GatewayApplication.java](../gateway/src/main/java/edu/campus/gateway/GatewayApplication.java)。类型：CLASS。

网关启动入口，设置服务身份、默认端口 8443 和调度器。

方法及构造器：

- `main(String[] args) : void`

## edu.campus.gateway.GatewayController

源码：[gateway/src/main/java/edu/campus/gateway/GatewayController.java](../gateway/src/main/java/edu/campus/gateway/GatewayController.java)。类型：CLASS。

统一收集浏览器请求信封，校验来源/方法/长度，仅转发 Cookie 与 CSRF 等必要信息；内部签名由网关重新生成。

字段：

- `registry : RegistryController`
- `rpc : RpcClient`

方法及构造器：

- `<init>(RegistryController registry) : constructor`
- `proxy(HttpServletRequest request, HttpServletResponse response) : void`

## edu.campus.gateway.RegistryController

源码：[gateway/src/main/java/edu/campus/gateway/RegistryController.java](../gateway/src/main/java/edu/campus/gateway/RegistryController.java)。类型：CLASS。

服务注册与发现。URL 白名单防 SSRF，20 秒租约清除过期实例，轮询选择；health 只公开服务名和实例编号。

字段：

- `entries : Map<String, Entry>`
- `cursor : AtomicInteger`

方法及构造器：

- `register(Protocol.Registration r, String caller) : boolean`
- `discover(Map<String, String> r) : Map<String, String>`
- `choose(String name) : String`
- `health() : Map<String, Object>`

## edu.campus.gateway.RegistryController.Entry

源码：[gateway/src/main/java/edu/campus/gateway/RegistryController.java](../gateway/src/main/java/edu/campus/gateway/RegistryController.java)。类型：RECORD。

服务注册与发现。URL 白名单防 SSRF，20 秒租约清除过期实例，轮询选择；health 只公开服务名和实例编号。

此命名内部类型属于上面的组件职责；字段即值传递载荷或内部状态，成员清单以编译器解析结果为准。

字段：

- `registration : Protocol.Registration`
- `seen : long`

## edu.campus.gateway.WebConfiguration

源码：[gateway/src/main/java/edu/campus/gateway/WebConfiguration.java](../gateway/src/main/java/edu/campus/gateway/WebConfiguration.java)。类型：CLASS。

将构建后的前端发布到 HTTPS 网关，并在嵌套过滤器中统一输出 CSP、HSTS、防嗅探、禁止嵌入与 no-store 头。

## edu.campus.gateway.WebConfiguration.Headers

源码：[gateway/src/main/java/edu/campus/gateway/WebConfiguration.java](../gateway/src/main/java/edu/campus/gateway/WebConfiguration.java)。类型：CLASS。

将构建后的前端发布到 HTTPS 网关，并在嵌套过滤器中统一输出 CSP、HSTS、防嗅探、禁止嵌入与 no-store 头。

此命名内部类型属于上面的组件职责；字段即值传递载荷或内部状态，成员清单以编译器解析结果为准。

方法及构造器：

- `doFilterInternal(HttpServletRequest r, HttpServletResponse s, FilterChain chain) : void`

## 前端与工具职责

`App.vue` 维护角色可见视图、课程状态、成绩表草稿、模态框及交互，所有服务器结果通过 Vue 文本绑定渲染。`api.js` 是统一同源请求客户端，读取 CSRF Cookie 并映射失败。`server.mjs` 维护独立 EVM、串行锚定请求和 LSTM 模型。`setup.mjs` 生成证书/随机配置，`start.mjs` 监督服务生命周期，`assets.mjs` 部署 OCR 离线资源。测试脚本不属于业务运行入口。
