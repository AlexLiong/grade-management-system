# 各类详细功能说明

由 `node scripts/generate-docs.mjs` 调用 JDK 编译器语法树生成成员清单。此处列出的均为 `src/main/java` 实际声明类型，包含 record 与命名内部类，不把第三方模型或数据库表伪写成本项目 Java 类。构造器以 `<init>` 表示。

## edu.campus.audit.AuditApplication

源码：[audit-service/src/main/java/edu/campus/audit/AuditApplication.java](../audit-service/src/main/java/edu/campus/audit/AuditApplication.java)。类型：CLASS。

独立审计进程启动入口，默认 9443；不依赖业务数据库。

方法及构造器：

- `main(String[]) : void`

## edu.campus.audit.AuditController

源码：[audit-service/src/main/java/edu/campus/audit/AuditController.java](../audit-service/src/main/java/edu/campus/audit/AuditController.java)。类型：CLASS。

签名审计 Webservice：追加、健康校验、读取已验证账本和主动 LSTM 分类。

字段：

- `ledger : edu.campus.audit.LedgerService`

方法及构造器：

- `bootstrap(java.util.List<edu.campus.common.Protocol.AuditEvent>) : void`
- `bootstrapWithAnchors(java.util.List<edu.campus.common.Protocol.AuditEvent>) : java.util.Map<java.lang.String,java.lang.Object>`
- `reset() : java.util.Map<java.lang.String,java.lang.Object>`
- `append(edu.campus.common.Protocol.AuditEvent) : java.util.Map<java.lang.String,java.lang.Object>`
- `check() : java.util.Map<java.lang.String,java.lang.Object>`
- `ledger() : java.util.Map<java.lang.String,java.lang.Object>`
- `classify() : java.util.Map<java.lang.String,java.lang.Object>`

## edu.campus.audit.LedgerService

源码：[audit-service/src/main/java/edu/campus/audit/LedgerService.java](../audit-service/src/main/java/edu/campus/audit/LedgerService.java)。类型：CLASS。

独立文件账本与 EVM 双重验证。AES-GCM 保护快照，哈希/HMAC 绑定顺序；完整校验后读写，事件 ID 幂等；文件落盘 fsync；输出原始证据与日志分类。

字段：

- `KEY : String`
- `file : java.nio.file.Path`
- `chain : edu.campus.common.RpcClient`
- `localVerified : edu.campus.audit.LedgerService.Snapshot`
- `fullVerified : edu.campus.audit.LedgerService.Snapshot`
- `SNAPSHOT_SECRETS : java.util.List<java.lang.String>`

方法及构造器：

- `fileSize() : long`
- `fileModified() : long`
- `invalidate() : void`
- `verified(boolean) : java.util.List<edu.campus.audit.LedgerService.Block>`
- `requireNotTruncated(int) : void`
- `anchoredBlocks() : Integer`
- `verifyLocal() : java.util.List<edu.campus.audit.LedgerService.Block>`
- `verify() : java.util.List<edu.campus.audit.LedgerService.Block>`
- `decode(edu.campus.audit.LedgerService.Block) : edu.campus.common.Protocol.AuditEvent`
- `reveal(edu.campus.common.Protocol.AuditEvent) : edu.campus.common.Protocol.AuditEvent`
- `revealRow(Object) : java.util.Map<java.lang.String,java.lang.Object>`
- `append(edu.campus.common.Protocol.AuditEvent) : java.util.Map<java.lang.String,java.lang.Object>`
- `bootstrapWithAnchors(java.util.List<edu.campus.common.Protocol.AuditEvent>) : void`
- `bootstrap(java.util.List<edu.campus.common.Protocol.AuditEvent>) : void`
- `reset() : java.util.Map<java.lang.String,java.lang.Object>`
- `read() : java.util.Map<java.lang.String,java.lang.Object>`
- `classify() : java.util.Map<java.lang.String,java.lang.Object>`

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

方法及构造器：

- `toString() : String`
- `hashCode() : int`
- `equals(Object) : boolean`
- `index() : long`
- `previous() : String`
- `ciphertext() : String`
- `hash() : String`
- `signature() : String`
- `transaction() : String`

## edu.campus.audit.LedgerService.Snapshot

源码：[audit-service/src/main/java/edu/campus/audit/LedgerService.java](../audit-service/src/main/java/edu/campus/audit/LedgerService.java)。类型：CLASS。

独立文件账本与 EVM 双重验证。AES-GCM 保护快照，哈希/HMAC 绑定顺序；完整校验后读写，事件 ID 幂等；文件落盘 fsync；输出原始证据与日志分类。

此命名内部类型属于上面的组件职责；字段即值传递载荷或内部状态，成员清单以编译器解析结果为准。

字段：

- `blocks : java.util.List<edu.campus.audit.LedgerService.Block>`
- `size : long`
- `modified : long`

方法及构造器：

- `matches(java.nio.file.Path) : boolean`

## edu.campus.business.AdminService

源码：[business-service/src/main/java/edu/campus/business/AdminService.java](../business-service/src/main/java/edu/campus/business/AdminService.java)。类型：CLASS。

人员组织与角色权限管理、账号停用/重置、审计复核和数据库完整性核对。独立账本快照提供数据库之外的原始成绩；禁止管理员获得个人预测结果。

字段：

- `repo : edu.campus.business.RemoteRepository`
- `organizations : edu.campus.business.OrganizationService`
- `DEMO_USERNAMES : java.util.Set<java.lang.String>`

方法及构造器：

- `users(edu.campus.business.Models.User) : java.util.List<java.util.Map<java.lang.String,java.lang.Object>>`
- `teachers(edu.campus.business.Models.User) : java.util.List<java.util.Map<java.lang.String,java.lang.Object>>`
- `department(Object,String,String,String) : String`
- `join(String,String) : String`
- `ids(java.util.List<java.util.Map<java.lang.String,java.lang.Object>>,String) : java.util.List<java.lang.String>`
- `text(Object) : String`
- `first(java.util.Map<java.lang.String,java.lang.Object>,String[]) : Object`
- `saveUser(edu.campus.business.Models.User,java.util.Map<java.lang.String,java.lang.Object>) : void`
- `integrity(edu.campus.business.Models.User) : java.util.Map<java.lang.String,java.lang.Object>`
- `currentGrades() : java.util.Map<java.lang.String,java.util.Map<java.lang.String,java.lang.Object>>`
- `verifyRowByRow(java.util.Map<java.lang.String,java.util.Map<java.lang.String,java.lang.Object>>) : java.util.List<java.util.Map<java.lang.String,java.lang.Object>>`
- `review(edu.campus.business.Models.User,java.util.Map<java.lang.String,java.lang.Object>) : void`
- `demoPassword(String) : java.util.Optional<java.lang.String>`

## edu.campus.business.AnalyticsService

源码：[business-service/src/main/java/edu/campus/business/AnalyticsService.java](../business-service/src/main/java/edu/campus/business/AnalyticsService.java)。类型：CLASS。

成绩统计、教学分析、异常检测和学业预测。使用 Weka 成熟模型，隔离训练历史与当前数据；三年门槛、留后验证和隐私过滤；预测不落库。

字段：

- `repo : edu.campus.business.RemoteRepository`
- `courses : edu.campus.business.CourseService`

方法及构造器：

- `anomalies(String) : java.util.List<java.util.Map<java.lang.String,java.lang.Object>>`
- `statistics(edu.campus.business.Models.User,String) : java.util.Map<java.lang.String,java.lang.Object>`
- `analysis(edu.campus.business.Models.User,java.util.Map<java.lang.String,java.lang.Object>) : void`
- `predict(edu.campus.business.Models.User,String) : java.util.Map<java.lang.String,java.lang.Object>`
- `clamp(double) : double`
- `instances(java.util.List<double[]>) : Instances`
- `instance(Instances,double,double) : Instance`

## edu.campus.business.ApiController

源码：[business-service/src/main/java/edu/campus/business/ApiController.java](../business-service/src/main/java/edu/campus/business/ApiController.java)。类型：CLASS。

网关内部业务信封路由。登录之外全部验证共享会话，POST 校验 CSRF，转入领域服务；拒绝访问形成独立审计事件；页面查询统一分页。

字段：

- `auth : edu.campus.business.AuthService`
- `repo : edu.campus.business.RemoteRepository`
- `routes : java.util.List<edu.campus.business.Routes>`

方法及构造器：

- `dispatch(java.util.Map<java.lang.String,java.lang.String>,HttpServletRequest,HttpServletResponse) : Object`
- `page(java.util.List<java.util.Map<java.lang.String,java.lang.Object>>,java.util.Map<java.lang.String,java.lang.String>) : java.util.Map<java.lang.String,java.lang.Object>`

## edu.campus.business.AuthService

源码：[business-service/src/main/java/edu/campus/business/AuthService.java](../business-service/src/main/java/edu/campus/business/AuthService.java)。类型：CLASS。

BCrypt 密码认证、失败锁定、随机会话、CSRF、Cookie、停用校验和密码修改。会话存储在数据服务，可被多个业务副本共享；密码或权限更新撤销旧会话。

字段：

- `PASSWORDS : BCryptPasswordEncoder`
- `repo : edu.campus.business.RemoteRepository`

方法及构造器：

- `login(java.util.Map<java.lang.String,java.lang.Object>,HttpServletRequest,HttpServletResponse) : java.util.Map<java.lang.String,java.lang.Object>`
- `authenticate(HttpServletRequest,boolean) : edu.campus.business.Models.User`
- `permissionSet(Object) : java.util.Set<java.lang.String>`
- `text(Object) : String`
- `logout(HttpServletRequest,HttpServletResponse,edu.campus.business.Models.User) : void`
- `changePassword(edu.campus.business.Models.User,java.util.Map<java.lang.String,java.lang.Object>) : void`
- `validatePassword(String) : void`
- `demoValidatePassword(String) : void`
- `cookie(HttpServletRequest,String) : String`
- `cookie(HttpServletResponse,String,String,boolean,long) : void`

## edu.campus.business.BusinessApplication

源码：[business-service/src/main/java/edu/campus/business/BusinessApplication.java](../business-service/src/main/java/edu/campus/business/BusinessApplication.java)。类型：CLASS。

业务服务启动入口，默认端口 9441；无 JDBC 配置和数据库驱动依赖。

方法及构造器：

- `main(String[]) : void`

## edu.campus.business.CoreRoutes

源码：[business-service/src/main/java/edu/campus/business/CoreRoutes.java](../business-service/src/main/java/edu/campus/business/CoreRoutes.java)。类型：CLASS。

既有成绩、课程、统计、人员与审计路径的注册实现。迁移到路由注册表后行为保持不变，供新增域作为写法参照。

字段：

- `PATHS : java.util.Set<java.lang.String>`
- `auth : edu.campus.business.AuthService`
- `courses : edu.campus.business.CourseService`
- `grades : edu.campus.business.GradeService`
- `analytics : edu.campus.business.AnalyticsService`
- `admin : edu.campus.business.AdminService`
- `organizations : edu.campus.business.OrganizationService`
- `selections : edu.campus.business.SelectionService`
- `repo : edu.campus.business.RemoteRepository`

方法及构造器：

- `handles() : java.util.Set<java.lang.String>`
- `dispatch(edu.campus.business.Routes.Request) : Object`
- `me(edu.campus.business.Models.User) : java.util.Map<java.lang.String,java.lang.Object>`
- `nameOf(edu.campus.business.Models.Level,String) : String`

## edu.campus.business.CourseService

源码：[business-service/src/main/java/edu/campus/business/CourseService.java](../business-service/src/main/java/edu/campus/business/CourseService.java)。类型：CLASS。

课程归属授权、学期/授课人/学分管理、六项权重及选课维护。权重改变拒绝破坏补考资格，课程版本与成绩操作协调。

字段：

- `repo : edu.campus.business.RemoteRepository`
- `organizations : edu.campus.business.OrganizationService`

