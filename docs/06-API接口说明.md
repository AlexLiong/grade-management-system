# 06 API 接口说明

本文档以当前源码中的 Controller、DTO、RMI 公共接口和服务端校验为准，说明 Web 前后端可依赖的 REST 契约，以及 Web 后端与独立数据服务之间的 RMI 契约。

## 1. REST 通用约定

### 1.1 基础路径与数据格式

- REST 基础路径为 `/api`。开发环境由 Vite 同源代理转发到 `http://localhost:8080`。
- JSON 属性名使用 lower camel case；时间字段为 ISO-8601 `Instant` 字符串；分数和权重使用 JSON number。
- 除 OCR 上传外，请求和响应均使用 `application/json; charset=UTF-8`。
- 客户端可发送 `X-Request-Id`。合法格式为 `[A-Za-z0-9._-]{8,64}`；缺失或不合法时服务端生成 UUID。最终值始终通过响应头 `X-Request-Id` 返回，并作为错误体的 `traceId`、审计/RMI 调用链标识。

成功响应统一为：

```json
{
  "success": true,
  "data": {},
  "timestamp": "2026-08-31T12:00:00Z"
}
```

`data` 为 `null` 时因非空序列化策略而省略。失败响应统一为：

```json
{
  "success": false,
  "error": {
    "code": "VALIDATION_FAILED",
    "message": "请求参数校验失败",
    "traceId": "2cb4a3f1-0f9f-4c66-a374-6a6a00f590bd",
    "fieldErrors": {
      "entries[0].examType": "不能为 null"
    }
  },
  "timestamp": "2026-08-31T12:00:00Z"
}
```

`fieldErrors` 为空时省略。分页数据统一为：

```json
{
  "items": [],
  "page": 0,
  "size": 20,
  "total": 0,
  "totalPages": 0
}
```

页码从 0 开始。各分页端点的 `size` 均限制在 1 至 100，具体默认值见端点表。

### 1.2 认证、CSRF 与授权

1. 先调用 `GET /api/auth/csrf`。Spring 写入可由 JavaScript 读取的 `XSRF-TOKEN` Cookie，同时响应 `{headerName,parameterName,token}`。
2. 所有 `POST`、`PUT`、`PATCH`、`DELETE` 请求都必须携带当前 CSRF 值，默认请求头为 `X-XSRF-TOKEN`。登录请求也受 CSRF 保护。
3. 登录成功后，JWT 仅写入 `AUTH_TOKEN` Cookie。该 Cookie 为 `HttpOnly; SameSite=Strict; Path=/`，默认有效期 2 小时；HTTPS 请求下同时带 `Secure`。系统不接受前端保存的 Bearer Token。登录和注销会显式轮换 CSRF Cookie，客户端随后重新调用 CSRF 接口获取与 Cookie 一致的新请求头值。
4. 每个已认证请求都会按 JWT 中的用户 ID重新读取账号状态、当前角色、角色权限和用户直接覆盖；账号不再为 `ACTIVE` 时会话立即失效。RMI 数据服务还会独立按当前数据库状态复核角色和权限。
5. `/api/teacher/**` 先要求 `TEACHER` 角色，`/api/student/**` 先要求 `STUDENT` 角色，`/api/admin/**` 先要求 `ADMIN` 角色；随后业务服务继续校验细粒度权限及资源归属。`/api/ocr/**` 要求已认证，并在业务层要求 `GRADE_DRAFT_WRITE`。
6. 公开入口仅有 `/api/auth/login`、`/api/auth/csrf` 和 `/actuator/health`。`/api/auth/me`、`/api/auth/logout` 及其他 `/api/**` 均要求登录。
7. CORS 只允许配置白名单来源，允许凭证；允许方法为 `GET, POST, PUT, PATCH, DELETE, OPTIONS`，允许请求头为 `Content-Type, X-XSRF-TOKEN, X-Request-Id, X-Requested-With`。

认证过滤器刷新账号与权限时，只有会话本身失效被规范化为 401；例如 RMI 不可达等非 401 故障保留原始状态（RMI 不可达为 `503 RMI_UNAVAILABLE`），不会被伪装成未登录。

有效权限按以下顺序计算：先合并全部角色默认权限，再应用用户直接覆盖。直接覆盖的 `true` 强制授予，`false` 强制撤销。

### 1.3 HTTP 错误语义

| HTTP 状态 | 典型错误码 | 含义 |
| --- | --- | --- |
| 400 | `BAD_REQUEST`, `VALIDATION_FAILED`, `RMI_IDEMPOTENCY_FINGERPRINT_INVALID` | JSON、查询参数、Bean Validation 或 RMI 幂等字段不合法 |
| 401 | `AUTHENTICATION_REQUIRED`, `INVALID_CREDENTIALS` | 未登录、Cookie 无效或登录失败 |
| 403 | `CSRF_INVALID`, `ACCESS_DENIED`, `PERMISSION_REQUIRED`, `RESOURCE_NOT_OWNED` | CSRF、角色、权限或资源归属校验失败 |
| 404 | `*_NOT_FOUND` | 目标资源不存在 |
| 409 | `OPTIMISTIC_LOCK_FAILED`, `*_STATUS_INVALID`, `RMI_IDEMPOTENCY_CONFLICT`, `GRADING_SCHEME_LOCKED_BY_SUBMITTED_GRADES` | 版本、状态机、幂等或已提交成绩冻结冲突 |
| 413 | `FILE_TOO_LARGE`, `OCR_IMAGE_TOO_LARGE` | 上传超限 |
| 415 | `OCR_IMAGE_TYPE_INVALID`, `OCR_IMAGE_SIGNATURE_INVALID` | OCR 文件类型不合法 |
| 422 | `INSUFFICIENT_RISK_SAMPLES`, `MODEL_TRAINING_FAILED` | 数据格式正确但无法完成模型分析 |
| 502 | `OCR_PROVIDER_FAILED`, `RMI_*` | 下游返回失败或拒绝请求 |
| 503 | `OCR_NOT_CONFIGURED`, `RMI_UNAVAILABLE` | 外部服务未配置或不可达 |
| 500 | `INTERNAL_ERROR` | 未分类服务端异常；响应不泄露堆栈 |

## 2. 认证接口

| 方法与路径 | 请求 | 返回 `data` | 访问条件 |
| --- | --- | --- | --- |
| `GET /api/auth/csrf` | 无 | `Csrf` | 公开 |
| `POST /api/auth/login` | `LoginRequest` | `CurrentUser`，并设置 `AUTH_TOKEN` | 公开，但要求 CSRF；按客户端地址与用户名限流 |
| `GET /api/auth/me` | 无 | `CurrentUser` | 已登录 |
| `POST /api/auth/logout` | 无 | 无 | 已登录；撤销当前 JWT 并清除 `AUTH_TOKEN` |

DTO：

- `LoginRequest = {username, password}`：二者非空；`username` 最长 64，`password` 最长 128。
- `CurrentUser = {id, username, displayName, organizationId, role, roles, permissions}`。`role` 按 `ADMIN > TEACHER > STUDENT` 选取稳定主角色；`roles` 和 `permissions` 是完整集合。
- `Csrf = {headerName, parameterName, token}`。