方法及构造器：

- `access(edu.campus.business.Models.User,String,String) : java.util.Map<java.lang.String,java.lang.Object>`
- `list(edu.campus.business.Models.User) : java.util.List<java.util.Map<java.lang.String,java.lang.Object>>`
- `catalog(edu.campus.business.Models.User,String,String) : java.util.List<java.util.Map<java.lang.String,java.lang.Object>>`
- `withOfferingCollege(java.util.Map<java.lang.String,java.lang.Object>) : java.util.Map<java.lang.String,java.lang.Object>`
- `withOfferingCollege(java.util.Map<java.lang.String,java.lang.Object>,java.util.Map<java.lang.String,java.lang.String>) : java.util.Map<java.lang.String,java.lang.Object>`
- `classNames(java.util.List<java.util.Map<java.lang.String,java.lang.Object>>) : java.util.Map<java.lang.String,java.lang.String>`
- `text(Object) : String`
- `roster(edu.campus.business.Models.User,String) : java.util.List<java.util.Map<java.lang.String,java.lang.Object>>`
- `failedCodesBefore(String,String,java.util.List<java.util.Map<java.lang.String,java.lang.Object>>,java.util.List<java.util.Map<java.lang.String,java.lang.Object>>,java.util.List<java.util.Map<java.lang.String,java.lang.Object>>) : java.util.Set<java.lang.String>`
- `collegeIds(java.util.Collection<java.util.Map<java.lang.String,java.lang.Object>>) : java.util.List<java.lang.String>`
- `majorIds(java.util.Collection<java.util.Map<java.lang.String,java.lang.Object>>) : java.util.List<java.lang.String>`
- `classIds(java.util.Collection<java.util.Map<java.lang.String,java.lang.Object>>) : java.util.List<java.lang.String>`
- `weights(edu.campus.business.Models.User,java.util.Map<java.lang.String,java.lang.Object>) : void`
- `saveCourse(edu.campus.business.Models.User,java.util.Map<java.lang.String,java.lang.Object>) : void`
- `enroll(edu.campus.business.Models.User,java.util.Map<java.lang.String,java.lang.Object>) : void`
- `enrollmentRecord(java.util.Map<java.lang.String,java.lang.Object>,String,String,String,String) : edu.campus.common.Protocol.Operation`
- `put(java.util.Map<java.lang.String,java.lang.Object>,String,Object) : void`
- `defaultWeights() : java.util.Map<java.lang.String,java.lang.Object>`

## edu.campus.business.GradeService

源码：[business-service/src/main/java/edu/campus/business/GradeService.java](../business-service/src/main/java/edu/campus/business/GradeService.java)。类型：CLASS。

成绩写入与状态机。只允许授课教师录入，校验选课、重复学生、范围、完整性、补考资格；课程/成绩双版本保护；学生成绩单仅本人已提交数据。

字段：

- `repo : edu.campus.business.RemoteRepository`
- `courses : edu.campus.business.CourseService`
- `analytics : edu.campus.business.AnalyticsService`

方法及构造器：

- `list(edu.campus.business.Models.User,String) : java.util.List<java.util.Map<java.lang.String,java.lang.Object>>`
- `save(edu.campus.business.Models.User,java.util.Map<java.lang.String,java.lang.Object>) : java.util.Map<java.lang.String,java.lang.Object>`
- `validateScores(java.util.Map<java.lang.String,java.lang.Object>,java.util.Map<java.lang.String,java.lang.Object>,boolean) : void`
- `transition(edu.campus.business.Models.User,java.util.Map<java.lang.String,java.lang.Object>) : void`
- `transcript(edu.campus.business.Models.User) : java.util.List<java.util.Map<java.lang.String,java.lang.Object>>`
- `markRetakes(java.util.List<java.util.Map<java.lang.String,java.lang.Object>>) : void`
- `blank(Object) : String`

## edu.campus.business.Models

源码：[business-service/src/main/java/edu/campus/business/Models.java](../business-service/src/main/java/edu/campus/business/Models.java)。类型：CLASS。

领域基础规则与转换。限定角色权限集合、有限数值、长度验证、成绩解析、加权总评和封顶补考有效分；User.require 进行功能权限断言。

字段：

- `COMPONENTS : java.util.List<java.lang.String>`
- `PERMISSIONS : java.util.Map<java.lang.String,java.util.Set<java.lang.String>>`
- `COLLEGE_PREFIX : String`
- `MAJOR_PREFIX : String`
- `CLASS_PREFIX : String`
- `SELECTION_STATUS : java.util.Set<java.lang.String>`
- `COURSE_STATUS : java.util.Set<java.lang.String>`
- `ENROLLMENT_ACTIONS : java.util.Set<java.lang.String>`
- `ENROLLMENT_STATUS : java.util.Set<java.lang.String>`
- `MAX_CREDITS_LIMIT : int`
- `MAX_BATCH_STUDENTS : int`
- `MAX_CODE : int`

方法及构造器：

- `levelOfCode(String) : edu.campus.business.Models.Level`
- `codePattern(edu.campus.business.Models.Level) : String`
- `validCode(edu.campus.business.Models.Level,String) : boolean`
- `validCodeValue(String) : boolean`
- `formatCode(int) : String`
- `parseCode(Object) : int`
- `integer(Object) : int`
- `number(Object) : double`
- `object(Object) : java.util.Map<java.lang.String,java.lang.Object>`
- `text(java.util.Map<java.lang.String,java.lang.Object>,String,int) : String`
- `optionalText(java.util.Map<java.lang.String,java.lang.Object>,String,int) : String`
- `flag(Object,boolean) : boolean`
- `flagInt(Object,boolean) : int`
- `publicUser(java.util.Map<java.lang.String,java.lang.Object>) : java.util.Map<java.lang.String,java.lang.Object>`
- `grade(java.util.Map<java.lang.String,java.lang.Object>) : java.util.Map<java.lang.String,java.lang.Object>`
- `total(java.util.Map<java.lang.String,java.lang.Object>,java.util.Map<java.lang.String,java.lang.Object>) : Double`
- `effective(java.util.Map<java.lang.String,java.lang.Object>,java.util.Map<java.lang.String,java.lang.Object>) : Double`

## edu.campus.business.Models.Level

源码：[business-service/src/main/java/edu/campus/business/Models.java](../business-service/src/main/java/edu/campus/business/Models.java)。类型：ENUM。

领域基础规则与转换。限定角色权限集合、有限数值、长度验证、成绩解析、加权总评和封顶补考有效分；User.require 进行功能权限断言。

此命名内部类型属于上面的组件职责；字段即值传递载荷或内部状态，成员清单以编译器解析结果为准。

字段：

- `label : String`
- `table : String`
- `prefix : String`
- `depth : int`

方法及构造器：

- `values() : edu.campus.business.Models.Level[]`
- `valueOf(String) : edu.campus.business.Models.Level`
- `isRoot() : boolean`
- `parentField() : String`
- `parent() : edu.campus.business.Models.Level`

## edu.campus.business.Models.User

源码：[business-service/src/main/java/edu/campus/business/Models.java](../business-service/src/main/java/edu/campus/business/Models.java)。类型：RECORD。

领域基础规则与转换。限定角色权限集合、有限数值、长度验证、成绩解析、加权总评和封顶补考有效分；User.require 进行功能权限断言。

此命名内部类型属于上面的组件职责；字段即值传递载荷或内部状态，成员清单以编译器解析结果为准。

字段：

- `id : String`
- `username : String`
- `name : String`
- `role : String`
- `permissions : java.util.Set<java.lang.String>`
- `version : int`
- `collegeId : String`
- `majorId : String`
- `classId : String`

方法及构造器：

- `require(String) : void`
- `any(String[]) : boolean`
- `toString() : String`
- `hashCode() : int`
- `equals(Object) : boolean`
- `id() : String`
- `username() : String`
- `name() : String`
- `role() : String`
- `permissions() : java.util.Set<java.lang.String>`
- `version() : int`
- `collegeId() : String`
- `majorId() : String`
- `classId() : String`

## edu.campus.business.OrganizationService

源码：[business-service/src/main/java/edu/campus/business/OrganizationService.java](../business-service/src/main/java/edu/campus/business/OrganizationService.java)。类型：CLASS。

学院—专业—班级三级组织。库中以独立编号作主键互相引用，前端按名称操作：resolveOwn 把名称归一化成编号，names/enrichOwn 回填可读名称；负责编号生成、删除影响面与级联校验、选课范围匹配。

字段：

- `repo : edu.campus.business.RemoteRepository`

方法及构造器：

- `list(edu.campus.business.Models.User,edu.campus.business.Models.Level) : java.util.List<java.util.Map<java.lang.String,java.lang.Object>>`
- `listAll(edu.campus.business.Models.Level) : java.util.List<java.util.Map<java.lang.String,java.lang.Object>>`
- `requireOrganization(edu.campus.business.Models.Level,String) : java.util.Map<java.lang.String,java.lang.Object>`
- `resolveOwn(edu.campus.business.Models.Level,Object) : String`
- `names(edu.campus.business.Models.Level,java.util.Collection<java.lang.String>) : java.util.Map<java.lang.String,java.lang.String>`
- `namesInto(edu.campus.business.Models.Level,java.util.Map<java.lang.String,java.lang.String>) : void`
- `displayName(edu.campus.business.Models.Level,java.util.Map<java.lang.String,java.lang.Object>) : String`
- `className(String,String,String) : String`
- `enrichOwn(java.util.Map<java.lang.String,java.lang.Object>,java.util.Map<java.lang.String,edu.campus.business.Models.Level>) : java.util.Map<java.lang.String,java.lang.Object>`
- `nameField(String) : String`
- `save(edu.campus.business.Models.User,edu.campus.business.Models.Level,java.util.Map<java.lang.String,java.lang.Object>) : String`
- `checkClassConsistency(java.util.Map<java.lang.String,java.lang.Object>) : void`
- `resolveParent(edu.campus.business.Models.Level,java.util.Map<java.lang.String,java.lang.Object>) : String`
- `nextId(edu.campus.business.Models.Level,String) : String`
- `sequenceOf(String) : String`
- `itemOf(String) : int`
- `impact(edu.campus.business.Models.Level,String) : java.util.Map<java.lang.String,java.lang.Object>`
- `affiliatedCourses(edu.campus.business.Models.Level,String) : java.util.List<java.util.Map<java.lang.String,java.lang.Object>>`
- `deleteOps(edu.campus.business.Models.User,edu.campus.business.Models.Level,String,boolean) : java.util.List<edu.campus.common.Protocol.Operation>`
- `doDelete(edu.campus.business.Models.User,java.util.List<edu.campus.common.Protocol.Operation>,edu.campus.business.Models.Level,String) : void`
- `childLevel(edu.campus.business.Models.Level) : edu.campus.business.Models.Level`
- `childrenOf(edu.campus.business.Models.Level,String) : java.util.List<java.util.Map<java.lang.String,java.lang.Object>>`
- `checkPeople(edu.campus.business.Models.Level,String) : void`
- `inScope(java.util.Map<java.lang.String,java.lang.Object>,java.util.Map<java.lang.String,java.lang.Object>) : boolean`
- `matchesScope(Object,Object) : boolean`
- `split(Object) : java.util.List<java.lang.String>`

## edu.campus.business.OrganizeRoutes

源码：[business-service/src/main/java/edu/campus/business/OrganizeRoutes.java](../business-service/src/main/java/edu/campus/business/OrganizeRoutes.java)。类型：CLASS。

组织管理的 HTTP 路由。提供学院/专业/班级的分页列表、下拉选项、影响面、成员、保存、删除与批量调整人员归属，全部写操作经统一事务进入审计。

字段：

- `PATHS : java.util.Set<java.lang.String>`
- `organizations : edu.campus.business.OrganizationService`
- `repo : edu.campus.business.RemoteRepository`

方法及构造器：

- `handles() : java.util.Set<java.lang.String>`
- `dispatch(edu.campus.business.Routes.Request) : Object`
- `list(edu.campus.business.Models.User,java.util.Map<java.lang.String,java.lang.String>) : Object`
- `options() : Object`
- `impact(edu.campus.business.Models.User,java.util.Map<java.lang.String,java.lang.String>) : Object`
- `members(edu.campus.business.Models.User,java.util.Map<java.lang.String,java.lang.String>) : Object`
- `students(edu.campus.business.Models.User,java.util.Map<java.lang.String,java.lang.String>) : Object`
- `save(edu.campus.business.Models.User,java.util.Map<java.lang.String,java.lang.Object>) : Object`
- `remove(edu.campus.business.Models.User,java.util.Map<java.lang.String,java.lang.Object>) : Object`
- `assign(edu.campus.business.Models.User,java.util.Map<java.lang.String,java.lang.Object>) : Object`
- `placement(java.util.Map<java.lang.String,java.lang.Object>,String,String,String,String) : java.util.Map<java.lang.String,java.lang.Object>`
- `requireRole(String,String,String) : java.util.Map<java.lang.String,java.lang.Object>`
- `nameOf(edu.campus.business.Models.Level,String) : String`
- `studentCount(edu.campus.business.Models.Level,String) : int`
- `fieldOf(edu.campus.business.Models.Level) : String`
- `level(Object) : edu.campus.business.Models.Level`
- `first(java.util.Map<java.lang.String,?>,String[]) : Object`
- `keep(java.util.Map<java.lang.String,java.lang.Object>,java.util.Map<java.lang.String,java.lang.Object>,String,String,int) : Object`
- `years(java.util.Map<java.lang.String,java.lang.Object>,java.util.Map<java.lang.String,java.lang.Object>) : int`
- `intOf(Object,int) : int`
- `idList(Object) : java.util.List<java.lang.String>`
- `valuesOf(java.util.List<java.util.Map<java.lang.String,java.lang.Object>>,String) : java.util.List<java.lang.String>`
- `matches(java.util.Map<java.lang.String,java.lang.Object>,String) : boolean`
- `plainNames(java.util.List<java.util.Map<java.lang.String,java.lang.Object>>) : java.util.Map<java.lang.String,java.lang.String>`
- `text(Object) : String`

## edu.campus.business.RemoteRepository

源码：[business-service/src/main/java/edu/campus/business/RemoteRepository.java](../business-service/src/main/java/edu/campus/business/RemoteRepository.java)。类型：CLASS。

领域服务访问数据层的唯一客户端。维护字段顺序、循环分页、记录存在检查和操作构造；调用独立审计服务。

字段：

- `rpc : edu.campus.common.RpcClient`
- `FIELDS : java.util.Map<java.lang.String,java.util.List<java.lang.String>>`
- `TRACE : boolean`
- `PAGE : int`

方法及构造器：

- `find(String,java.util.Map<java.lang.String,java.lang.Object>) : java.util.List<java.util.Map<java.lang.String,java.lang.Object>>`
- `findPage(String,java.util.Map<java.lang.String,java.lang.Object>) : java.util.List<java.util.Map<java.lang.String,java.lang.Object>>`
- `one(String,String) : java.util.Map<java.lang.String,java.lang.Object>`
- `findOne(String,java.util.Map<java.lang.String,java.lang.Object>) : java.util.Map<java.lang.String,java.lang.Object>`
- `mutate(java.util.List<edu.campus.common.Protocol.Operation>,String,String,String) : void`
- `insert(String,java.util.Map<java.lang.String,java.lang.Object>) : edu.campus.common.Protocol.Operation`
- `update(String,String,java.util.Map<java.lang.String,java.lang.Object>,Object) : edu.campus.common.Protocol.Operation`
- `delete(String,String,Object) : edu.campus.common.Protocol.Operation`
- `status() : java.util.Map<java.lang.String,java.lang.Object>`
- `ledger() : java.util.Map<java.lang.String,java.lang.Object>`
- `logModel() : java.util.Map<java.lang.String,java.lang.Object>`
- `securityEvent(String,String,String) : void`

## edu.campus.business.Routes

源码：[business-service/src/main/java/edu/campus/business/Routes.java](../business-service/src/main/java/edu/campus/business/Routes.java)。类型：INTERFACE。

领域路由注册表。每个业务域声明自己负责的路径并实现 dispatch，ApiController 只做信封解析、会话/CSRF 校验与命中分发，新增业务域无需修改分发代码。

方法及构造器：

- `handles() : java.util.Set<java.lang.String>`
- `dispatch(edu.campus.business.Routes.Request) : Object`

## edu.campus.business.Routes.Request

源码：[business-service/src/main/java/edu/campus/business/Routes.java](../business-service/src/main/java/edu/campus/business/Routes.java)。类型：RECORD。

领域路由注册表。每个业务域声明自己负责的路径并实现 dispatch，ApiController 只做信封解析、会话/CSRF 校验与命中分发，新增业务域无需修改分发代码。

此命名内部类型属于上面的组件职责；字段即值传递载荷或内部状态，成员清单以编译器解析结果为准。

字段：

- `path : String`
- `user : edu.campus.business.Models.User`
- `write : boolean`
- `body : java.util.Map<java.lang.String,java.lang.Object>`
- `query : java.util.Map<java.lang.String,java.lang.String>`
- `servletRequest : HttpServletRequest`
- `servletResponse : HttpServletResponse`

方法及构造器：

- `toString() : String`
- `hashCode() : int`
- `equals(Object) : boolean`
- `path() : String`
- `user() : edu.campus.business.Models.User`
- `write() : boolean`
- `body() : java.util.Map<java.lang.String,java.lang.Object>`
- `query() : java.util.Map<java.lang.String,java.lang.String>`
- `servletRequest() : HttpServletRequest`
- `servletResponse() : HttpServletResponse`

## edu.campus.business.SelectionRoutes

源码：[business-service/src/main/java/edu/campus/business/SelectionRoutes.java](../business-service/src/main/java/edu/campus/business/SelectionRoutes.java)。类型：CLASS。

选课系统的 HTTP 路由与定时任务。暴露发布、关闭、取消、结算、选课、退课、批量与流水查询路径，并在选课窗口结束后自动结算不满足最低开课人数的课程。

字段：

- `PATHS : java.util.Set<java.lang.String>`
- `selections : edu.campus.business.SelectionService`

方法及构造器：

- `handles() : java.util.Set<java.lang.String>`
- `dispatch(edu.campus.business.Routes.Request) : Object`

## edu.campus.business.SelectionService

源码：[business-service/src/main/java/edu/campus/business/SelectionService.java](../business-service/src/main/java/edu/campus/business/SelectionService.java)。类型：CLASS。

教务选课系统。发布选课信息（选课名、起止时间、选课范围、最低开课人数、可选可退），按十四条顺序规则校验学生选课，处理退课、按班级批量选课与最低开课人数结算自动退回；所有写操作写选课流水并进入审计。

字段：

- `OPEN : String`
- `CLOSED : String`
- `CANCELLED : String`
- `ACTIVE : String`
- `DROPPED : String`
- `COURSE_CANCELLED : String`
- `SUBMITTED : String`
- `SYSTEM_ACTOR : String`
- `ALREADY_SELECTED : String`
- `NO_ENROLLMENT : String`
- `GRADED_PUBLISH : String`
- `GRADED_DROP : String`
- `CODE_TAKEN : String`
- `PASSED_BEFORE : String`
- `RETRY_FORBIDDEN : String`
- `ADD_FORBIDDEN : String`
- `DROP_FORBIDDEN : String`
- `OUT_OF_SCOPE : String`
- `NOT_STARTED : String`
- `ENDED : String`
- `NOT_OPEN : String`
- `ALREADY_OPEN_PUBLISH : String`
- `BAD_TIME : String`
- `RETRY_LABEL : String`
- `MAX_MIN_ENROLL : int`
- `repo : edu.campus.business.RemoteRepository`
- `organizations : edu.campus.business.OrganizationService`

方法及构造器：

- `list(edu.campus.business.Models.User,java.util.Map<java.lang.String,java.lang.String>) : java.util.Map<java.lang.String,java.lang.Object>`
- `available(edu.campus.business.Models.User) : java.util.List<java.util.Map<java.lang.String,java.lang.Object>>`
- `failedCodes(java.util.Collection<java.util.Map<java.lang.String,java.lang.Object>>,java.util.Map<java.lang.String,java.util.Map<java.lang.String,java.lang.Object>>) : java.util.Map<java.lang.String,edu.campus.business.SelectionService.FailedCodes>`
- `gradeIndex() : java.util.Map<java.lang.String,java.util.Map<java.lang.String,java.lang.Object>>`
- `my(edu.campus.business.Models.User) : java.util.List<java.util.Map<java.lang.String,java.lang.Object>>`
- `records(edu.campus.business.Models.User,java.util.Map<java.lang.String,java.lang.String>) : java.util.Map<java.lang.String,java.lang.Object>`
- `save(edu.campus.business.Models.User,java.util.Map<java.lang.String,java.lang.Object>) : java.util.Map<java.lang.String,java.lang.Object>`
- `close(edu.campus.business.Models.User,java.util.Map<java.lang.String,java.lang.Object>) : java.util.Map<java.lang.String,java.lang.Object>`
- `cancel(edu.campus.business.Models.User,java.util.Map<java.lang.String,java.lang.Object>) : java.util.Map<java.lang.String,java.lang.Object>`
- `settle(edu.campus.business.Models.User,java.util.Map<java.lang.String,java.lang.Object>) : java.util.Map<java.lang.String,java.lang.Object>`
- `settleBatch(String,String) : java.util.Map<java.lang.String,java.lang.Object>`
- `select(edu.campus.business.Models.User,java.util.Map<java.lang.String,java.lang.Object>) : java.util.Map<java.lang.String,java.lang.Object>`
- `drop(edu.campus.business.Models.User,java.util.Map<java.lang.String,java.lang.Object>) : java.util.Map<java.lang.String,java.lang.Object>`
- `batch(edu.campus.business.Models.User,java.util.Map<java.lang.String,java.lang.Object>) : java.util.Map<java.lang.String,java.lang.Object>`
- `batchByCourse(edu.campus.business.Models.User,java.util.Map<java.lang.String,java.lang.Object>) : java.util.Map<java.lang.String,java.lang.Object>`
- `batchPublish(String,String,String) : java.util.Map<java.lang.String,java.lang.Object>`
- `enrollmentRecord(String,String,String,String,String,String,String,String,String) : edu.campus.common.Protocol.Operation`
- `failure(String,String,String) : java.util.Map<java.lang.String,java.lang.Object>`
- `autoSettleExpired() : void`
- `checkSelectable(edu.campus.business.SelectionService.StudentState,java.util.Map<java.lang.String,java.lang.Object>,String,boolean) : java.util.Map<java.lang.String,java.lang.Object>`
- `codeHistory(edu.campus.business.SelectionService.StudentState,String,String,String) : edu.campus.business.SelectionService.CodeHistory`
- `effectiveScore(java.util.Map<java.lang.String,java.lang.Object>,String) : Double`
- `effectiveScore(java.util.Map<java.lang.String,java.lang.Object>,String,java.util.Map<java.lang.String,java.util.Map<java.lang.String,java.lang.Object>>) : Double`
- `checkCredits(edu.campus.business.SelectionService.StudentState,java.util.Map<java.lang.String,java.lang.Object>,java.util.Map<java.lang.String,java.lang.Object>) : void`
- `studentState(edu.campus.business.Models.User,java.util.Map<java.lang.String,java.util.Map<java.lang.String,java.lang.Object>>) : edu.campus.business.SelectionService.StudentState`
- `studentState(String,String,String,String,String,java.util.Map<java.lang.String,java.util.Map<java.lang.String,java.lang.Object>>) : edu.campus.business.SelectionService.StudentState`
- `fill(java.util.Map<java.lang.String,java.lang.Object>,String,String) : void`
- `courseIndex() : java.util.Map<java.lang.String,java.util.Map<java.lang.String,java.lang.Object>>`
- `describe(java.util.Map<java.lang.String,java.lang.Object>,java.util.List<java.util.Map<java.lang.String,java.lang.Object>>,java.util.Map<java.lang.String,java.util.Map<java.lang.String,java.lang.Object>>,edu.campus.business.SelectionService.Names) : java.util.Map<java.lang.String,java.lang.Object>`
- `scope(java.util.Map<java.lang.String,java.lang.Object>,String,String,edu.campus.business.Models.Level,java.util.Map<java.lang.String,java.lang.Object>,edu.campus.business.SelectionService.Names) : java.util.List<java.lang.String>`
- `options(edu.campus.business.SelectionService.StudentState,java.util.Map<java.lang.String,java.lang.Object>,java.util.Map<java.lang.String,java.util.Map<java.lang.String,java.lang.Object>>,edu.campus.business.SelectionService.Names,java.util.List<java.util.Map<java.lang.String,java.lang.Object>>,boolean) : java.util.List<java.util.Map<java.lang.String,java.lang.Object>>`
- `courseRow(java.util.Map<java.lang.String,java.lang.Object>,edu.campus.business.SelectionService.Names) : java.util.Map<java.lang.String,java.lang.Object>`
- `courseFields(java.util.Map<java.lang.String,java.lang.Object>,java.util.Map<java.lang.String,java.lang.Object>,edu.campus.business.SelectionService.Names) : void`
- `refund(java.util.List<edu.campus.common.Protocol.Operation>,java.util.Map<java.lang.String,java.lang.Object>,java.util.List<java.util.Map<java.lang.String,java.lang.Object>>,java.util.Map<java.lang.String,java.util.Map<java.lang.String,java.lang.Object>>,String,String) : int`
- `auditRecord(java.util.Map<java.lang.String,java.lang.Object>,String,String,String,String,String,String,String) : edu.campus.common.Protocol.Operation`
- `put(java.util.Map<java.lang.String,java.lang.Object>,String,String) : void`
- `failure(String,String) : java.util.Map<java.lang.String,java.lang.Object>`
- `requirePublish(String) : java.util.Map<java.lang.String,java.lang.Object>`
- `requireOpen(java.util.Map<java.lang.String,java.lang.Object>) : void`
- `requireWindow(java.util.Map<java.lang.String,java.lang.Object>,java.time.Instant) : void`
- `parseTime(Object) : java.time.Instant`
- `localIso(java.time.Instant) : String`
- `resolveScope(edu.campus.business.Models.Level,Object) : String`
- `findCourse(java.util.Map<java.lang.String,java.util.Map<java.lang.String,java.lang.Object>>,String) : java.util.Map<java.lang.String,java.lang.Object>`
- `strings(Object) : java.util.List<java.lang.String>`
- `requiredInt(java.util.Map<java.lang.String,java.lang.Object>,String,int,int,String) : int`
- `optionalInt(java.util.Map<java.lang.String,java.lang.Object>,String,int,int,int,String) : int`
- `firstValue(java.util.Map<java.lang.String,java.lang.Object>,String,String) : Object`
- `statusName(String) : String`
- `actionName(String) : String`
- `credits(java.util.Map<java.lang.String,java.lang.Object>) : double`
- `amount(double) : String`
- `count(java.util.List<java.util.Map<java.lang.String,java.lang.Object>>,String,String,String) : int`
- `rows(String,java.util.Map<java.lang.String,java.lang.Object>) : java.util.List<java.util.Map<java.lang.String,java.lang.Object>>`
- `first(String,java.util.Map<java.lang.String,java.lang.Object>) : java.util.Map<java.lang.String,java.lang.Object>`
- `text(Object) : String`
- `intOf(Object,int) : int`