内部角色 `AUDITOR`、`GATEWAY`、`SYSTEM`、`ANALYTICS` 不是 Web 可交互身份，不能通过登录接口进入系统。

注销时服务端验证 Cookie 中的 JWT，将其 `jti` 记入拒绝表直到 token 原到期时间，因此被窃取的同一 token 也不能继续使用。当前拒绝表是单 JVM 内存存储；多实例生产部署必须换成 Redis 等共享且具有 TTL 的存储，否则注销不会跨实例生效。

## 3. 教师接口

所有教师接口均要求 `TEACHER` 路由角色。带 `offeringId` 的接口还会校验该开课班确由当前教师授课；写评分方案和改成绩只允许开课状态为 `OPEN`。

| 方法与路径 | 查询/请求体 | 返回 `data` | 细粒度权限 |
| --- | --- | --- | --- |
| `GET /api/teacher/courses` | `academicYear?`, `semester?` 仅 1/2，`className?`, `keyword?`, `page=0`, `size=20` | `PageResult<CourseView>` | `COURSE_READ` |
| `GET /api/teacher/history-courses` | `keyword?`, `academicYear?`, `semester?` 仅 1/2，`page=0`, `size=20` | `PageResult<HistoryCourseView>` | `GRADE_HISTORY_READ` |
| `GET /api/teacher/history-courses/{offeringId}` | 无 | `HistoryCourseView` | `GRADE_HISTORY_READ`；仅本人已结课开课班 |
| `GET /api/teacher/courses/{offeringId}/weights` | 无 | `WeightScheme` 或 `null` | `GRADE_READ` |
| `PUT /api/teacher/courses/{offeringId}/weights` | `SaveWeightsRequest` | 保存后的 `WeightScheme` | `GRADING_SCHEME_WRITE` |
| `GET /api/teacher/courses/{offeringId}/grade-sheet` | `status?`, `page=0`, `size=50` | `PageResult<GradeView>` | `GRADE_READ` |
| `POST /api/teacher/courses/{offeringId}/grades/draft` | `BatchDraftRequest` | 本批次更新后的 `GradeView[]` | `GRADE_DRAFT_WRITE` 和 `GRADE_READ`，两者均在写入前校验 |
| `POST /api/teacher/courses/{offeringId}/grades/submit` | `BatchActionRequest` | 无 | `GRADE_SUBMIT` |
| `POST /api/teacher/courses/{offeringId}/grades/withdraw` | `BatchActionRequest` | 无 | `GRADE_WITHDRAW` |
| `GET /api/teacher/courses/{offeringId}/statistics` | 无 | `Statistics` | `GRADE_ANALYTICS_READ` |
| `PUT /api/teacher/courses/{offeringId}/statistics` | `AnalysisNoteRequest` | `SavedAnalysis` | `GRADE_ANALYTICS_READ` |
| `GET /api/teacher/course-history` | `courseId`, `academicYear?`, `page=0`, `size=50` | `PageResult<CourseHistoricalGrade>` | `GRADE_HISTORY_READ` |
| `GET /api/teacher/courses/{offeringId}/history` | `gradeId?`, `page=0`, `size=20` | `PageResult<GradeHistoryView>` | `GRADE_HISTORY_READ` |
| `GET /api/teacher/courses/{offeringId}/risk/{studentId}` | 无 | `RiskAssessment` | `RISK_ANALYZE` |
| `POST /api/teacher/courses/{offeringId}/predictions` | `PredictionBatchRequest` | `PredictionBatchResult` | `RISK_ANALYZE` |

### 3.1 课程与动态评分方案

`CourseView`：

```text
{offeringId, courseId, courseCode, courseName, credit, academicYear,
 semester, className, status, enrolledStudents}
```

开课班 `status` 只以 `OPEN` 表示可写；`CLOSED` 及任何未知值均按不可写处理，不能由成绩行的 `DRAFT/SUBMITTED` 状态替代。

`HistoryCourseView = {offeringId, courseId, courseCode, courseName, academicYear, semester, className}`。历史目录只列出当前教师本人授课且 `status=CLOSED` 的开课班；`keyword` 对课程号和课程名称做不区分大小写的字面包含查询，`academicYear` 与 `semester` 可独立或组合使用。接口以零基页码返回真实 `total`/`totalPages`，`size` 为 1 至 100；数据库计数与分页查询使用完全相同的过滤条件，按学年、学期倒序及课程号、开课班 ID 升序稳定返回，不存在 500 条目录截断。`GET /api/teacher/history-courses/{offeringId}` 使用同一只读视图按“当前教师 + 开课班 ID”直接定位，因而详情页刷新或直达时不依赖目录当前页；目标不存在、未结课或不属于本人时返回 `404 HISTORY_COURSE_NOT_FOUND`。两类读取都使用独立的 `GRADE_HISTORY_READ`，不要求 `COURSE_READ` 或 `GRADE_READ`。

`WeightScheme`：

```text
{id, offeringId, name, totalWeight, version, status, items: WeightItem[]}
```

`WeightItem`：

```text
{id, itemCode, itemName, weight, maxScore, sortOrder}
```

评分项完全由方案的 `items` 动态决定，不存在固定的“平时/作业/实验/期中/期末”字段。`itemCode` 是加密成绩明文中的稳定契约键，必须是规范大写编码；例如 `LAB`、`PROJECT`、`DAILY` 与 `USUAL` 都是彼此独立的项目。

`SaveWeightsRequest` 的约束：

- `name` 非空，最长 64。
- `items` 为 1 至 20 项；`itemCode` 匹配 `[A-Z][A-Z0-9_]{0,31}`，`itemName` 非空且最长 64。
- 同一请求中的 `itemCode` 不得重复；`weight` 为 0.01 至 100，所有权重之和必须精确等于 100。
- 当前 REST DTO 要求每项 `maxScore` 精确等于 100；`sortOrder` 为 1 至 100，在请求数组中必须唯一且严格递增。
- `expectedVersion >= 0`。修改已有方案时必须等于当前版本；创建首个方案时该值不参与比较。
- 保存时服务端重建评分项并生成新的评分项 ID，客户端必须用响应中的 `items` 替换本地副本，不能假定请求里的 `id` 保持不变。
- 一旦当前方案已有任何持久化成绩（包括草稿），不得删除原有评分项或通过删除后新增的方式修改原有 `itemCode`；仍可新增评分项或调整名称、权重。违反时返回 `409 WEIGHT_ITEMS_LOCKED_BY_GRADES`。
- 一旦该方案已有任一 `SUBMITTED` 成绩，方案名称、全部评分项及权重整体冻结；Web 在发出写事务前明确返回 `409 GRADING_SCHEME_LOCKED_BY_SUBMITTED_GRADES`，RMI 数据服务仍做独立复核。

示例：

```json
{
  "name": "六项综合评分",
  "expectedVersion": 3,
  "items": [
    {"id": "w-1", "itemCode": "USUAL", "itemName": "平时表现", "weight": 10, "maxScore": 100, "sortOrder": 1},
    {"id": "w-2", "itemCode": "ATTENDANCE", "itemName": "考勤", "weight": 10, "maxScore": 100, "sortOrder": 2},
    {"id": "w-3", "itemCode": "HOMEWORK", "itemName": "作业", "weight": 10, "maxScore": 100, "sortOrder": 3},
    {"id": "w-4", "itemCode": "LAB", "itemName": "实验", "weight": 10, "maxScore": 100, "sortOrder": 4},
    {"id": "w-5", "itemCode": "MIDTERM", "itemName": "期中", "weight": 10, "maxScore": 100, "sortOrder": 5},
    {"id": "w-6", "itemCode": "FINAL", "itemName": "期末", "weight": 50, "maxScore": 100, "sortOrder": 6}
  ]
}
```