## edu.campus.business.SelectionService.FailedCodes

源码：[business-service/src/main/java/edu/campus/business/SelectionService.java](../business-service/src/main/java/edu/campus/business/SelectionService.java)。类型：CLASS。

教务选课系统。发布选课信息（选课名、起止时间、选课范围、最低开课人数、可选可退），按十四条顺序规则校验学生选课，处理退课、按班级批量选课与最低开课人数结算自动退回；所有写操作写选课流水并进入审计。

此命名内部类型属于上面的组件职责；字段即值传递载荷或内部状态，成员清单以编译器解析结果为准。

字段：

- `firstFailedTerm : java.util.Map<java.lang.String,java.lang.String>`

方法及构造器：

- `add(String,String) : void`
- `retakeIn(String,String) : boolean`

## edu.campus.business.SelectionService.CodeHistory

源码：[business-service/src/main/java/edu/campus/business/SelectionService.java](../business-service/src/main/java/edu/campus/business/SelectionService.java)。类型：RECORD。

教务选课系统。发布选课信息（选课名、起止时间、选课范围、最低开课人数、可选可退），按十四条顺序规则校验学生选课，处理退课、按班级批量选课与最低开课人数结算自动退回；所有写操作写选课流水并进入审计。

此命名内部类型属于上面的组件职责；字段即值传递载荷或内部状态，成员清单以编译器解析结果为准。

字段：

- `passed : boolean`
- `failed : boolean`
- `sameTermOtherClass : boolean`

方法及构造器：

- `toString() : String`
- `hashCode() : int`
- `equals(Object) : boolean`
- `passed() : boolean`
- `failed() : boolean`
- `sameTermOtherClass() : boolean`

## edu.campus.business.SelectionService.StudentState

源码：[business-service/src/main/java/edu/campus/business/SelectionService.java](../business-service/src/main/java/edu/campus/business/SelectionService.java)。类型：CLASS。

教务选课系统。发布选课信息（选课名、起止时间、选课范围、最低开课人数、可选可退），按十四条顺序规则校验学生选课，处理退课、按班级批量选课与最低开课人数结算自动退回；所有写操作写选课流水并进入审计。

此命名内部类型属于上面的组件职责；字段即值传递载荷或内部状态，成员清单以编译器解析结果为准。

字段：

- `id : String`
- `role : String`
- `profile : java.util.Map<java.lang.String,java.lang.Object>`
- `enrollments : java.util.List<java.util.Map<java.lang.String,java.lang.Object>>`
- `allCourses : java.util.Map<java.lang.String,java.util.Map<java.lang.String,java.lang.Object>>`
- `byCourse : java.util.Map<java.lang.String,java.util.Map<java.lang.String,java.lang.Object>>`

方法及构造器：

- `active(String) : boolean`
- `enrollment(String) : java.util.Map<java.lang.String,java.lang.Object>`

## edu.campus.business.SelectionService.Names

源码：[business-service/src/main/java/edu/campus/business/SelectionService.java](../business-service/src/main/java/edu/campus/business/SelectionService.java)。类型：CLASS。

教务选课系统。发布选课信息（选课名、起止时间、选课范围、最低开课人数、可选可退），按十四条顺序规则校验学生选课，处理退课、按班级批量选课与最低开课人数结算自动退回；所有写操作写选课流水并进入审计。

此命名内部类型属于上面的组件职责；字段即值传递载荷或内部状态，成员清单以编译器解析结果为准。

字段：

- `teachers : java.util.Map<java.lang.String,java.lang.String>`
- `colleges : java.util.Map<java.lang.String,java.lang.String>`
- `majors : java.util.Map<java.lang.String,java.lang.String>`
- `classes : java.util.Map<java.lang.String,java.lang.String>`
- `students : java.util.Map<java.lang.String,java.lang.String>`

方法及构造器：

- `fill(java.util.Map<java.lang.String,java.lang.String>,edu.campus.business.Models.Level) : void`
- `teacher(String) : String`
- `college(String) : String`
- `major(String) : String`
- `className(String) : String`
- `student(String) : String`

## edu.campus.common.ApiException

源码：[common/src/main/java/edu/campus/common/ApiException.java](../common/src/main/java/edu/campus/common/ApiException.java)。类型：CLASS。

可预期的业务异常，携带 HTTP 状态、稳定错误码和可展示说明。require 将业务前置条件变成失败响应。

字段：

- `status : int`
- `code : String`

方法及构造器：

- `require(boolean,int,String) : void`

## edu.campus.common.Crypto

源码：[common/src/main/java/edu/campus/common/Crypto.java](../common/src/main/java/edu/campus/common/Crypto.java)。类型：CLASS。

加密与完整性原语。AES-GCM 使用随机 96 位 nonce；AAD 绑定成绩身份、状态和版本；HMAC-SHA256 认证内部请求和账本；解密失败统一返回完整性错误。

字段：

- `srandom : java.security.SecureRandom`
- `PBKDF2_ITERATIONS : int`
- `KEY_CACHE_LIMIT : int`
- `KEY_CACHE : java.util.Map<java.lang.String,javax.crypto.spec.SecretKeySpec>`

方法及构造器：

- `random() : String`
- `hash(String) : String`
- `hmac(String,String) : String`
- `equal(String,String) : boolean`
- `encrypt(String,String,String) : String`
- `decrypt(String,String,String) : String`
- `deriveKey(String,byte[]) : javax.crypto.spec.SecretKeySpec`

## edu.campus.common.ErrorAdvice

源码：[common/src/main/java/edu/campus/common/ErrorAdvice.java](../common/src/main/java/edu/campus/common/ErrorAdvice.java)。类型：CLASS。

跨控制器异常映射。业务错误保留状态；格式错误返回 400；内部错误仅暴露追踪编号，避免堆栈与 SQL 泄漏。

方法及构造器：

- `missing(Exception) : ResponseEntity<?>`
- `api(edu.campus.common.ApiException) : ResponseEntity<?>`
- `invalid(Exception) : ResponseEntity<?>`
- `other(Exception) : ResponseEntity<?>`

## edu.campus.common.InternalSecurity

源码：[common/src/main/java/edu/campus/common/InternalSecurity.java](../common/src/main/java/edu/campus/common/InternalSecurity.java)。类型：CLASS。

所有 /internal 路径的服务间鉴权过滤器。限定调用方角色、30 秒时间窗、一次性 nonce、请求体大小与 HMAC；缓存请求体后继续 MVC 解析。

字段：

- `nonces : java.util.Map<java.lang.String,java.lang.Long>`

方法及构造器：

- `shouldNotFilter(HttpServletRequest) : boolean`
- `doFilterInternal(HttpServletRequest,HttpServletResponse,FilterChain) : void`

## edu.campus.common.Protocol

源码：[common/src/main/java/edu/campus/common/Protocol.java](../common/src/main/java/edu/campus/common/Protocol.java)。类型：CLASS。

远程值传递契约容器。查询与操纵分开定义，字段顺序显式传递，不允许浏览器携带 SQL。

## edu.campus.common.Protocol.Selection

源码：[common/src/main/java/edu/campus/common/Protocol.java](../common/src/main/java/edu/campus/common/Protocol.java)。类型：RECORD。

远程值传递契约容器。查询与操纵分开定义，字段顺序显式传递，不允许浏览器携带 SQL。

此命名内部类型属于上面的组件职责；字段即值传递载荷或内部状态，成员清单以编译器解析结果为准。

字段：

- `table : String`
- `fields : java.util.List<java.lang.String>`
- `where : java.util.Map<java.lang.String,java.lang.Object>`
- `orderBy : String`
- `offset : int`
- `limit : int`

方法及构造器：

- `toString() : String`
- `hashCode() : int`
- `equals(Object) : boolean`
- `table() : String`
- `fields() : java.util.List<java.lang.String>`
- `where() : java.util.Map<java.lang.String,java.lang.Object>`
- `orderBy() : String`
- `offset() : int`
- `limit() : int`

## edu.campus.common.Protocol.Operation

源码：[common/src/main/java/edu/campus/common/Protocol.java](../common/src/main/java/edu/campus/common/Protocol.java)。类型：RECORD。