### 3.2 成绩草稿与返回模型

`BatchDraftRequest`：

```json
{
  "schemeId": "scheme-8",
  "idempotencyKey": "grade-draft-5dc107a1",
  "entries": [
    {
      "enrollmentId": "enrollment-17",
      "componentScores": {
        "USUAL": 86,
        "ATTENDANCE": 95,
        "HOMEWORK": 90,
        "LAB": 88,
        "MIDTERM": 79,
        "FINAL": 84
      },
      "makeupRawScore": null,
      "examType": "REGULAR",
      "expectedVersion": 2
    }
  ]
}
```

- `schemeId` 非空；`entries` 为 1 至 200 条，同批次 `enrollmentId` 不得重复且必须属于当前开课班。
- `idempotencyKey` 非空，最长 80；同一键只能用于完全相同的远程事务。
- `examType` 必须为 `REGULAR` 或 `RETAKE`；`expectedVersion >= 0`，新成绩使用 0。
- 正考的 `componentScores` 可只提交部分评分项，服务端与已有草稿按精确 `itemCode` 合并。未知项目被拒绝；值须为 0 至 100 且不得超过项目 `maxScore`。
- 只有合并后的键集合与方案项目集合完全相等时才计算 `regularScore`；公式为 `sum(componentScore / maxScore * weight)`，结果四舍五入为两位小数，否则 `regularScore = null`。
- 补考条目不使用请求中的 `componentScores`。通常必须提供 `makeupRawScore`；传 `null` 明确表示清空已保存的补考草稿，仅在正考已提交且低于 60 分、现有 `makeupStatus=DRAFT` 且草稿仍有卷面分时允许。没有已保存分数、已经清空或补考已提交时返回 409，且不会产生版本、历史或账本记录。

`GradeView`：

```text
{id, enrollmentId, studentId, studentNo, studentName, schemeId,
 componentScores, regularScore, makeupRawScore, makeupEffectiveScore,
 finalScore, score, examType, cappedAtSixty, makeupStatus, status,
 version, submittedAt, updatedAt}
```

没有持久化成绩的学生也会出现在成绩表中，此时 `id=null`、`componentScores={}`、`status=NOT_GRADED`、`version=0`。持久化正考状态为 `DRAFT` 或 `SUBMITTED`。`examType` 依据是否已有补考卷面分展示；`score` 与当前可见的 `finalScore` 相同。

### 3.3 正考与补考状态机

成绩行使用外层 `status` 表示正考状态，使用加密载荷内的 `makeupStatus` 表示补考状态：

| 操作 | 前置状态 | 后置状态 | 关键规则 |
| --- | --- | --- | --- |
| 暂存正考 | `NOT_GRADED` 或 `status=DRAFT` | `status=DRAFT`, `makeupStatus=null` | 允许部分动态评分项；每次版本加 1 |
| 提交正考 | `status=DRAFT` 且全部项目完整 | `status=SUBMITTED` | 记录提交人和时间 |
| 撤回正考 | `status=SUBMITTED`，且补考未提交 | `status=DRAFT` | 若存在补考草稿则完整清除；RMI 原因信封必须为 `REGULAR_WITHDRAW:<reason>` |
| 暂存补考 | `status=SUBMITTED`, `regularScore<60`, `makeupStatus=null/DRAFT` | 外层仍为 `SUBMITTED`, `makeupStatus=DRAFT` | 计算 `makeupEffectiveScore=min(makeupRawScore,60)` |
| 清空补考草稿 | `status=SUBMITTED`, `regularScore<60`, `makeupStatus=DRAFT` 且已有卷面分 | 外层仍为 `SUBMITTED`, `makeupStatus=DRAFT` | `makeupRawScore/makeupEffectiveScore=null`，`finalScore=regularScore`，版本加 1；使用 `MAKEUP_CLEAR` 信封和 `GRADE_MAKEUP_CLEAR` 历史/账本事件 |
| 提交补考 | `status=SUBMITTED`, `makeupStatus=DRAFT` | 外层仍为 `SUBMITTED`, `makeupStatus=SUBMITTED` | 卷面分、有效分和最终分在提交动作中不可被替换 |
| 撤回补考 | `status=SUBMITTED`, `makeupStatus=SUBMITTED` | 外层仍为 `SUBMITTED`, `makeupStatus=DRAFT` | 分数保留，可继续编辑后再提交 |

`BatchActionRequest = {gradeIds, examType, reason, idempotencyKey}`：`gradeIds` 为 1 至 200 个非空 ID；`reason` 长度 3 至 300；`idempotencyKey` 非空且最长 80。`examType=REGULAR` 操作正考状态，`RETAKE` 操作补考状态。

提交端点只接收已经通过暂存端点持久化的成绩 ID，不会隐式执行暂存，因此 `GRADE_SUBMIT` 不隐含 `GRADE_DRAFT_WRITE`；前端存在未暂存修改时会要求先暂存。

前端把暂存、提交和撤回都按最多 200 条拆成独立批次，每批使用一个不超过 80 字符的 UUID 幂等键。已完成批次会立即合并到本地状态；后续批次失败时保留已完成结果，用户重试只发送尚未完成的部分。浏览器网络错误、超时以及 `503 RMI_UNAVAILABLE` 都属于“远端可能已提交但响应丢失”的不确定结果，重试相同语义载荷时必须复用原键；收到确定的业务拒绝或成功响应后才释放该键。撤回在不确定失败后会先原样重放待确认批次，再刷新成绩表。

Web 为成绩草稿、提交、撤回、小范围撤销和高风险申请计算 SHA-256 语义指纹，并在状态、版本等易变化校验之前调用 RMI 幂等探针。同一主体、同一键、同一语义指纹已完成时直接重放成功结果；同键配不同语义指纹返回 `409 RMI_IDEMPOTENCY_CONFLICT`。草稿重放仍按本次 `entries` 顺序返回同样数量的 `GradeView`，避免客户端批次错位。

可见最终分规则：补考只有在 `makeupStatus=SUBMITTED` 后才对教师统计和学生展示生效；否则仍显示 `regularScore`。补考有效分始终为 `min(卷面分, 60)`，`cappedAtSixty` 表示卷面分大于 60。清空后保留空的 `DRAFT` 状态以便继续编辑，但空草稿不能提交。正考已及格时禁止补考；补考已提交时必须先撤回补考，才能修改或清空补考以及撤回正考。

### 3.4 统计、历史与风险分析 DTO

- `Statistics = {count, average, maximum, minimum, median, passRate, standardDeviation, distribution, narrative, savedAnalysis}`。统计只读取已提交成绩及当前可见最终分；无成绩时数值为 0。`savedAnalysis` 是最近一次保存的教师分析，可在没有已提交成绩时独立读回。
- `AnalysisNoteRequest = {analysis}`，非空且最长 3000。`SavedAnalysis = {id, offeringId, statistics, analysis, updatedBy, updatedAt}`。
- `CourseHistoricalGrade = {academicYear, semester, offeringId, studentId, studentNo, studentName, regularScore, makeupRawScore, makeupEffectiveScore, finalScore}`。`GET /api/teacher/course-history` 从只读视图 `teacher_course_historical_grades` 读取当前教师指定课程、状态为 `CLOSED` 的历史开课班及其 `SUBMITTED` 成绩；`academicYear` 为空时覆盖该课程全部历史学年。接口先以完全相同的 `courseId + 当前 teacherId + 可选 academicYear` 条件执行数据库 `COUNT`，再按学年、学期倒序以及开课班、学号、成绩 ID 升序稳定分页，只校验并解密当前页，因此 `total`/`totalPages` 精确且不存在先取 500 条造成的详情截断。Web 请求固定加入当前教师过滤，RMI 再核对签名主体的教师身份与所有权，不能读取其他教师的数据；它只要求 `GRADE_HISTORY_READ`，内部读取不会追加 `COURSE_READ` 或 `GRADE_READ`。
- `GradeHistoryView = {id, gradeId, action, reason, scope, batchId, actorId, createdAt}`。路径中的开课班必须由当前教师授课且为 `CLOSED`，可查询的 `gradeId` 还必须是该班的 `SUBMITTED` 成绩；历史只返回元数据，不返回原始密文或明文。
- `PredictionBatchRequest = {students: PredictionInput[]}`，1 至 200 条；`PredictionInput = {studentId, usualScore, labScore}`，两个分数均为 0 至 100。
- `RiskAssessment = {studentId, courseId, sampleYears, predictedScore, failureProbability, level, linearRegressionExplanation, decisionPath, generatedAt}`。
- `PredictionBatchResult = {regression, decisionTree, predictions, generatedAt, persisted}`；当前 `persisted=false`，表示学生 ID、精确预测结果及其派生预警均不持久化。训练至少需要同一课程 6 条有效的平时/实验/期末样本，并覆盖至少 3 个学年。预测期末分比训练样本期末均值低至少 10 分时，`decisionPath` 追加本次响应内的风险提示，不写成绩、分析或告警表。

提交后的规则告警采用明确边界：当前班均值与同课程历史结课班均值绝对偏移至少 10 分时生成班级告警；同一成绩记录与上一不同历史版本相差超过 20 分时生成版本波动告警；本次成绩与当前教师本人已结课授课班中该学生的任一已提交历史成绩相差超过 20 分时生成个人历史波动告警，不越权读取其他教师的数据；已提交补考卷面分低于 30 或高于 90 时生成复核提示，30 和 90 本身不触发。

## 4. 学生接口

所有学生接口均要求 `STUDENT` 路由角色。成绩、排名和风险结果只允许访问当前账号对应的学生档案或本人选课记录。

| 方法与路径 | 查询 | 返回 `data` | 细粒度权限 |
| --- | --- | --- | --- |
| `GET /api/student/grades` | `scope=current|all`，默认 `current` | `StudentOverview` | `GRADE_SELF_READ` |
| `GET /api/student/overview` | 无，等价于 `scope=current` | `StudentOverview` | `GRADE_SELF_READ` |
| `GET /api/student/ranking` | 必填 `offeringId` | `Ranking` | `GRADE_SELF_READ`，且本人已选课并已有提交成绩 |
| `GET /api/student/warnings` | 无 | `FailureWarning[]` | `GRADE_SELF_READ` |
| `GET /api/student/courses` | 无 | `StudentCourseView[]` | `GRADE_SELF_READ` |
| `GET /api/student/risk` | 必填 `courseId`, `usualScore`, `labScore`，分数 0 至 100 | `RiskAssessment` | `RISK_SELF_ANALYZE` 和 `GRADE_SELF_READ`，且本人必须曾选修该课程 |

DTO：

- `StudentOverview = {grades, weightedAverage, earnedCredits, failedCourses}`。
- `StudentGradeView = {gradeId, offeringId, courseId, courseCode, courseName, credit, academicYear, semester, regularScore, makeupRawScore, makeupEffectiveScore, finalScore, score, cappedAtSixty, status}`。
- `StudentCourseView = {id, code, name}`。`id` 是课程 ID，不是开课班 ID；只列出本人至少选修过一次的课程，按课程编码和 ID 排序并去重。
- `Ranking = {offeringId, rank, participants, percentile, score}`，不返回任何同学身份。
- `FailureWarning = {offeringId, courseCode, courseName, score, message}`。

学生只看到 `status=SUBMITTED` 的正考。补考草稿不向学生暴露：只有 `makeupStatus=SUBMITTED` 时补考三个分数字段及其最终分才出现在学生响应中。

## 5. 管理员接口

所有管理员接口均要求 `ADMIN` 路由角色，再按下表检查细粒度权限。

### 5.1 用户、档案、组织和权限

| 方法与路径 | 查询/请求体 | 返回 `data` | 权限 |
| --- | --- | --- | --- |
| `GET /api/admin/users` | `keyword?`, `status?`, `role?`, `organizationId?`, `page=0`, `size=20` | `PageResult<UserView>` | `USER_MANAGE` |
| `POST /api/admin/users` | `CreateUserRequest` | `UserView` | `USER_MANAGE` + `PERMISSION_MANAGE`；创建教师还需 `ORG_MANAGE` |
| `PUT /api/admin/users/{id}` | `UpdateUserRequest` | `UserView` | `USER_MANAGE`；角色变更或目标为 `ADMIN` 还需 `PERMISSION_MANAGE`；组织实际变更还需 `ORG_MANAGE` |
| `DELETE /api/admin/users/{id}` | 无 | 无 | `USER_MANAGE`；目标为 `ADMIN` 还需 `PERMISSION_MANAGE` |
| `GET /api/admin/organizations` | 无 | `OrganizationView[]` | `ORG_MANAGE` |
| `POST /api/admin/organizations` | `OrganizationRequest` | `OrganizationView` | `ORG_MANAGE` |
| `PUT /api/admin/organizations/{id}` | `OrganizationRequest` | `OrganizationView` | `ORG_MANAGE` |
| `DELETE /api/admin/organizations/{id}` | 无 | 无 | `ORG_MANAGE` |
| `GET /api/admin/roles` | 无 | `RolePermissions[]` | `PERMISSION_MANAGE` |
| `GET /api/admin/permissions` | 无 | `PermissionView[]` | `PERMISSION_MANAGE` |
| `PUT /api/admin/roles/{id}/permissions` | `{permissions:[...]}` | `RolePermissions` | `PERMISSION_MANAGE` |
| `PUT /api/admin/users/{id}/permissions` | `UserPermissionOverrides` | 同请求 | `PERMISSION_MANAGE` |

`UserView`：

```text
{id, username, displayName, email, status, roles,
 studentId, studentNo, teacherId, teacherNo,
 organizationId, organizationName, className, major,
 permissionOverrides, createdAt, updatedAt}
```

仅有 `USER_MANAGE` 时仍可列出用户和其唯一交互角色；若缺少 `ORG_MANAGE`，则保留教师 `organizationId` 但不额外查询/`organizationName`；若缺少 `PERMISSION_MANAGE`，则不查询个人覆盖并返回空 `permissionOverrides`。