远程值传递契约容器。查询与操纵分开定义，字段顺序显式传递，不允许浏览器携带 SQL。

此命名内部类型属于上面的组件职责；字段即值传递载荷或内部状态，成员清单以编译器解析结果为准。

字段：

- `type : String`
- `table : String`
- `values : java.util.Map<java.lang.String,java.lang.Object>`
- `where : java.util.Map<java.lang.String,java.lang.Object>`
- `expectedCount : Integer`

方法及构造器：

- `toString() : String`
- `hashCode() : int`
- `equals(Object) : boolean`
- `type() : String`
- `table() : String`
- `values() : java.util.Map<java.lang.String,java.lang.Object>`
- `where() : java.util.Map<java.lang.String,java.lang.Object>`
- `expectedCount() : Integer`

## edu.campus.common.Protocol.Mutation

源码：[common/src/main/java/edu/campus/common/Protocol.java](../common/src/main/java/edu/campus/common/Protocol.java)。类型：RECORD。

远程值传递契约容器。查询与操纵分开定义，字段顺序显式传递，不允许浏览器携带 SQL。

此命名内部类型属于上面的组件职责；字段即值传递载荷或内部状态，成员清单以编译器解析结果为准。

字段：

- `operations : java.util.List<edu.campus.common.Protocol.Operation>`
- `actor : String`
- `action : String`
- `resource : String`
- `requestId : String`

方法及构造器：

- `toString() : String`
- `hashCode() : int`
- `equals(Object) : boolean`
- `operations() : java.util.List<edu.campus.common.Protocol.Operation>`
- `actor() : String`
- `action() : String`
- `resource() : String`
- `requestId() : String`

## edu.campus.common.Protocol.Registration

源码：[common/src/main/java/edu/campus/common/Protocol.java](../common/src/main/java/edu/campus/common/Protocol.java)。类型：RECORD。

远程值传递契约容器。查询与操纵分开定义，字段顺序显式传递，不允许浏览器携带 SQL。

此命名内部类型属于上面的组件职责；字段即值传递载荷或内部状态，成员清单以编译器解析结果为准。

字段：

- `service : String`
- `instance : String`
- `url : String`

方法及构造器：

- `toString() : String`
- `hashCode() : int`
- `equals(Object) : boolean`
- `service() : String`
- `instance() : String`
- `url() : String`

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
- `changes : java.util.List<java.util.Map<java.lang.String,java.lang.Object>>`

方法及构造器：

- `toString() : String`
- `hashCode() : int`
- `equals(Object) : boolean`
- `id() : String`
- `actor() : String`
- `action() : String`
- `resource() : String`
- `time() : String`
- `changes() : java.util.List<java.util.Map<java.lang.String,java.lang.Object>>`

## edu.campus.common.Protocol.SelectInterface

源码：[common/src/main/java/edu/campus/common/Protocol.java](../common/src/main/java/edu/campus/common/Protocol.java)。类型：INTERFACE。

远程值传递契约容器。查询与操纵分开定义，字段顺序显式传递，不允许浏览器携带 SQL。

此命名内部类型属于上面的组件职责；字段即值传递载荷或内部状态，成员清单以编译器解析结果为准。

方法及构造器：

- `select(edu.campus.common.Protocol.Selection) : String[][]`

## edu.campus.common.Protocol.ManipulationInterface

源码：[common/src/main/java/edu/campus/common/Protocol.java](../common/src/main/java/edu/campus/common/Protocol.java)。类型：INTERFACE。

远程值传递契约容器。查询与操纵分开定义，字段顺序显式传递，不允许浏览器携带 SQL。

此命名内部类型属于上面的组件职责；字段即值传递载荷或内部状态，成员清单以编译器解析结果为准。

方法及构造器：

- `manipulate(edu.campus.common.Protocol.Mutation) : boolean`

## edu.campus.common.RpcClient

源码：[common/src/main/java/edu/campus/common/RpcClient.java](../common/src/main/java/edu/campus/common/RpcClient.java)。类型：CLASS。

Webservice 远程调用客户端。HTTPS 使用可信证书校验，连接/请求超时有上限；服务发现后签名调用；远端业务错误保持 HTTP 语义。

字段：

- `caller : String`
- `client : java.net.http.HttpClient`

方法及构造器：

- `buildClient() : java.net.http.HttpClient`
- `raw(String,String,String,java.util.Map<java.lang.String,java.lang.String>) : java.net.http.HttpResponse<java.lang.String>`
- `postUrl(String,Object,Class<T>) : T`
- `discover(String) : String`
- `post(String,String,Object,Class<T>) : T`

## edu.campus.common.ServiceHeartbeat

源码：[common/src/main/java/edu/campus/common/ServiceHeartbeat.java](../common/src/main/java/edu/campus/common/ServiceHeartbeat.java)。类型：CLASS。

每五秒向网关续租，进程实例 UUID 区分副本；注册失败后下个周期重试，不阻止已有进程继续启动。

字段：

- `port : int`
- `serverAddress : String`
- `gatewayUrl : String`
- `id : String`

方法及构造器：

- `beat() : void`

## edu.campus.common.Settings

源码：[common/src/main/java/edu/campus/common/Settings.java](../common/src/main/java/edu/campus/common/Settings.java)。类型：CLASS。

运行配置读取器。环境变量优先，缺少配置时拒绝启动；初始化工具生成随机密钥和密码。server 配置 TLS 和回环地址，json 统一序列化。

字段：

- `JSON : ObjectMapper`
- `loaded : boolean`
- `PREFIX : String`
- `env : Environment`

方法及构造器：

- `get(String) : String`
- `root() : java.nio.file.Path`
- `json(Object) : String`

## edu.campus.common.Settings.Loader

源码：[common/src/main/java/edu/campus/common/Settings.java](../common/src/main/java/edu/campus/common/Settings.java)。类型：CLASS。

运行配置读取器。环境变量优先，缺少配置时拒绝启动；初始化工具生成随机密钥和密码。server 配置 TLS 和回环地址，json 统一序列化。

此命名内部类型属于上面的组件职责；字段即值传递载荷或内部状态，成员清单以编译器解析结果为准。

方法及构造器：

- `init(Environment) : void`

## edu.campus.data.DataApplication

源码：[data-service/src/main/java/edu/campus/data/DataApplication.java](../data-service/src/main/java/edu/campus/data/DataApplication.java)。类型：CLASS。

独立数据访问进程入口。默认启动加密 H2 文件库，根据 DB_URL/DB_USER/DB_PASSWORD 切换外部数据库。

方法及构造器：

- `main(String[]) : void`

## edu.campus.data.DataRpcController

源码：[data-service/src/main/java/edu/campus/data/DataRpcController.java](../data-service/src/main/java/edu/campus/data/DataRpcController.java)。类型：CLASS。

实现 SelectInterface 和 ManipulationInterface，分别暴露二维字符串查询和布尔事务操纵接口；内部签名过滤器先行鉴权。

字段：

- `service : edu.campus.data.TransactionService`

方法及构造器：

- `select(edu.campus.common.Protocol.Selection) : String[][]`
- `manipulate(edu.campus.common.Protocol.Mutation) : boolean`
- `status() : java.util.Map<java.lang.String,java.lang.Object>`

## edu.campus.data.DemoInitializer

源码：[data-service/src/main/java/edu/campus/data/DemoInitializer.java](../data-service/src/main/java/edu/campus/data/DemoInitializer.java)。类型：CLASS。

演示数据初始化。结构版本变化或表为空时整库重建：写入多学院/多专业/多班级组织、按学院归属的师生账号、同学期课程代码唯一的教学班与历史成绩，并同步重建独立审计账本。

字段：

- `DEFAULT_PASSWORD : String`
- `PASSWORDS : BCryptPasswordEncoder`
- `ADMIN_USERNAMES : java.util.Set<java.lang.String>`
- `DISABLED_USERNAMES : java.util.Set<java.lang.String>`
- `ADMIN_PERMISSIONS : String`
- `COLLEGES : java.util.List<edu.campus.data.DemoInitializer.Org>`
- `MAJORS : java.util.List<edu.campus.data.DemoInitializer.Org>`
- `CLASSES : java.util.List<edu.campus.data.DemoInitializer.Org>`
- `PEOPLE : java.util.List<edu.campus.data.DemoInitializer.Person>`
- `STUDENTS : String[][]`
- `COURSES : java.util.List<edu.campus.data.DemoInitializer.Course>`
- `RNG : java.util.Random`
- `CURRENT_TERM : String`
- `MIN_PREDICTION_YEARS : int`
- `MIN_PREDICTION_SAMPLES : int`
- `SAMPLE_CODES_2020 : java.util.List<java.lang.String>`
- `SAMPLE_CODES_2021 : java.util.List<java.lang.String>`
- `SAMPLE_CODES_2023 : java.util.List<java.lang.String>`
- `SAMPLE_LAYERS : java.util.List<edu.campus.data.DemoInitializer.SampleLayer>`
- `PARTIAL_POOL_CLASS : String`
- `SAMPLE_COURSES : java.util.List<edu.campus.data.DemoInitializer.Course>`
- `SAMPLE_COURSE_IDS : java.util.Set<java.lang.String>`
- `SAMPLE_RNG : java.util.Random`
- `RETAKES : java.util.List<edu.campus.data.DemoInitializer.Retake>`
- `RETAKE_ONLY_COURSES : java.util.Set<java.lang.String>`
- `MIN_RETAKERS : int`
- `PASS_SCORE : double`
- `FAILED_PAYLOAD : String`
- `RETAKE_PASSED_PAYLOAD : String`
- `jdbc : JdbcTemplate`
- `catalog : edu.campus.data.SchemaCatalog`
- `KEY : String`
- `audit : edu.campus.common.RpcClient`

方法及构造器：

- `admin(String,String) : edu.campus.data.DemoInitializer.Person`
- `teacher(String,String,String,String) : edu.campus.data.DemoInitializer.Person`
- `student(String,String,String,String,String) : edu.campus.data.DemoInitializer.Person`
- `buildSampleCourses() : java.util.List<edu.campus.data.DemoInitializer.Course>`
- `sampleCourseIds() : java.util.Set<java.lang.String>`
- `prototypeOf(String) : edu.campus.data.DemoInitializer.Course`
- `studentsOfClass(String) : java.util.List<java.lang.String>`
- `run(ApplicationArguments) : void`
- `resetRequested() : boolean`
- `count(String) : long`
- `clearAll() : void`
- `declaredTerms() : java.util.Set<java.lang.String>`
- `purgeTestArtifacts() : void`
- `resetLedger() : void`
- `verifyOrganizationIntegrity() : void`
- `verifyTranscriptIntegrity() : void`
- `findRetake(String,String) : edu.campus.data.DemoInitializer.Retake`
- `verifyPredictionCoverage() : void`
- `complete(java.util.Map<java.lang.String,java.lang.Object>) : boolean`
- `awaitingFinal(java.util.Map<java.lang.String,java.lang.Object>) : boolean`
- `courseIdOf(String) : String`
- `coursesById() : java.util.Map<java.lang.String,edu.campus.data.DemoInitializer.Course>`
- `loadGrades() : java.util.Map<java.lang.String,edu.campus.data.DemoInitializer.GradeRow>`
- `scores(String) : java.util.Map<java.lang.String,java.lang.Object>`
- `effective(edu.campus.data.DemoInitializer.Course,java.util.Map<java.lang.String,java.lang.Object>) : Double`
- `column(java.util.Map<java.lang.String,java.lang.Object>,String) : String`
- `seedOrganizations() : void`
- `find(java.util.List<edu.campus.data.DemoInitializer.Org>,String) : edu.campus.data.DemoInitializer.Org`
- `seedUsers() : void`
- `blank(String) : String`
- `collegeNameOf(String) : String`
- `classNameOf(String) : String`
- `seedCourses() : void`
- `selectedAt(String) : String`
- `seedEnrollmentsAndGrades() : void`
- `seedPartialStudents(String,java.util.List<java.lang.Object[]>,int[]) : void`
- `samplePayload(edu.campus.data.DemoInitializer.Course) : String`
- `retakePairs() : java.util.Set<java.lang.String>`
- `insertEnrollment(String,java.util.List<java.lang.Object[]>,int[],edu.campus.data.DemoInitializer.Course,String) : void`
- `insertPartialGrade(String,edu.campus.data.DemoInitializer.Course,String,String) : void`
- `partialPayload() : String`
- `payload(edu.campus.data.DemoInitializer.Course,String) : String`
- `round(double) : double`
- `seedCourseSelection() : void`
- `seedAuditLedger() : void`