`CreateUserRequest`：

```text
{username, password, displayName, email?, roleCodes,
 studentNo?, teacherNo?, organizationId?, className?, major?, permissionOverrides?}
```

- `username` 匹配 `[A-Za-z0-9._-]{3,32}`；密码 8 至 128；显示名非空且最长 80；邮箱最长 120 且格式合法。
- `roleCodes` 必须恰好包含 `ADMIN`、`TEACHER`、`STUDENT` 之一。交互账号不允许多角色；历史数据中的多角色账号会被拒绝登录和管理更新。
- 含 `STUDENT` 时必须提供学号，服务端同时建立学生档案，默认性别 `UNKNOWN`、入学年为当前年。
- 含 `TEACHER` 时必须提供工号和有效 `organizationId`，服务端同时建立教师档案，默认职称 `LECTURER`。

`UpdateUserRequest = {displayName, email?, status, roleCodes, newPassword?, organizationId?, className?, major?, permissionOverrides?}`。`status` 只能为 `ACTIVE|DISABLED|LOCKED`；只有 `ACTIVE` 可登录，`DISABLED` 表示停用，`LOCKED` 表示锁定。新密码不为空时长度 8 至 128；组织 ID 最长 64，班级和专业各最长 80。提供 `permissionOverrides` 时按全量替换处理，并与用户、档案及角色变更写入同一 RMI 事务，任一命令失败则全部回滚，避免 `DENY` 覆盖失败后留下过权账号。更新会同步学生/教师档案姓名；学生的 `className`、`major` 可修改，字段为 `null` 时保持原值、空字符串时清空；教师组织可修改。学号 `studentNo` 和工号 `teacherNo` 不在更新 DTO 中，创建后不可由该接口修改。已有学生/教师档案对应的 `STUDENT`/`TEACHER` 角色不可直接增加或删除，否则返回 `PROFILE_ROLE_CHANGE_REQUIRES_MIGRATION`。

`DELETE /users/{id}` 是软禁用，不物理删除用户。不能禁用当前登录账号，也不能移除自身 `ADMIN` 角色；对现有 `ADMIN` 的任何更新（包括密码重置）或禁用都额外要求 `PERMISSION_MANAGE`。内部服务/审计身份不能通过用户管理 API 查询、修改、禁用或分配。

`OrganizationView = {id, code, name, parentId}`；`OrganizationRequest = {code, name, parentId?}`。组织编码匹配 `[A-Z0-9_-]{2,24}`，名称非空且最长 100。服务端会沿父链检查直接或间接循环；存在子组织、教师或课程引用时禁止删除。

`RolePermissions = {roleId, roleCode, permissions}`。`GET /roles` 只列出三个可交互角色；`GET /permissions` 返回完整 `{code,name}` 权限目录。角色权限 `PUT` 是全量替换；`permissions` 字段必须存在且不可为 `null`，但允许空集合以撤销全部默认权限。内部角色不可修改。

用户直接权限覆盖采用三态语义：

| `permissionOverrides` 中的状态 | 有效含义 |
| --- | --- |
| 不包含该权限码 | 继承全部角色合并后的默认值 |
| `"CODE": true` | 无论角色默认值如何，直接授予 |
| `"CODE": false` | 无论角色默认值如何，直接撤销 |

`UserPermissionOverrides = {overrides: {permissionCode: boolean}}`。每次 `PUT` 都替换该用户的全部直接覆盖；传 `{"overrides":{}}` 可清空覆盖并恢复继承。`UserView.permissionOverrides` 只返回显式覆盖，不返回计算后的权限集合。内部服务/审计身份的覆盖不可通过 Web 修改。

保存角色权限或会改变有效权限的用户覆盖时，服务端强制以下依赖，缺失返回 `400 PERMISSION_DEPENDENCY_REQUIRED`：`GRADING_SCHEME_WRITE`、`GRADE_DRAFT_WRITE`、`GRADE_SUBMIT`、`GRADE_WITHDRAW`、`GRADE_ANALYTICS_READ` 均依赖 `COURSE_READ + GRADE_READ`；`RISK_ANALYZE` 还依赖 `GRADE_ANALYTICS_READ`；`RISK_SELF_ANALYZE` 依赖 `GRADE_SELF_READ`；`GRADE_REVERT_SMALL` 依赖 `GRADE_READ`。`GRADE_HISTORY_READ` 是刻意独立的历史权限，不依赖 `COURSE_READ` 或 `GRADE_READ`。

权限目录：

```text
COURSE_READ, GRADE_READ, GRADING_SCHEME_WRITE, GRADE_DRAFT_WRITE,
GRADE_SUBMIT, GRADE_WITHDRAW, GRADE_ANALYTICS_READ, GRADE_HISTORY_READ,
RISK_ANALYZE, GRADE_SELF_READ, RISK_SELF_ANALYZE,
USER_MANAGE, ORG_MANAGE, PERMISSION_MANAGE,
GRADE_REVERT_SMALL, GRADE_REVERT_REQUEST, GRADE_REVERT_APPROVE,
GRADE_RESTORE_ORIGINAL, AUDIT_READ, INTEGRITY_VERIFY, ALERT_MANAGE
```

### 5.2 审计、安全与恢复端点

| 方法与路径 | 查询/请求体 | 返回 `data` | 权限 |
| --- | --- | --- | --- |
| `GET /api/admin/audit-logs` | `actor?`, `operation?`, `from?`, `to?`, `page=0`, `size=20` | `PageResult<AuditLogView>` | `AUDIT_READ` |
| `GET /api/admin/alerts` | `status?`, `severity?`, `page=0`, `size=20` | `PageResult<AlertView>` | `ALERT_MANAGE` |
| `PATCH /api/admin/alerts/{id}/resolve` | 无 | `AlertView` | `ALERT_MANAGE` |
| `POST /api/admin/integrity/verify` | 无 | `IntegrityView` | `INTEGRITY_VERIFY` |
| `GET /api/admin/grades` | `course?`, `student?`, `status?`, `page=0`, `size=20` | `PageResult<AdminGradeView>` | `GRADE_READ` |
| `GET /api/admin/recovery-evidence` | `gradeId`, `limit=20`，1 至 100 | `RecoveryEvidence[]` | `GRADE_RESTORE_ORIGINAL` |
| `POST /api/admin/recovery-evidence/{sequence}/preview` | `{reason}` | `RecoverySnapshotView` | `GRADE_RESTORE_ORIGINAL` |
| `POST /api/admin/grades/revert-small` | `SmallReversionRequest` | 无 | `GRADE_REVERT_SMALL` 和 `GRADE_READ` |
| `GET /api/admin/reversion-requests` | `status?`, `page=0`, `size=20` | `PageResult<ReversionView>` | `GRADE_REVERT_REQUEST` 或 `GRADE_REVERT_APPROVE` |
| `GET /api/admin/reversion-requests/{id}` | 无 | `ReversionView` | `GRADE_REVERT_REQUEST` 或 `GRADE_REVERT_APPROVE`；用于写请求结果不明确时按 ID 对账 |
| `POST /api/admin/reversion-requests` | `CreateReversionRequest` | `ReversionView` | `GRADE_REVERT_REQUEST`；原始恢复还需 `GRADE_RESTORE_ORIGINAL` |
| `POST /api/admin/reversion-requests/{id}/approve` | `{comment}` | `ReversionView` | `GRADE_REVERT_APPROVE`；执行原始恢复时还需 `GRADE_RESTORE_ORIGINAL` |
| `POST /api/admin/reversion-requests/{id}/reject` | `{comment}` | `ReversionView` | `GRADE_REVERT_APPROVE` |