## edu.campus.data.DemoInitializer.Org

源码：[data-service/src/main/java/edu/campus/data/DemoInitializer.java](../data-service/src/main/java/edu/campus/data/DemoInitializer.java)。类型：RECORD。

演示数据初始化。结构版本变化或表为空时整库重建：写入多学院/多专业/多班级组织、按学院归属的师生账号、同学期课程代码唯一的教学班与历史成绩，并同步重建独立审计账本。

此命名内部类型属于上面的组件职责；字段即值传递载荷或内部状态，成员清单以编译器解析结果为准。

字段：

- `id : String`
- `name : String`
- `code : String`
- `parent : String`
- `extra : Object`

方法及构造器：

- `toString() : String`
- `hashCode() : int`
- `equals(Object) : boolean`
- `id() : String`
- `name() : String`
- `code() : String`
- `parent() : String`
- `extra() : Object`

## edu.campus.data.DemoInitializer.Person

源码：[data-service/src/main/java/edu/campus/data/DemoInitializer.java](../data-service/src/main/java/edu/campus/data/DemoInitializer.java)。类型：RECORD。

演示数据初始化。结构版本变化或表为空时整库重建：写入多学院/多专业/多班级组织、按学院归属的师生账号、同学期课程代码唯一的教学班与历史成绩，并同步重建独立审计账本。

此命名内部类型属于上面的组件职责；字段即值传递载荷或内部状态，成员清单以编译器解析结果为准。

字段：

- `id : String`
- `name : String`
- `role : String`
- `permissions : String`
- `college : String`
- `major : String`
- `klass : String`

方法及构造器：

- `toString() : String`
- `hashCode() : int`
- `equals(Object) : boolean`
- `id() : String`
- `name() : String`
- `role() : String`
- `permissions() : String`
- `college() : String`
- `major() : String`
- `klass() : String`

## edu.campus.data.DemoInitializer.Course

源码：[data-service/src/main/java/edu/campus/data/DemoInitializer.java](../data-service/src/main/java/edu/campus/data/DemoInitializer.java)。类型：RECORD。

演示数据初始化。结构版本变化或表为空时整库重建：写入多学院/多专业/多班级组织、按学院归属的师生账号、同学期课程代码唯一的教学班与历史成绩，并同步重建独立审计账本。

此命名内部类型属于上面的组件职责；字段即值传递载荷或内部状态，成员清单以编译器解析结果为准。

字段：

- `id : String`
- `code : String`
- `name : String`
- `term : String`
- `teacher : String`
- `credits : double`
- `college : String`
- `klass : String`
- `regular : int`
- `lab : int`
- `finalExam : int`
- `submitted : boolean`

方法及构造器：

- `toString() : String`
- `hashCode() : int`
- `equals(Object) : boolean`
- `id() : String`
- `code() : String`
- `name() : String`
- `term() : String`
- `teacher() : String`
- `credits() : double`
- `college() : String`
- `klass() : String`
- `regular() : int`
- `lab() : int`
- `finalExam() : int`
- `submitted() : boolean`

## edu.campus.data.DemoInitializer.SampleLayer

源码：[data-service/src/main/java/edu/campus/data/DemoInitializer.java](../data-service/src/main/java/edu/campus/data/DemoInitializer.java)。类型：RECORD。

演示数据初始化。结构版本变化或表为空时整库重建：写入多学院/多专业/多班级组织、按学院归属的师生账号、同学期课程代码唯一的教学班与历史成绩，并同步重建独立审计账本。

此命名内部类型属于上面的组件职责；字段即值传递载荷或内部状态，成员清单以编译器解析结果为准。

字段：

- `term : String`
- `poolClass : String`
- `teacher : String`
- `codes : java.util.List<java.lang.String>`

方法及构造器：

- `toString() : String`
- `hashCode() : int`
- `equals(Object) : boolean`
- `term() : String`
- `poolClass() : String`
- `teacher() : String`
- `codes() : java.util.List<java.lang.String>`

## edu.campus.data.DemoInitializer.Retake

源码：[data-service/src/main/java/edu/campus/data/DemoInitializer.java](../data-service/src/main/java/edu/campus/data/DemoInitializer.java)。类型：RECORD。

演示数据初始化。结构版本变化或表为空时整库重建：写入多学院/多专业/多班级组织、按学院归属的师生账号、同学期课程代码唯一的教学班与历史成绩，并同步重建独立审计账本。

此命名内部类型属于上面的组件职责；字段即值传递载荷或内部状态，成员清单以编译器解析结果为准。

字段：

- `student : String`
- `code : String`
- `failedCourse : String`
- `retakeCourse : String`

方法及构造器：

- `toString() : String`
- `hashCode() : int`
- `equals(Object) : boolean`
- `student() : String`
- `code() : String`
- `failedCourse() : String`
- `retakeCourse() : String`

## edu.campus.data.DemoInitializer.Attempt

源码：[data-service/src/main/java/edu/campus/data/DemoInitializer.java](../data-service/src/main/java/edu/campus/data/DemoInitializer.java)。类型：RECORD。

演示数据初始化。结构版本变化或表为空时整库重建：写入多学院/多专业/多班级组织、按学院归属的师生账号、同学期课程代码唯一的教学班与历史成绩，并同步重建独立审计账本。

此命名内部类型属于上面的组件职责；字段即值传递载荷或内部状态，成员清单以编译器解析结果为准。

字段：

- `courseId : String`
- `term : String`
- `effective : Double`

方法及构造器：

- `toString() : String`
- `hashCode() : int`
- `equals(Object) : boolean`
- `courseId() : String`
- `term() : String`
- `effective() : Double`

## edu.campus.data.DemoInitializer.GradeRow

源码：[data-service/src/main/java/edu/campus/data/DemoInitializer.java](../data-service/src/main/java/edu/campus/data/DemoInitializer.java)。类型：RECORD。

演示数据初始化。结构版本变化或表为空时整库重建：写入多学院/多专业/多班级组织、按学院归属的师生账号、同学期课程代码唯一的教学班与历史成绩，并同步重建独立审计账本。

此命名内部类型属于上面的组件职责；字段即值传递载荷或内部状态，成员清单以编译器解析结果为准。

字段：

- `scores : java.util.Map<java.lang.String,java.lang.Object>`
- `state : String`

方法及构造器：

- `toString() : String`
- `hashCode() : int`
- `equals(Object) : boolean`
- `scores() : java.util.Map<java.lang.String,java.lang.Object>`
- `state() : String`

## edu.campus.data.SchemaCatalog

源码：[data-service/src/main/java/edu/campus/data/SchemaCatalog.java](../data-service/src/main/java/edu/campus/data/SchemaCatalog.java)。类型：CLASS。

数据库结构、列名和类型白名单。主键、唯一索引、厂商大文本类型初始化；外部生产数据库建议通过迁移管理工具预建结构。

字段：

- `SCHEMA_VERSION : int`
- `META_TABLE : String`
- `tables : java.util.Map<java.lang.String,java.util.LinkedHashMap<java.lang.String,java.lang.String>>`
- `jdbc : JdbcTemplate`
- `rebuilt : boolean`

方法及构造器：

- `add(String,String) : void`
- `columns(String) : java.util.LinkedHashMap<java.lang.String,java.lang.String>`
- `init() : void`
- `wasRebuilt() : boolean`
- `hasBusinessRows() : boolean`
- `structureVersion() : int`
- `writeStructureVersion() : void`
- `dropAll() : void`
- `drop(String) : void`
- `tableExists(String) : boolean`
- `missingColumns(String,java.util.LinkedHashMap<java.lang.String,java.lang.String>) : java.util.List<java.lang.String>`
- `definition(java.util.LinkedHashMap<java.lang.String,java.lang.String>,String) : String`
- `plainType(String,String,String) : String`
- `sqlType(String,String,String) : String`
- `uniqueIndex(String,String) : void`
- `index(String,String) : void`

## edu.campus.data.SqlCompiler

源码：[data-service/src/main/java/edu/campus/data/SqlCompiler.java](../data-service/src/main/java/edu/campus/data/SqlCompiler.java)。类型：CLASS。

将 Selection/Operation 编译为 SQL 与绑定参数列表。仅标识符进入 SQL 文本，按元数据把数字转换成整数/Decimal；更新删除必须有主键。

字段：

- `catalog : edu.campus.data.SchemaCatalog`

方法及构造器：

- `value(String,String,Object) : Object`
- `where(String,java.util.Map<java.lang.String,java.lang.Object>,java.util.List<java.lang.Object>) : String`
- `select(edu.campus.common.Protocol.Selection) : edu.campus.data.SqlCompiler.Statement`
- `mutate(edu.campus.common.Protocol.Operation) : edu.campus.data.SqlCompiler.Statement`

## edu.campus.data.SqlCompiler.Statement

源码：[data-service/src/main/java/edu/campus/data/SqlCompiler.java](../data-service/src/main/java/edu/campus/data/SqlCompiler.java)。类型：RECORD。

将 Selection/Operation 编译为 SQL 与绑定参数列表。仅标识符进入 SQL 文本，按元数据把数字转换成整数/Decimal；更新删除必须有主键。

此命名内部类型属于上面的组件职责；字段即值传递载荷或内部状态，成员清单以编译器解析结果为准。

字段：

- `sql : String`
- `parameters : java.util.List<java.lang.Object>`

方法及构造器：

- `toString() : String`
- `hashCode() : int`
- `equals(Object) : boolean`
- `sql() : String`
- `parameters() : java.util.List<java.lang.Object>`

## edu.campus.data.TransactionService

源码：[data-service/src/main/java/edu/campus/data/TransactionService.java](../data-service/src/main/java/edu/campus/data/TransactionService.java)。类型：CLASS。

数据事务协调器。查询成绩时验证 AAD；批量维护时检查 expectedCount，写前/写后快照与数据库发件箱同事务持久化；flush 网络同步且按事件 ID 重试。

字段：

- `jdbc : JdbcTemplate`
- `compiler : edu.campus.data.SqlCompiler`
- `catalog : edu.campus.data.SchemaCatalog`
- `tx : TransactionTemplate`
- `audit : edu.campus.common.RpcClient`
- `TRACE : boolean`
- `SNAPSHOT_SECRETS : java.util.Map<java.lang.String,java.util.List<java.lang.String>>`

方法及构造器：

- `select(edu.campus.common.Protocol.Selection) : String[][]`
- `row(String,Object) : java.util.Map<java.lang.String,java.lang.Object>`
- `sealSnapshot(String,String,String,Object) : String`
- `manipulate(edu.campus.common.Protocol.Mutation) : boolean`
- `flush() : void`
- `status() : java.util.Map<java.lang.String,java.lang.Object>`

## edu.campus.gateway.GatewayApplication

源码：[gateway/src/main/java/edu/campus/gateway/GatewayApplication.java](../gateway/src/main/java/edu/campus/gateway/GatewayApplication.java)。类型：CLASS。

网关启动入口，设置服务身份、默认端口 8443 和调度器。

方法及构造器：

- `main(String[]) : void`

## edu.campus.gateway.GatewayController

源码：[gateway/src/main/java/edu/campus/gateway/GatewayController.java](../gateway/src/main/java/edu/campus/gateway/GatewayController.java)。类型：CLASS。

统一收集浏览器请求信封，校验来源/方法/长度，仅转发 Cookie 与 CSRF 等必要信息；内部签名由网关重新生成。

字段：

- `registry : edu.campus.gateway.RegistryController`
- `rpc : edu.campus.common.RpcClient`

方法及构造器：

- `proxy(HttpServletRequest,HttpServletResponse) : void`

## edu.campus.gateway.RegistryController

源码：[gateway/src/main/java/edu/campus/gateway/RegistryController.java](../gateway/src/main/java/edu/campus/gateway/RegistryController.java)。类型：CLASS。

服务注册与发现。URL 白名单防 SSRF，20 秒租约清除过期实例，轮询选择；health 只公开服务名和实例编号。

字段：

- `entries : java.util.Map<java.lang.String,edu.campus.gateway.RegistryController.Entry>`
- `cursor : java.util.concurrent.atomic.AtomicInteger`

方法及构造器：

- `register(edu.campus.common.Protocol.Registration,String) : boolean`
- `discover(java.util.Map<java.lang.String,java.lang.String>) : java.util.Map<java.lang.String,java.lang.String>`
- `choose(String) : String`
- `health() : java.util.Map<java.lang.String,java.lang.Object>`

## edu.campus.gateway.RegistryController.Entry

源码：[gateway/src/main/java/edu/campus/gateway/RegistryController.java](../gateway/src/main/java/edu/campus/gateway/RegistryController.java)。类型：RECORD。

服务注册与发现。URL 白名单防 SSRF，20 秒租约清除过期实例，轮询选择；health 只公开服务名和实例编号。

此命名内部类型属于上面的组件职责；字段即值传递载荷或内部状态，成员清单以编译器解析结果为准。

字段：

- `registration : edu.campus.common.Protocol.Registration`
- `seen : long`

方法及构造器：

- `toString() : String`
- `hashCode() : int`
- `equals(Object) : boolean`
- `registration() : edu.campus.common.Protocol.Registration`
- `seen() : long`

## edu.campus.gateway.SystemController

源码：[gateway/src/main/java/edu/campus/gateway/SystemController.java](../gateway/src/main/java/edu/campus/gateway/SystemController.java)。类型：CLASS。

网关的系统状态页，直接把 classpath 下的 system/index.html 原样返回，供运维检查注册与心跳。

方法及构造器：

- `systemPage() : String`

## edu.campus.gateway.WebConfiguration

源码：[gateway/src/main/java/edu/campus/gateway/WebConfiguration.java](../gateway/src/main/java/edu/campus/gateway/WebConfiguration.java)。类型：CLASS。

将构建后的前端发布到 HTTPS 网关，并在嵌套过滤器中统一输出 CSP、HSTS、防嗅探、禁止嵌入与 no-store 头。

## edu.campus.gateway.WebConfiguration.Headers

源码：[gateway/src/main/java/edu/campus/gateway/WebConfiguration.java](../gateway/src/main/java/edu/campus/gateway/WebConfiguration.java)。类型：CLASS。

将构建后的前端发布到 HTTPS 网关，并在嵌套过滤器中统一输出 CSP、HSTS、防嗅探、禁止嵌入与 no-store 头。

此命名内部类型属于上面的组件职责；字段即值传递载荷或内部状态，成员清单以编译器解析结果为准。

方法及构造器：

- `doFilterInternal(HttpServletRequest,HttpServletResponse,FilterChain) : void`

## 前端与工具职责

`App.vue` 维护角色可见视图、课程状态、成绩表草稿、模态框及交互，所有服务器结果通过 Vue 文本绑定渲染。`api.js` 是统一同源请求客户端，读取 CSRF Cookie 并映射失败。`server.mjs` 维护独立 EVM、串行锚定请求和 LSTM 模型。`setup.mjs` 生成证书/随机配置，`start.sh` 一键启动与停止全部服务，OCR 离线资源随源码置于 `frontend/public/ocr/`。测试脚本不属于业务运行入口。

### frontend/src/ocr.js（成绩单图片识别，浏览器本地）

源码：[frontend/src/ocr.js](../frontend/src/ocr.js)。纯 ES 模块，只依赖浏览器 `document`/`Image`/`Canvas` 与随源码提供的 tesseract.js；**不引用 `api.js`，不产生任何后端请求**。导出函数与常量的职责、参数与返回值：