安全 DTO：

- `AuditLogView = {id, requestId, actor, operation, tableName, recordKey, success, detail, createdAt}`。查询参数 `actor` 为操作人账号精确匹配；`operation` 为动作模糊匹配。
- `AlertView = {id, type, severity, message, status, relatedTable, relatedId, createdAt, resolvedAt}`。告警状态仅为 `OPEN` 或 `RESOLVED`；处置端点只将 `OPEN` 变更为 `RESOLVED`，不存在 `REVIEWED` 中间态。
- `IntegrityView = {valid, checkedEntries, firstInvalidSequence, message}`。
- `AdminGradeView = {id, enrollmentId, status, version, studentId, studentNo, studentName, offeringId, courseId, courseCode, courseName, academicYear, semester, className, submittedAt, updatedAt}`。目录只返回撤销定位所需元数据，不返回或解密成绩分数。`course` 对课程编码/名称、`student` 对学号/姓名做模糊匹配；`status` 为空或精确为 `DRAFT|SUBMITTED`，其他值返回 `400 GRADE_STATUS_INVALID`。任一模糊查询匹配超过 500 条时返回 `400 GRADE_LOOKUP_TOO_BROAD`，要求缩小条件。
- `RecoveryEvidence = {sequence, eventType, aggregateType, aggregateId, previousHash, entryHash, createdAt, actor}`。REST 刻意不返回 RMI `RecoveryRecord.encryptedSnapshot`。
- `RecoverySnapshotView = {sequence, eventType, aggregateId, originalValues, createdAt}`。预览请求 `reason` 非空且最长 300，并写审计日志。

### 5.3 小范围撤销

`SmallReversionRequest = {gradeIds, reason, idempotencyKey}`：`gradeIds` 为 1 至 10 条，`reason` 长度 5 至 500，幂等键最长 80。所有目标必须存在且处于 `SUBMITTED`。操作将正考退回 `DRAFT`、版本加 1，并清除整套补考字段；RMI 写命令使用精确原因信封 `ADMIN_SMALL_REVERSION:<reason>`。该路径不经过双人审批，但仍写不可变历史、外部完整性账本和审计日志。

### 5.4 高风险撤销与原始恢复协议

`CreateReversionRequest = {targetFilter, scope, reason, idempotencyKey}`。`targetFilter` 最长 40000，`reason` 长度 5 至 500，`idempotencyKey` 非空且最长 80。DTO 枚举含 `SMALL_BATCH|LARGE_BATCH|ORIGINAL_RESTORE`，但创建端点明确拒绝 `SMALL_BATCH`，小范围操作必须走上一节的直接接口。服务端用“发起人 + 幂等键”生成确定性申请 ID；相同语义请求重放会返回同一 `ReversionView`，不会创建第二张申请。

大范围物理删除的输入格式：

```json
{
  "scope": "LARGE_BATCH",
  "targetFilter": "gradeIds:g-2,g-1,g-2",
  "reason": "批次导入错误，需要删除后重新录入",
  "idempotencyKey": "2cb4a3f1-0f9f-4c66-a374-6a6a00f590bd"
}
```

服务端先对每个 ID 去空白并逐个校验 `[A-Za-z0-9_-]{1,64}`；任何空或非法 token 都使整个请求返回 `400 TARGET_FILTER_INVALID`，不会静默缩小目标。全部通过后才去重、排序，并要求 1 至 500 个。数据库保存 `CanonicalForms.collection([Filter(id, IN, sortedGradeIds)])`。以上示例的规范值为：

```text
1[21:2:id2:IN2[3:g-13:g-2]]
```

该规范串是服务端与 RMI 的防篡改匹配值，客户端应把响应中的 `targetFilter` 当作不透明字符串，不应自行生成或修改。

原始恢复的输入格式：

```json
{
  "scope": "ORIGINAL_RESTORE",
  "targetFilter": "RECOVERY:42",
  "reason": "依据账本证据恢复原始加密成绩",
  "idempotencyKey": "cb14b84c-8532-4dd1-95df-0803807c4f38"
}
```

创建时兼容 `ledgerSequence:42` 和 `sequence:42`，但持久化和后续 RMI 校验的唯一规范格式始终为 `RECOVERY:<正整数>`。

两种高风险请求在数据库中的 `scope` 都必须是精确值 `LARGE`。REST `ReversionView.scope` 根据规范目标还原为 `LARGE_BATCH` 或 `ORIGINAL_RESTORE`。`ReversionView` 字段为：

这里的 `LARGE` 是审批请求协议值；原始恢复实际写入 `grade_history` 的恢复前、恢复后证据使用 `scope=ORIGINAL_RESTORE`，不会伪装成小撤销 `SMALL`。

```text
{id, requestNo, scope, targetFilter, reason, status,
 requestedBy, requestedAt, approvedAt, executedAt,
 reviewer, reviewComment, reviewedAt}
```

`reviewer`、`reviewComment`、`reviewedAt` 来自最近一条 `high_risk_approvals` 复核记录，尚未复核时均为 `null`；批准和驳回都会返回这三个字段。前端领域模型将 `reviewComment` 映射为 `comment`。

状态机和严格协议如下：

```text
PENDING --reject--> REJECTED
PENDING --approve--> APPROVED --RMI 原子执行并消费审批--> EXECUTED
                              \--执行失败，保留 APPROVED，可再次调用 approve 重试
```

1. 发起人与审批人必须是不同用户名；自己审批或驳回自己的请求会被拒绝。
2. 审批先在一个事务中写入 `high_risk_approvals(decision=APPROVED)`，并把请求从 `PENDING` 改为 `APPROVED`。
3. 调用 RMI 时，`MutationCommand.approvalId` 或 `RecoveryRestoreRequest.approvalId` 必须传 **撤销申请 `reversion_requests.id`**，也就是 REST 路径中的请求 ID；不能传 `high_risk_approvals.id`。
4. RMI 从数据库重新验证 `scope=LARGE`、请求仍为 `APPROVED`、存在 `decision=APPROVED`、发起人与审批人不同，并要求保存的规范目标与实际 `Filter` 或 `RECOVERY:<sequence>` 完全相等。
5. 大范围撤销执行的是 `grades` 物理删除；原始恢复从指定账本序列读取并认证快照，目标已存在则覆盖，不存在则插入。
6. 实际成绩操作、历史/账本写入以及 `reversion_requests.status='EXECUTED'`、`executed_at` 标记由 RMI 放在同一数据库事务中完成。Web 层不自行伪造 `EXECUTED`。
7. `APPROVED` 状态再次调用 approve 会重试远程执行；已经 `REJECTED` 或 `EXECUTED` 的请求不可再复核。`ReviewRequest.comment` 长度 2 至 300。

## 6. OCR 接口

`POST /api/ocr/recognize` 使用 `multipart/form-data`，文件字段名必须为 `image`，返回：

```json
{
  "success": true,
  "data": {
    "text": "20230001 86",
    "provider": "ocr.example.com",
    "requestId": "2cb4a3f1-0f9f-4c66-a374-6a6a00f590bd"
  },
  "timestamp": "2026-08-31T12:00:00Z"
}
```

- 要求已登录且具有 `GRADE_DRAFT_WRITE`。
- 默认最大 5 MiB；仅接受 `image/jpeg`、`image/png`、`image/webp`，同时校验 MIME 与 JPEG/PNG/RIFF-WebP 魔数。
- 必须配置带主机名的 HTTPS `OCR_API_URL` 和非空 `OCR_API_TOKEN`；HTTP、相对 URL 或无主机 URL 均按未配置处理并返回 `503 OCR_NOT_CONFIGURED`。
- `OCR_TIMEOUT` 默认为 `PT10S`，同时作为连接超时和读取超时；零值或负值回退到默认值。
- Web 后端以原始图片字节同步调用供应商，请求头为 `Authorization: Bearer <token>`，并从供应商响应的 `text`、`result` 或 `data.text` 提取文本。
- 图片字节只保存在内存，供应商调用结束后立即覆写为零；Servlet multipart 的 `file-size-threshold=6MB` 高于 `max-file-size=5MB`，因此所有可接受图片均不会因阈值转存临时文件。供应商无文本返回 `OCR_RESPONSE_INVALID`，调用失败返回 `OCR_PROVIDER_FAILED`。
- `Recognition = {text, provider, requestId}`；`provider` 为 OCR URL 的主机名，`requestId` 为当前 REST 链路 ID。

## 7. RMI 公共契约

RMI 只供 Web 后端及受限内部身份调用，不是浏览器 API。公共 Java 类型位于 `rmi-contract` 模块。

### 7.1 注册表与传输

| 绑定名 | 接口 | 默认端口 |
| --- | --- | --- |
| `GradeSelectService` | `SelectInterface` | Registry `1199`，Service `1200` |
| `GradeManipulationService` | `ManipulationInterface` | 同上 |
| `GradeHealthService` | `HealthInterface` | 同上 |
| `GradeIntegrityService` | `IntegrityInterface` | 同上 |

默认只绑定 `127.0.0.1`。生产配置要求 RMI TLS；TLS 打开时默认要求客户端证书，客户端同时使用 HTTPS 主机名校验算法验证服务端证书 SAN/CN 与 RMI 主机一致。反序列化过滤器限制最大深度 24、最大引用数 10000、最大字节数 1 MiB，并只允许 JDK 基础类型及 `edu.chd.practice.rmi.contract.**`。

### 7.2 远程方法

除健康检查外，每次调用都传 `InvocationContext`，并可能抛出 `RemoteException` 或 `RemoteServiceException`。

| 接口/操作常量 | Java 方法 | 返回语义 |
| --- | --- | --- |
| `HealthInterface` | `HealthStatus check()` | 不带签名；数据库 `SELECT 1` 成功则 `healthy=true`，失败也返回状态对象而非业务异常 |
| `SelectInterface.OP_SELECT = select.select` | `String[][] select(SelectRequest, InvocationContext)` | 行列矩阵；每行列顺序严格等于请求 `columns`，数据库 `NULL` 映射为 Java `null` |
| `SelectInterface.OP_COUNT = select.count` | `long count(SelectRequest, InvocationContext)` | 只使用表和过滤器，返回匹配数量 |
| `ManipulationInterface.OP_EXECUTE = manipulation.execute` | `boolean execute(MutationCommand, InvocationContext)` | 单命令原子执行；成功或命中相同幂等结果返回 `true` |
| `ManipulationInterface.OP_TRANSACTION = manipulation.transaction` | `boolean executeTransaction(TransactionRequest, InvocationContext)` | 1 至配置上限条命令，默认上限 200；全部提交才返回 `true` |
| `ManipulationInterface.OP_IDEMPOTENCY_PROBE = manipulation.transaction.idempotency-probe` | `boolean transactionCompleted(IdempotencyProbe, InvocationContext)` | 相同主体、键和语义指纹已有成功事务时返回 `true`；不存在时返回 `false`，同键异指纹返回冲突 |
| `IntegrityInterface.OP_VERIFY = integrity.verify` | `IntegrityReport verifyLedger(InvocationContext)` | 验证外部 HMAC 哈希链 |
| `IntegrityInterface.OP_RECOVERY = integrity.recovery` | `RecoveryRecord[] readRecoveryEvidence(RecoveryQuery, InvocationContext)` | 按时间倒序返回指定成绩的 1 至 100 条证据 |
| `IntegrityInterface.OP_DECRYPT_RECOVERY = integrity.decrypt-recovery` | `RecoverySnapshot decryptRecoveryEvidence(RecoveryEvidenceRequest, InvocationContext)` | 受控解密一个账本快照 |
| `IntegrityInterface.OP_RESTORE = integrity.restore` | `boolean restoreGrade(RecoveryRestoreRequest, InvocationContext)` | 双人审批校验通过后原子恢复，成功返回 `true` |

### 7.3 调用认证与规范签名

`InvocationContext` 字段：

```text
{requestId, principal, roles, timestampEpochMillis, nonce, signature}
```

- `requestId`、`principal`、`nonce` 必须匹配 `[A-Za-z0-9_.:@/-]{1,128}`；`roles` 最多 16 个并在构造时排序。
- Web RMI 客户端以 `SHA-256(HTTP traceId + operation + payload.canonicalForm())` 的 64 位小写十六进制值作为 `InvocationContext.requestId`。同一 HTTP 链路内不同审计命令因此拥有不同的 `execute` 幂等键，同一命令重放仍得到稳定键；REST 审计记录的 `request_id` 继续保存原始 HTTP traceId。
- 签名算法为 HMAC-SHA-256，共享密钥至少 32 字节。签名内容为 `operation + payload.canonicalForm() + context.canonicalIdentity()` 的长度前缀规范串；`signature` 本身不参与被签名身份。
- 时间戳默认只能偏离 RMI 服务端时间正负 300 秒。
- nonce 按 `principal` 防重放。查询、完整性与恢复调用遇到已用 nonce 一律返回 `AUTH_REPLAY`；写调用只有在相同幂等结果已经持久化时才可返回缓存结果，否则也返回 `AUTH_REPLAY`。
- RMI 不信任调用方单独声明的角色。服务端取签名角色与数据库中该 `ACTIVE` 用户当前角色的交集，并再次读取角色权限和三态直接覆盖。

规范编码规则由 `CanonicalForms` 固定：普通值编码为 `<UTF-16长度>:<文本>`，`null` 为 `-1:`；集合保留顺序并编码为 `<数量>[...]`；Map 先按键排序再编码。所有请求 DTO 的 `canonicalForm()` 字段顺序由其源码固定，双方必须使用 `rmi-contract` 实现，不能自行拼接。

### 7.4 请求值对象