|导出|职责|参数 / 返回值要点|
|---|---|---|
|`VARIANTS`|预处理候选的**常量表**（11 项），保留供文档与回归对照；当前 `buildVariants` **不再逐个消费**它|每项 `{id, label, binarize: "otsu"\|"adaptive", rotate: 0\|±1\|±2, scale: 1\|2}`；`label` 用于界面与排障展示。实际候选由 `buildVariants` 按轮生成：`VARIANTS[0]`（`gray`）、`VARIANTS[1]`（`otsu`）、就地新建的 `adaptive` 与 `gray-up2`，以及条件追加的 `gray-raw`|
|`loadCanvas(source, maxEdge = 2600)`|把图片/画布等比缩到最长边 ≤ `maxEdge` 并画到新 Canvas，**随后调用 `invertIfDark` 自动反色**|入参 `HTMLImageElement\|HTMLCanvasElement`；返回 `HTMLCanvasElement`（已按需反色）|
|`invertIfDark(canvas)`|深色背景自动反色（暗色主题截图的修复，见 [ocr-voice-design.md](ocr-voice-design.md) 4.1.1）|判据：逐像素 `299/587/114` 加权平均灰度 **< 110** 且灰度 **< 128** 的像素占比 **> 55%**，两个条件同时满足才把 R/G/B 置 `255 - v`；返回 `boolean`（是否反色）。由 `loadCanvas` 调用，因此只有真实图片文件会经过它——直接传 Canvas 时 `recognizeSheet`/`recognizeBest` 不再二次处理|
|`estimateSkew(canvas, options)`|投影法估计倾斜角（粗估计 `coarseSkew`）|`options.candidates` 默认 `[-4,-3,-2,-1.5,-1,-0.5,0,0.5,1,1.5,2,3,4]`；返回角度（度），正值表示图顺时针倾斜，校正时应反向旋转。实测会明显偏小（最新一轮 2° 图估到 `0.41`、4° 图估到 `0.88`，见 [ocr-voice-design.md](ocr-voice-design.md) 4.2）|
|`segmentBands(binary, width, height, options)`|水平投影切出行带（**自适应阈值选优**）|`options.minHeight`（默认 12）、`options.maxRows`（默认 200）、`options.ratios`（默认 `[0.06, 0.03, 0.12, 0.015]`）；阈值 `max(1, min(maxInk × ratio, p90 × ratio))`，按「行数落在 `[expectedLines/3, expectedLines×2]` 内最好、越接近 `expectedLines` 越优、其次行数多者优先」打分；`expectedLines = round(强墨行数/3)`，碎带过滤 `minBandInk = max(8, 总墨量 × 0.004)`；返回 `{top, bottom, ink}[]`（已合并/过滤/上下外扩 4px）|
|`recognizeSheet(source, onProgress, options)`|低层入口：返回**全部**候选结果|`options` 可含 `tesseract`/`maxVariants`（默认 `VARIANTS.length`，即 11）/`goodEnough`（默认 55）/`skew`/`offsets`（默认 `[0]`）；返回 `{variants, width, height, skew}`，每个变体为 `{id, label, angle, rows, quality}`|
|`recognizeBest(source, onProgress, options)`|高层入口：**粗估计 + 基线自校正 + 尺度回退 + offset 微调**，返回最优结果|`options` 可含 `tesseract`/`goodEnough`（默认 1）/`roster`/`components`/`allowDigitCorrection`/`maxVariants`（默认 4）/`variantQuality`（默认 55）/`skew`/**`scaleSteps`（默认 `[2, 3, 1.5]`，小字号截图的放大回退，见 [ocr-voice-design.md](ocr-voice-design.md) 4.1.2）**；返回 `{best, variants, skew, coarseSkew, corrected, passes, width, height}`，`best` 额外带 `usable`/`meanConfidence`/`score`。**`maxPasses` 与 `rotationSets` 都不存在/不被读取**：前者早已从签名移除，后者只残留在本函数上方那段过时 JSDoc 里（微调序列写死为 `+1/-1/+2/-2`）|
|`scoreVariant(variant, components, roster, options)`|候选打分|返回 `{usable, rows, filled, mean, columns}`；`usable` 为主排序键（命中名册且有分数的行数），`filled` 为已填格子数，`mean` 为预览行平均置信度（1 位小数）。`options.allowDigitCorrection` 会透传给 `buildPreview` → `mapColumns`|
|`estimateTiltFromRows(rows)`|从识别结果量**残余倾斜角**（自校正用）|**baseline 优先**：取 `line.baseline` 的斜率，要求 `\|dx\| ≥ 60px`、`\|angle\| ≤ 12°`，≥ 2 条样本时取均值（2 位小数）；否则退化为所有 text line 外接框中心的**最小二乘拟合**（样本 < 6 或 `variance < 1` 返回 `null`）；返回 `number\|null`|
|`rowTokens(words)`|把一行的词按 x 排序并 token 化|支持 `{bbox:{x0,x1,y0,y1}}` 与扁平两种词结构；粘连 token 按等分估算 x 区间；返回 `{text, number, numeric, confidence, x0, x1, y0, y1}[]`|
|`fixConfusion(text)`|按混淆表归一字符|`O/o/Q/D→0`、`I/l/\|/!/i→1`、`Z/z→2`、`B→8`、`S/s→5`、`G/b→6`、`T→7`、`A→4`、`g/q→9`；返回仅含 `[0-9A-Za-z]` 的串|
|`editDistance(a, b, limit = 3)`|编辑距离（**完整计算后截断**，无行最小值剪枝）|`\|len(a) − len(b)\| > limit` 时直接返回 `limit + 1`；否则跑完整 DP，最后 `distance > limit ? limit + 1 : distance`。**不要**加回"某行最小值超限即提前返回"的剪枝：首字符不同但整体只差 1 的对照会被误判为超限|
|`correctId(raw, candidates, options)`|学号纠错（**安全优先**）：① 归一化精确匹配 → ② 编辑距离 ≤ `maxDistance`（默认 1），距离相同优先 `confusionHits` 更多者，并列标 `ambiguous`|`options.maxDistance`（默认 1）、`options.allowDigitCorrection`（默认关）；返回 `{username, distance, corrected, fixed, confusionHits?, ambiguous?, alternatives?, unverified?, candidate?}` 或 `null`（名册外）。**安全规则**：最优候选差异 `confusionHits === 0` 时默认返回 `{username: null, unverified: true, candidate}`（不认人，交人工核对）；仅在 `allowDigitCorrection: true` 且差异**仅一位数字**时才自动认人|
|`idCandidates(tokens, idLength = 8)`|挑学号候选|长度 `[7,9]` 的 token、相邻两 token 拼接、以及长度 `[8,10]` 的纯数字 token；返回去重后的字符串数组|
|`assignColumns(tokens, anchors)`|按列锚点分配数值 token|比较 token **右边缘**（内部 `rightEdge`）与锚点；常规容差 `max(36, 中位列距 × 0.35)`，单位数 token（内部 `singleDigit`）容差 `max(18, 中位列距 × 0.12)`；超差留空、缺值不前移；返回与锚点等长的 `token\|null[]`|
|`inferAnchors(rows, columnCount)`|推导列锚点|入参可含 `words` 的行数组，或已切好的 token 数组；取数值 token 数最多的前 12 行，按位置取**右边缘**中位数，样本不足时用中位列距外推；返回 `number[]\|null`|
|`structureRows(input)`|便捷入口：`detectColumns` + `mapColumns`|`input` 含 `rows`/`components`/`roster`，可选 `idLength`（默认 8）/`minConfidence`（默认 85）/`detected`；返回预览行数组|
|`buildPreview(rows, components, roster, columnMap, options)`|端到端便捷入口（App 与测试脚本共用）|`options.allowDigitCorrection` 会透传给 `mapColumns`；返回 `{columns, tokens, rows, previewRows, summary}`|
|`isRowUsable(row)`|判断一行能否填入|`row.matched && row.cells.some(cell => cell.value !== null)`|
|`detectColumns(rows)`|抽出「检测到的分值列」|先剔除无分数形态 token 的行；返回 `{columns, tokens, rows}`|
|`mapColumns(input)`|把检测列映射成成绩项，生成预览行|`input` 含 `rows`/`tokens`/`components`/`roster`，可选 `columnMap`/`idLength`（默认 8）/`minConfidence`（默认 85）/`allowDigitCorrection`；逐行产出的 `issues` 含四种学号文案（未匹配 / 无法确认 / 多候选歧义 / 已纠正）；返回 `RecognizedRow[]`（字段见 [ocr-voice-design.md](ocr-voice-design.md) 第 3.1 与 3.3 节）|
|`defaultColumnMap(columnCount, componentCount)`|默认列映射|按顺序一一对应，超出列数的成绩项映射为 `null`（不导入）|
|`summarize(structure, components)`|汇总统计|返回 `{rows, usable, skipped, cells, filled, suspicious, columns, coverage}`|
|`__testRotate(canvas, degrees)`|仅供测试：暴露旋转实现|用于验证"转过去再转回来"的一致性|

内部实现（不导出）的职责：

- 图像与二值化：`toCanvas`（等比画到新 Canvas）、`grayToCanvas`、`toGray`（`299/587/114` 加权灰度）、`stretch`（2%~98% 分位直方图拉伸）、`otsu`（全局阈值）、`adaptiveThreshold`（积分图自适应均值阈值，`radius = max(8, min(w,h)/12)`、`offset = 10`）；
- 几何：`rotateCanvas`（**逐像素双线性插值**、白底；`estimateSkew` 与 `buildVariants` 共用）、`cropBand`（按行带切图并补白）；
- 行带：`sum`（区间墨量求和，供 `segmentBands` 使用）；
- 识别与拍平：`flattenWords`（`blocks→paragraphs→lines→words` 拍平为 `{text, confidence, x0, x1, y0, y1}`，`blocks` 为空时退化为整段文本）、`flattenLines`（拍平为 `{x, y, baseline}`，text line 级别，供 `estimateTiltFromRows` 使用）、`lineConfidence`（按词宽度加权平均置信度）、`variantQuality`（`平均行置信度 × (0.6 + 0.4 × min(1, 行数/8))`）；
- 候选生成与识别：`buildVariants(canvas, skew, offsets, options)`（**先按 `options.scale`（默认 1）放大**，再在 `|skew| > 0.25` 时按 `-skew` 回转，然后按轮产出 `gray`/`otsu`/`adaptive`（`binarize: "adaptive"`，真自适应均值阈值）/`gray-up2`（管线内再放大 2 倍），并在 `includeRaw !== false` 且确实做了校正时追加 `gray-raw` 原图；`options.rawFirst` 决定原图排在本轮最前还是最后）、`recognizeVariants`（创建 `eng` worker、逐候选识别、`quality >= 55` 提前结束、`finally` 终止 worker）、`recognizeRows`（单候选：灰度 → 拉伸 → 二值化 → 切带 → 逐行 PSM 7 → 汇总 `rows`/`lines`）、`rankPasses`（按 `usable → filled → mean` 排序候选，供 `recognizeBest` 取最优）；
- token 与列：`normalize`/`tokenize`/`asNumber`/`asScore`（`SCORE_SHAPE` 限制整数位 ≤ 3）/`box`/`medianGap`/`rightEdge`（token 右边缘；`x1` 非有限时退化为 `x0 + 20`，被 `assignColumns`/`inferAnchors`/`fallbackAnchors` 共用）/`singleDigit`（是否只剩 1 位数字，决定用单位数容差）/`maxNumericColumns`/`fallbackAnchors`；
- 学号：`compact`（只去分隔符）/`confusionHits`（统计差异中有几位能被 `CONFUSION` 解释，是"是否放行自动纠正"的判据）。

### frontend/src/voice.js（成绩语音录入）

源码：[frontend/src/voice.js](../frontend/src/voice.js)。同一模块里既有纯函数（可在 Node 里直接单测），也有浏览器 `SpeechRecognition` 的薄封装；同样**不引用 `api.js`**。

|导出|职责|参数 / 返回值要点|
|---|---|---|
|`chineseToNumber(text)`|中文数字 → 数值（0–999）|`DIGITS` + `UNITS`（十/拾、百/佰）；`八十五→85`、`一百→100`、`十→10`；纯阿拉伯数字直接转；无法解析返回 `null`|
|`toNumber(text)`|口语数字片段（含阿拉伯数字与小数）→ 数值|支持 `85`、`85.5`、`八十五点五`、`85点5`；无法解析返回 `null`|
|`scanUtterance(utterance, components)`|字符级扫描成有序语义单元|先归一标点；按「成绩项别名（最长优先）→ 阿拉伯数字 → 中文数字（含「点」小数）」匹配，数字后紧跟的「分」一并吃掉；返回 `{type: "component"\|"number"\|"unknown", name?, value?, text}[]`。**不按空格切词**，因为语音结果常常没有空格|
|`parseUtterance(utterance, components, options)`|解析一句口述|`options.defaults` 为该行已有值；具名项消费紧随的数字（越界记入 `rejected`），裸数字按列顺序补「本次未提到且当前为空」的项；返回 `{values, mentioned, unknown, rejected, unmatched, rest}`|
|`toCellUpdates(parsed, components)`|整理成待确认单元格|只回传 `mentioned` 里的项；返回 `{key, label, value}[]`|
|`speechSupport(scope = globalThis)`|能力探测|`Boolean(scope.SpeechRecognition \|\| scope.webkitSpeechRecognition)`|
|`createVoiceSession(handlers, scope)`|创建一次语音识别会话|`handlers` 为 `{onResult(text, isFinal), onError(error), onEnd()}`；配置 `lang="zh-CN"`、`continuous=true`、`interimResults=true`、`maxAlternatives=1`；不支持时返回 `{supported: false, start(){}, stop(){}, abort(){}}`；`stop()`/`abort()` 后不再回调 `onEnd`|
|`COMPONENT_ALIASES`|成绩项的中文口语别名表|`regular`/`attendance`/`homework`/`lab`/`midterm`/`finalExam`/`makeup` 各自一组别名（如 `lab: ["实验","上机","实验分"]`）|
|`buildVoiceComponents(components)`|规格化成绩项|同时接受 `[["regular","平时"], ...]` 与 `[{key,label}, ...]`；返回 `{key, label, aliases}[]`|

### frontend/src/components/RecognizePreview.vue（识别结果预览确认）

源码：[frontend/src/components/RecognizePreview.vue](../frontend/src/components/RecognizePreview.vue)。只负责「展示 + 编辑 + 勾选 + 汇报」，不认识 tesseract 也不调用后端；编辑与跳过只改本地副本，父组件收到 `confirm` 后才写入录入表单。

|props|类型|默认|说明|
|---|---|---|---|
|`open`|`Boolean`|`false`|为假时不渲染|
|`source`|`String`|`"local-ocr"`|`"voice"` 时标题显示「语音录入确认」|
|`rows`|`Array`|`[]`|`RecognizedRow[]`，被 `watch` 复制成本地可编辑副本并补 `enabled`|
|`components`|`Array`|`[]`|`{key, label}[]`，即参与录入的成绩项（列顺序）|
|`columns`|`Array`|`[]`|检测到的列下标，用于列映射下拉的「第 N 列」|
|`columnMap`|`Array`|`[]`|第 i 个成绩项对应的检测列下标，`null` 表示不导入|
|`summary`|`Object`|`{}`|`summarize` 的结果（当前仅作整体上下文，表头统计来自本地副本）|
|`busy`|`Boolean`|`false`|忙碌时禁用「取消」「确认填入」|

|emits|载荷|说明|
|---|---|---|
|`close`|—|点「取消」|
|`confirm`|`{username, studentId, name, cells: [{key, label, value}]}[]`|只含**已勾选**且 `value !== null` 的格子|
|`update:columnMap`|`(number\|null)[]`|列映射下拉变化时回传新数组|

界面元素与 `data-testid`：`recognize-preview`、`usable-count`、`filled-count`、`suspicious-count`、`column-map`、`preview-row-<i>`、`preview-username`、`confirm-fill`。行置信度徽标按 `>= 80` 绿、`>= 60` 琥珀、否则红；`cell.needsCheck` 的输入框加 `.cell-check` 高亮。

### frontend/src/components/VoicePanel.vue（语音录入面板）

源码：[frontend/src/components/VoicePanel.vue](../frontend/src/components/VoicePanel.vue)。语音与文本两条通道共用 `voice.js` 的解析逻辑；组件只产出结构化分数，父组件收到 `apply` 后才写入表单。

|props|类型|默认|说明|
|---|---|---|---|
|`open`|`Boolean`|`false`|为假时不渲染（关闭时停止识别并清空状态）|
|`components`|`Array`|`[]`|成绩项；经 `buildVoiceComponents` 规格化后用于解析|
|`rows`|`Array`|`[]`|**可录入的学生行**（`App.vue` 传整份课程名册 `rows`）：驱动「录入对象」下拉框与上一行/下一行按钮；行的稳定标识由内部 `rowKey(row) = username \|\| student_id \|\| id` 给出|
|`target`|`Object`|`null`|当前行（`{id, username, student_id, name}`），决定「当前行：…」与 `apply` 的目标|
|`rowDefaults`|`Object`|`{}`|该行已有分数；传给 `parseUtterance` 的 `defaults`，决定哪些列算「空位」|
|`active`|`Boolean`|`false`|麦克风监听状态（由父组件回传展示）|

|emits|载荷|说明|
|---|---|---|
|`close`|—|关闭面板|
|`apply`|`{target, cells: [{key, label, value}], utterance}`|「填入当前行」；只带解析出的（`mentioned`）项|
|`toggle-mic`|`Boolean`|开始/停止识别，父组件据此更新 `voiceActive`|
|`select-target`|`row`（`rows` 里的一整行）|**切换录入对象**：下拉框选中与上一行/下一行按钮两条可视化路径都只 `emit` 这一个事件，由 `App.vue` 的 `@select-target="voiceTargetRow = $event"` 写回目标行（成绩表行内麦克风按钮走 `App.vue` 的 `openVoice(row)`，不经本事件）|

界面元素与 `data-testid`：`voice-panel`、`voice-target-select`（录入对象下拉，选项文案 `第 N 行 · 学号 姓名`）、`voice-prev` / `voice-next`（上一行 / 下一行，越界禁用、不循环）、`voice-target`（`当前行：<学号 姓名>（第 i / N 行）`）、`voice-mic`、`voice-unsupported`、`voice-reset`、`voice-text`、`voice-interim`、`voice-error`、`voice-result`、`voice-value`、`voice-rejected`、`voice-unknown`、`voice-unmatched`、`voice-applied`、`voice-apply`。**命令行提示文案与 `.voice-command-hint` 样式已随语音口令切行一并删除**（语音与文本框通道都只解析分数，切行只走下拉框/按钮/行内麦克风按钮）。**面板内重复的「语音录入」标题与关闭图标、以及原先那条黄色隐私提示框都已按用户要求删除**（弹窗标题已有「语音录入」，底部仍保留「关闭」按钮）；隐私事实见 [ocr-voice-design.md](ocr-voice-design.md) 2 节与 7.4 节。组件另挂一个只读排障快照 `window.__voiceState`（`transcript`/`components`/`parsed`/`updates`/`target`/`rowCount`），供浏览器测试核对界面里真正生效的文本与解析结果。