| 类型 | 字段与约束 |
| --- | --- |
| `SelectRequest` | `{table, columns, filters, sorts, page, pageSize}`；`page>=0`，`pageSize` 默认上限 500；空 `columns` 表示表的全部白名单列 |
| `Filter` | `{column, operator, values}`；最多 30 个过滤器；`IN` 为 1 至 500 个值 |
| `FilterOperator` | `EQ, NE, GT, GE, LT, LE, LIKE, CONTAINS, IN, BETWEEN, IS_NULL, IS_NOT_NULL`；`CONTAINS` 只适用于字符串列，并把 `%`、`_` 和转义字符按普通文本处理 |
| `Sort` / `SortDirection` | `{column,direction}`；方向为 `ASC|DESC`；未指定排序时按 `id` 或第一白名单列升序 |
| `MutationCommand` | `{type, table, values, filters, reason, approvalId}`；Map 按键排序为不可变副本 |
| `MutationType` | `INSERT, UPDATE, DELETE`；`INSERT` 要求非空 values，`UPDATE/DELETE` 要求至少一个过滤器，`DELETE` 禁止 values |
| `TransactionRequest` | `{idempotencyKey, semanticFingerprint, commands}`；键不能为空且服务端限制 1 至 128 字符；`semanticFingerprint` 可选，但语义重放事务使用 1 至 128 字符的 SHA-256 指纹；命令数默认 1 至 200 |
| `IdempotencyProbe` | `{idempotencyKey, semanticFingerprint}`；两者均不能为空且为 1 至 128 字符，探测的是 `OP_TRANSACTION` 的完成记录 |
| `RecoveryQuery` | `{aggregateType, aggregateId, limit}`；当前只允许 `aggregateType=GRADE`，limit 为 1 至 100 |
| `RecoveryEvidenceRequest` | `{sequence, reason}`；reason 非空且最长 1024 |
| `RecoveryRestoreRequest` | `{sequence, reason, approvalId}`；`approvalId` 必须是已批准的 `reversion_requests.id` |

RMI 查询和写入不是任意 SQL。`table`、只读视图、列和类型必须在 `SchemaRegistry` 白名单中；值通过参数化 SQL 转换，角色权限、教师/学生资源归属、评分方案不可变性、成绩状态信封和乐观版本还会在数据服务中复核。`teacher_history_courses` 是严格只读且只注册给教师角色的已结课课程视图；RMI 授权仍强制请求携带与签名主体一致的 `teacher_id`，不能由浏览器指定教师身份。

### 7.5 返回值对象与加密成绩

| 类型 | 字段 |
| --- | --- |
| `HealthStatus` | `{healthy, database, serviceVersion, serverTimeEpochMillis}` |
| `IntegrityReport` | `{valid, checkedEntries, firstInvalidSequence, message}` |
| `RecoveryRecord` | `{sequence, eventType, aggregateType, aggregateId, encryptedSnapshot, previousHash, entryHash, createdAtEpochMillis, actor}` |
| `RecoverySnapshot` | `{sequence, eventType, aggregateId, originalValues, createdAtEpochMillis}`，`originalValues` 按键排序且不可变 |
| `EncryptedGradePayload` | `{ciphertext, nonce, integrity}` |

成绩明文是包含 `componentScores, regularScore, makeupRawScore, makeupEffectiveScore, finalScore, makeupStatus` 的 JSON，但数据库和普通 RMI 表查询只保存/返回加密三元组。加密格式为 AES-256-GCM，`gradeId` 作为 AAD，12 字节 nonce 使用无填充 Base64URL；`ciphertext` 前缀为 `v1.`。`integrity` 是对 `gradeId + nonce + ciphertext` 的独立 HMAC-SHA-256。解密前必须先验证完整性，篡改、错误 gradeId 或未知版本均失败。

### 7.6 幂等、事务与失败语义

- `execute` 的幂等键为 `InvocationContext.requestId`；`executeTransaction` 使用 `TransactionRequest.idempotencyKey`。数据库实际以 `principal + key` 的 SHA-256 作为作用域。
- `semanticFingerprint` 为空时，事务请求哈希覆盖完整 `TransactionRequest.canonicalForm()`；提供指纹时，请求哈希只绑定操作、主体和该语义指纹，使服务端生成的时间戳、密文 nonce、命令 ID 等实现细节变化不影响重放判断。
- 同一 principal、同一键、同一操作和同一规范载荷或语义指纹返回已保存的 boolean；同一键用于不同请求返回 `IDEMPOTENCY_CONFLICT`。事务开始时先在同一数据库事务内预留唯一键，完成时标记成功，并发同键调用由唯一约束串行化，避免“先探测再写入”的竞态。
- 远程事务中任一命令失败会回滚完整事务，并返回 `TRANSACTION_ROLLED_BACK` 或更具体的业务码。更新/删除没有影响任何行返回 `NO_ROWS_AFFECTED`；快照范围与实际影响行数不一致返回 `SNAPSHOT_MISMATCH`。Web 网关将输入校验映射为 400、认证为 401、授权/无效审批为 403、不存在为 404、幂等/状态/乐观锁/不可变/完整性冲突为 409；审批已消费使用 `APPROVAL_CONFLICT` 返回 409，只有真实下游内部失败保留 502。
- `RemoteException` 表示注册表、网络、TLS 或远程进程故障。Web 网关会失效本地 stub，并转成 HTTP 503、错误码 `RMI_UNAVAILABLE`。
- `RemoteServiceException` 是可序列化业务拒绝，包含 `{code,message}`。Web 网关在错误码前加 `RMI_`；`NOT_FOUND` 映射 404，`DUPLICATE|CONFLICT` 映射 409，`AUTHENTICATION_REQUIRED` 映射 401，`ACCESS_DENIED` 映射 403，`INVALID_REQUEST|VALIDATION_FAILED` 映射 400，其余 RMI 业务码映射 502。

主要 RMI 失败码：

| 类别 | 错误码 |
| --- | --- |
| 调用认证 | `AUTH_CONTEXT_INVALID`, `AUTH_TIMESTAMP_EXPIRED`, `AUTH_SIGNATURE_INVALID`, `AUTH_REPLAY`, `ACCESS_DENIED` |
| 查询 | `SCHEMA_NOT_ALLOWED`, `SELECT_INVALID`, `SELECT_FAILED` |
| 写入与幂等 | `TRANSACTION_SIZE_INVALID`, `IDEMPOTENCY_KEY_INVALID`, `IDEMPOTENCY_FINGERPRINT_INVALID`, `IDEMPOTENCY_CONFLICT`, `TRANSACTION_ROLLED_BACK`, `MUTATION_INVALID`, `NO_ROWS_AFFECTED` |
| 加密成绩/状态机 | `GRADE_INTEGRITY_INVALID`, `GRADE_PAYLOAD_INVALID`, `SUBMITTED_GRADE_IMMUTABLE`, `MAKEUP_TRANSITION_INVALID`, `SMALL_REVERSION_PAYLOAD_INVALID` |
| 高风险审批 | `REVERSION_REASON_REQUIRED`, `APPROVAL_REQUIRED`, `APPROVAL_INVALID` |
| 账本恢复 | `RECOVERY_QUERY_INVALID`, `RECOVERY_REASON_REQUIRED`, `RECOVERY_DECRYPT_FAILED`, `RECOVERY_ROLLED_BACK` |

高风险成绩删除和恢复的 RMI 规则以第 5.4 节为准：规范目标必须逐字相等，`approvalId` 必须是撤销申请 ID，只有 RMI 成功消费审批时请求才会标记为 `EXECUTED`。
