# HTTP API 与远程命名约定

> 更新时间：本轮（动态密钥与整库加密）。**接口清单与字段约定未变**：本次改动只影响配置注入与数据库文件层的加密，没有新增、删除或修改任何 HTTP 接口。

浏览器基址 `https://localhost:8443/api`，UTF-8 JSON。除登录外，所有接口需要会话 Cookie；所有 POST 还需 `X-CSRF-Token`。API 对不允许的角色返回 403，对会话缺失/过期返回 401。公开入口不接受 table、SQL 或任意调用服务 URL。

---

## 一、模块划分

| 模块 | 职责 | 端口（默认） |
|------|------|-------------|
| `gateway` | HTTPS 统一入口、服务注册发现、静态页面 | 8443 |
| `business-service` | 认证、权限、课程成绩流程、统计分析 | 9441（内部） |
| `data-service` | SQL 编译、参数绑定、事务、成绩加密、审计发件箱、整库加密数据源 | 9442（内部） |
| `audit-service` | 独立加密账本、哈希链、EVM 锚定 | 9443（内部） |
| `chain-worker` | Ganache EVM 测试链、LSTM 推理 | 9545（内部） |

网关只接受 GET/POST，业务路径 `/api/*` 转发至 business-service 的 `/internal/api`。各服务间通过 HMAC 签名 + 时间窗 + Nonce 认证。

**整库加密对接口透明**：数据服务的 H2 库现在以 `CIPHER=AES` 整库加密（会话口令是「文件口令 + 空格 + 用户口令」两段式，见 [配置说明](configuration.md#331-数据库整库加密h2-cipheraes)），但**没有任何 HTTP 接口因此新增、删除或改变请求/响应形状**：加密发生在 JDBC 连接层与文件层，网关、业务服务与浏览器都感知不到；接口清单与字段约定与之前完全一致。

---

## 二、Web 接口总览

| 方法 | 路径 | 权限 | 简述 |
|------|------|------|------|
| POST | `/login` | 公开 | 登录，返回会话 Cookie + CSRF |
| GET | `/me` | 已登录 | 当前用户信息 |
| POST | `/logout` | 已登录 | 登出 |
| POST | `/password` | 已登录 | 修改密码 |
| GET | `/courses` | QUERY/GRADE_ADMIN/AUDIT | 课程列表（分页） |
| GET | `/courses/catalog` | QUERY / GRADE_ADMIN / SELECTION_ADMIN | 教学班目录（分页） |
| GET | `/roster` | QUERY/GRADE_ADMIN | 课程选课名单 |
| GET | `/grades` | QUERY/GRADE_ADMIN | 成绩列表（分页） |
| POST | `/grades/save` | ENTRY（教师） | 批量暂存成绩 |
| POST | `/grades/transition` | MAINTAIN/GRADE_ADMIN | 提交/撤回/撤销 |
| POST | `/weights` | MAINTAIN（教师） | 设置六项系数 |
| GET | `/statistics` | QUERY/GRADE_ADMIN | 班级统计与异常 |
| POST | `/analysis` | MAINTAIN（教师） | 保存教学分析文本 |
| POST | `/predict` | PREDICT（学生/教师） | 学业预测：预测「期末未录入」的学生（不落库） |
| GET | `/transcript` | QUERY（学生） | 本人在校学业记录（学期倒序，含重修标记） |
| GET | `/users` | USER_ADMIN | 人员列表（分页） |
| POST | `/users/save` | USER_ADMIN | 新增/修改人员 |
| POST | `/courses/save` | GRADE_ADMIN | 新增/修改课程 |
| POST | `/enrollments` | GRADE_ADMIN | 单学生代选/代退（写入选课记录） |
| POST | `/enrollments/batch` | GRADE_ADMIN | 按课程批量选课/退课（1 人 / 多人 / 整班） |
| GET | `/audit` | AUDIT | 独立账本读取 |
| GET | `/integrity` | AUDIT | 成绩核查 |
| POST | `/audit/classify` | AUDIT | LSTM 日志检测 |
| POST | `/audit/review` | AUDIT | 复核事件 |
| GET | `/status` | ADMIN | 待同步审计数量 |
| GET | `/organizations` | ORG_ADMIN | 分层组织列表（分页） |
| GET | `/organizations/options` | 已登录 | 三级组织下拉选项 |
| GET | `/organizations/impact` | ORG_ADMIN | 删除影响面统计 |
| GET | `/organizations/members` | ORG_ADMIN | 组织成员列表（分页） |
| GET | `/organizations/students` | ORG_ADMIN | 学生名册（按组织筛选，分页） |
| POST | `/organizations/save` | ORG_ADMIN | 新增/修改学院、专业、班级 |
| POST | `/organizations/delete` | ORG_ADMIN | 删除组织（可选级联下级） |
| POST | `/organizations/assign` | ORG_ADMIN | 批量调整人员组织归属 |
| GET | `/selections` | SELECTION_ADMIN | 选课批次列表（分页） |
| GET | `/selections/available` | SELECTION_ENROLL / SELECTION_ADMIN | 学生可选批次与课程 |
| GET | `/selections/my` | SELECTION_ENROLL | 本人选课记录 |
| GET | `/selections/records` | SELECTION_ADMIN | 选课操作流水（分页） |
| POST | `/selections/save` | SELECTION_ADMIN | 发布/修改选课批次 |
| POST | `/selections/close` | SELECTION_ADMIN | 关闭批次 |
| POST | `/selections/cancel` | SELECTION_ADMIN | 取消批次并退回 |
| POST | `/selections/settle` | SELECTION_ADMIN | 结算开课人数 |
| POST | `/selections/select` | SELECTION_ENROLL | 学生选课 |
| POST | `/selections/drop` | SELECTION_ENROLL / SELECTION_ADMIN | 退课（教务可代退） |
| POST | `/selections/batch` | SELECTION_ADMIN | 按班级批量选课/退课 |

成绩单图片识别与语音录入是**前端本地能力**，不在此表中：它们不新增任何接口，识别/解析结果经教师确认后写入录入表单，再走既有的 `/grades/save` 与 `/grades/transition`。界面动作与接口的对照见 [3.3 的「成绩录入辅助（前端本地能力）」](#成绩录入辅助前端本地能力)。

---

## 三、详细接口说明

### 3.1 认证相关

#### POST `/login`
```http
POST /api/login HTTP/1.1
Content-Type: application/json

{"username": "admin", "password": "passwd"}
```
**响应：**
```json
{
  "user": {"id": "u1", "username": "admin", "name": "管理员", "role": "ADMIN", "permissions": ["GRADE_ADMIN","USER_ADMIN","AUDIT"]},
  "csrf": "abc123"
}
```
- 返回 `Set-Cookie: CAMPUS_SESSION=...; HttpOnly; Secure; SameSite=Strict`
- 返回 `Set-Cookie: CAMPUS_CSRF=...; SameSite=Strict`
- 失败 5 次后锁定 5 分钟（429）

#### GET `/me`
```http
GET /api/me HTTP/1.1
Cookie: CAMPUS_SESSION=xxx
X-CSRF-Token: abc
```
**响应：** 当前用户对象（含 id, username, name, role, permissions）

#### POST `/logout`
```http
POST /api/logout HTTP/1.1
Cookie: CAMPUS_SESSION=xxx
X-CSRF-Token: abc
Content-Type: application/json

{}
```
删除会话记录，清除 Cookie。

#### POST `/password`
```http
POST /api/password HTTP/1.1
Cookie: CAMPUS_SESSION=xxx
X-CSRF-Token: abc
Content-Type: application/json

{"oldPassword": "old123", "newPassword": "newStrong123"}
```
- 新密码 12–72 位，需含字母和数字
- 删除全部旧会话

---

### 3.2 课程管理

#### GET `/courses`
```http
GET /api/courses?term=2026-1&search=网络&page=1&size=20 HTTP/1.1
```
**响应：**
```json
{
  "items": [{"id":"c1","code":"CS101","name":"计算机网络","term":"2026-1","teacher_id":"t1","credits":4,"weights":{"regular":30,"attendance":0,"homework":0,"lab":20,"midterm":0,"finalExam":50},"version":0}],
  "total": 10,
  "page": 1,
  "size": 20
}
```
- 教师仅返回本人授课课程
- 学生仅返回已选课

#### GET `/courses/catalog`
```http
GET /api/courses/catalog?term=2026-1&search=网络&page=1&size=300 HTTP/1.1
```
**教学班目录**：教师与学生用它查看可选课程，教务管理员用它做课程维护与发布选课。与 `/courses` 不同，它**不按教师归属或选课关系过滤**，返回全部未停开的教学班，且每项已附带可读名称。

**查询参数：**

|参数|说明|
|---|---|
|`term`|按学期精确过滤，缺省返回全部学期|
|`search`|按课程**名称**子串匹配（`catalog` 用 `String.contains`，不是模糊搜索）|
|`page`、`size`|统一分页，默认 `1`/`100`|

**响应：** 统一分页对象，按学期降序、课程代码升序排列；每项是课程行加上 `collegeName`、`teacherName`、`className`：

```json
{
  "items": [{
    "id": "net-2026", "code": "CS301", "name": "网络软件与安全", "term": "2026-1",
    "teacher_id": "t2101", "credits": 3, "college_id": "C01001", "class_id": "B01002",
    "status": "ACTIVE", "version": 0,
    "collegeName": "信息工程学院", "teacherName": "赵老师", "className": "2024级-软件工程-2401班"
  }],
  "total": 11, "page": 1, "size": 300
}
```
- 已停开（`status=CANCELLED`）的教学班不出现在目录中；`class_id` 为空表示不限班级，此时 `className` 为 `null`
- 权限接受三者之一：`QUERY`（教师、学生）、`GRADE_ADMIN`（课程维护）、`SELECTION_ADMIN`（发布选课），否则 403「没有此操作权限」。这是本轮把权限放宽后的口径——选课管理员需要读目录才能勾选开放课程

#### POST `/courses/save`（GRADE_ADMIN）
```json
{
  "code": "NET-2026",
  "name": "计算机网络",
  "term": "2026-1",
  "teacherId": "t1101",
  "credits": 4
}
```
- 新增时 omit `id` 和 `version`
- 更新时必须提供 `id`、`version`
- 已有课程的 code 和 term 不可更改
- **同一学期允许开设多个使用相同课程代码的教学班**（不同教师分别开课）：新增时不再校验 `(code, term)` 唯一性，重复修读由选课环节拦截（见 3.8 节「同一课程代码冲突」）

#### GET `/roster`
```http
GET /api/roster?courseId=c21-cs102b HTTP/1.1
```
返回该教学班的选课学生列表（`id`、`name`、`username`、`source`、`collegeName`、`majorName`、`className`），并标出**哪些学生本次是在重修**：

```json
[{
  "id": "20231530", "name": "林知夏", "username": "20231530", "source": "SEED",
  "collegeName": "信息工程学院", "majorName": "软件工程", "className": "2023级-软件工程-2301班",
  "retake": true, "retakeLabel": "重修"
}]
```
- `retake`(boolean) 表示该生在本课程号的**更早学期**有「成绩已提交且有效分 < 60」的修读记录；`retakeLabel` 为 `"重修"` 或 `null`（`CourseService.roster` 用 `failedCodesBefore` 判定，教师端据此看到谁在重修）
- 实现上**消除 N+1**：课程、选课、成绩三份数据各取一次后在内存 join 完成重修判定，学院/专业/班级名称也用 `OrganizationService.namesInto()` 一次取回整表复用（此前逐门课各查一次组织表），因此大名单下的响应时间不再随课程数线性放大
- **课程名称与重修无关**：重修就是**同一课程代码在后续学年重新开设**的教学班，课程名、学分与权重都可以与原教学班完全一致，接口不会返回任何带「重修」后缀的课程名
- 只返回 `status != DROPPED` 的选课行；同一个学生不会因为重修而在名单里出现两次（他选的是另一个教学班）

#### POST `/enrollments`（GRADE_ADMIN）
教务为**单个**学生代选或代退一门课。第二轮起，两类动作都会写一条 `enrollment_records` 流水，因此也会出现在「选课管理 → 选课记录」里。

```json
{"courseId": "c24-cs301", "studentId": "20241530"}
```
```json
{"courseId": "c24-cs301", "studentId": "20241530", "remove": true}
```
**响应：** `{"ok": true}`

|字段|必填|说明|
|---|---|---|
|`courseId`|是|课程主键，不存在 404「记录不存在」|
|`studentId`|是|学生账号，不存在 404「记录不存在」|
|`remove`|否|缺省 `false` 表示代选；`true` 表示代退|

- 学生角色必须是 `STUDENT`；选课（`remove=false`）还要求账号 `enabled=1`，否则 400「选课人必须为启用的学生」
- 代选写入 `enrollments` 时带 `source="ADMIN"`、`status="ACTIVE"`、`selected_at`（当前时间）；代退在**没有任何成绩行**时**删除**该 `enrollments` 行（与按课程批量退课的 `status=DROPPED` 不同），已有成绩 409「已有成绩，不能退选」，没有选课记录 404「选课不存在」
- 同一次请求还会把课程 `version + 1`（课程版本串行化选课变更），审计 action 为 `ENROLLMENT_UPDATE`，resource 为课程编号
- 流水动作：代选 `ADMIN_ASSIGN`、代退 `ADMIN_REMOVE`，`reason` 为「教务直接选课」/「教务直接退课」，`code`/`term` 取自课程行

#### POST `/enrollments/batch`（GRADE_ADMIN）
按**课程**批量选课/退课：可选一个学生、多个学生，或某个班级的全部学生（两种方式可叠加，取并集并按学生去重）。这是管理员「选课管理 → 课程与选课」里每门课程「选课」按钮弹窗调用的接口。

```json
{
  "courseId": "c24-cs301",
  "studentIds": ["20241530", "20241536"],
  "className": "2024级-软件工程-2401班",
  "remove": false
}
```
**响应：**
```json
{
  "ok": true,
  "added": 4,
  "skipped": 1,
  "removed": 0,
  "failed": [{"studentId": "20241601", "name": "安时雨", "reason": "账号已停用，不能选课"}],
  "classStudents": 3,
  "total": 5
}
```

|字段|必填|说明|
|---|---|---|
|`courseId`|是|课程主键；不存在 404「课程不存在」|
|`studentIds`|否|字符串数组（也接受逗号分隔字符串）|
|`classId`/`class_id` 或 `className`/`class_name`|否|班级**编号或名称**，经 `OrganizationService.resolveOwn` 解析；传名称即可|
|`remove`|否|`true` 表示批量退课，缺省/`false` 表示批量选课（`Models.flag` 解析，接受 `true`/`false`/`1`/`0`）|
|`publishId`|否|写入流水所属批次；未给出时由后端按课程学期自动匹配|

- 目标学生 = `studentIds` ∪ 班级全部学生（只取 `role=STUDENT`），按学生去重；**两者至少要有一个**，否则 400「必须指定学生名单或班级」；班级不存在 400「指定的班级不存在」；去重后为空 400「没有找到可操作的学生」
- 单次上限 `Models.MAX_BATCH_STUDENTS = 300`，超出 400「单次批量操作最多 300 名学生」
- 选课（`remove=false`）：已选（`status=ACTIVE`）的学生计入 `skipped` 而不报错；账号停用进 `failed`（原因「账号已停用，不能选课」）；非学生账号进 `failed`（「仅学生可选课」）；账号不存在进 `failed`（「学生不存在」）；其余插入 `enrollments`（`source="ADMIN"`、`status="ACTIVE"`、`selected_at`），**已退课的行复用为 `UPDATE`**（`(course_id,student_id)` 是唯一索引，不能再插一行）
- 退课（`remove=true`）：没有 `ACTIVE` 记录的学生计入 `skipped`；该生该课程存在任意状态的 `grades` 行时进 `failed`（「教师已录入成绩，不能退课」）而**不中断整批**；其余把 `status` 置为 `DROPPED`（保留行）
- 每名真正被处理的学生都写一条 `enrollment_records`：选课 `ADMIN_ASSIGN`（reason「教务按课程批量选课」）、退课 `ADMIN_REMOVE`（reason「教务按课程批量退课」），`operator` 为当前管理员，`code`/`term` 取自课程行
- 所有关系行与流水在**同一次** `repo.mutate` 中提交，审计 action 为 `ENROLLMENT_BATCH`、resource 为课程编号，因此要么全成要么全滚
- `publishId` 的解析顺序：请求给出则优先使用（批次行不存在时也照写该编号）；未给出时在课程所属学期里找 `course_ids` 含该课程且 `status=OPEN` 的批次，没有 `OPEN` 再找 `CLOSED`，都找不到就把 `publish_id` 留空
- `classStudents` 只在请求指定了班级时返回（前端据此显示「班级 N 人」）；`total` 是去重后的目标学生数
- 与批次维度的 `POST /selections/batch` 的分工：后者按 `publishId` + 班级处理并走完整选课规则校验（`checkSelectable`），前者面向课程界面直接维护名单，不要求批次 `OPEN`、不检查 `allow_add`/`allow_drop` 与时间窗口，逐人失败只记入 `failed`；两者写同一张流水表、使用同样的 `ADMIN_ASSIGN`/`ADMIN_REMOVE` 动作

---

### 3.3 成绩管理

#### POST `/grades/save`（教师）
```json
{
  "courseId": "net-2026",
  "courseVersion": 0,
  "grades": [
    {
      "studentId": "s1",
      "version": null,
      "scores": {"regular": 50, "lab": 50, "finalExam": 66}
    }
  ]
}
```
- 一次最多 300 条
- 新成绩 version 为 null；更新必须携带当前版本
- 带权项缺失允许暂存，状态为 DRAFT
- 补考只对正考不及格的学生开放：某项权重 > 0 时正考加权总评已达到 60 分，再提交 `makeup` 会 400「正考已及格，不能录入补考成绩」
- 返回 `{saved: N, anomalies: [...]}`

#### POST `/grades/transition`
```json
{
  "courseId": "net-2026",
  "courseVersion": 1,
  "action": "SUBMIT"
}
```
**操作类型：**
| action | 权限 | 说明 |
|--------|------|------|
| SUBMIT | 教师 MAINTAIN | 提交所有成绩，所有有权重项必须完整 |
| WITHDRAW | 教师 MAINTAIN | 撤回，变回 DRAFT |
| SMALL_REVOKE | 管理员 GRADE_ADMIN | 小撤销（单个成绩） |
| DELETE_ALL | 管理员 GRADE_ADMIN | 大撤销，confirmation 须等于 courseId |

#### POST `/weights`（教师）
```json
{
  "courseId": "net-2026",
  "version": 0,
  "weights": {"regular": 30, "attendance": 10, "homework": 10, "lab": 20, "midterm": 10, "finalExam": 20}
}
```
- 六项必须齐全，总和为 100
- 已提交成绩后不可修改，需先撤销

#### 成绩录入辅助（前端本地能力）

**本节没有接口。** 成绩单图片识别（`frontend/src/ocr.js` + `components/RecognizePreview.vue`）与语音录入（`frontend/src/voice.js` + `components/VoicePanel.vue`）都是**纯前端能力**：识别、解析、列映射、逐格编辑全部在浏览器内完成，因此**不新增任何后端接口**，也不改变任何既有接口的请求体与响应体。两个功能的产物最终仍走下面这条既有链路。

|界面动作|实际发生的调用|说明|
|---|---|---|
|点「识别成绩单」→ 选择图片|**无接口调用**|图片经 `URL.createObjectURL(file)` 在本页 `Image` + `Canvas` 读取，只用同源静态资源 `/ocr/worker.min.js`、`/ocr/core`、`/ocr/lang` 加载 tesseract.js 与其 `eng` 语言包；用完在 `finally` 里 `URL.revokeObjectURL(url)`。界面上限单张 10 MB、支持 PNG/JPEG/WebP（版式要求见 [ocr-voice-design.md](ocr-voice-design.md) 4.12）|
|点「语音录入」→ 口述 / 文本框输入|**无接口调用**|`frontend/src/voice.js` 的中文数字与口语解析是纯函数；只有浏览器厂商的 `SpeechRecognition` 会处理音频（录音可能上行到厂商服务，界面要求逐次点击「开始识别」，原先那条固定隐私提示已按用户要求删除）|
|切换录入对象（下拉框 / 上一行下一行 / 成绩表行内麦克风按钮）|**无接口调用**|纯前端：`VoicePanel.vue` 用 `rows` 渲染「录入对象」下拉框与上一行/下一行按钮，`App.vue` 的成绩表每行状态列另有麦克风按钮（`openVoice(row)`）；两条面板路径都只 `emit("select-target", row)`，由 `App.vue` 的 `@select-target="voiceTargetRow = $event"` 写回目标行。**语音与文本框通道都只解析分数，不再承担切行**（`voice.js` 已移除 `parseNavigation`）。既不改成绩、也不发请求（名册本身仍来自既有的 `GET /roster?courseId=`）|
|预览里改格 / 改列映射 / 取消勾选|**无接口调用**|全部只改组件本地副本，`RecognizePreview.vue` 的注释即此口径：只负责展示 + 编辑 + 勾选 + 汇报|
|预览里点「确认填入」/ 语音面板点「填入当前行」|**无接口调用**|`applyRecognized` / `applyVoice` 只把数值写进 `App.vue` 的录入表单草稿（走与手工输入同一个 `score()`，置 `dirty = true`），提示「已填入 N 人成绩，待暂存」|
|点「暂存」|`POST /grades/save`（教师 `ENTRY`）|请求体与 3.3 完全一致：`{courseId, courseVersion, grades:[{studentId, version, scores}]}`，只提交 `state === "DRAFT"` 的成绩行|
|点「提交」/「撤销提交」/「小撤销」/「大撤销」|`POST /grades/transition`（`MAINTAIN` / `GRADE_ADMIN`）|`action` 取 `SUBMIT`/`WITHDRAW`/`SMALL_REVOKE`/`DELETE_ALL`；识别与语音都不产生独立的"提交"路径|
|成绩表数据来源|`GET /roster?courseId=`、`GET /grades?courseId=`|教师名单与已有成绩仍由这两个既有接口提供；预览的学号匹配用的是 `roster` 里的 `username`，**中文姓名不作为匹配依据**|

**学号不猜人（识别侧的默认安全取向）**：OCR 给出的学号只有在"归一化后精确命中名册"或"差异能被已知字形混淆（`O→0`、`l→1`、`B→8`、`S→5`…）解释"时才自动填入；像 `9→0`、`5→4` 这类**纯数字位的差异**默认不认人——`correctId` 返回 `{username: null, unverified: true, candidate}`，`mapColumns` 因此把该行标成 `matched: false` 并给出「学号与名册有差异但无法确认，请人工核对」。只有在显式传 `allowDigitCorrection: true` 且差异**仅一位数字**时才自动认人（仍标「学号经自动纠正，请核对」）；多个候选在"编辑距离 + 混淆解释力"上完全并列时标 `ambiguous`，提示「学号有多个相近候选，请人工确认」。这三条都只是界面提示与草稿，**不产生任何接口调用**，也不改变上面的暂存/提交链路。规则细节与参数见 [ocr-voice-design.md](ocr-voice-design.md) 4.9。

**权限**：两个入口按钮与「暂存」按钮使用同一个可见性条件——`isTeacher && can('ENTRY') && !submitted`（`App.vue` 模板）。成绩表每行的麦克风按钮（`data-testid="voice-<学号>"`）同样条件；成绩已全部提交（`submitted`）后按钮不渲染，成绩格也改为只读文本。也就是说，这两个能力**没有引入新的权限点**：能看到录入按钮，就能看到它们。

**数据不出浏览器**：识别过程不产生任何图片上行请求，这一点由 `scripts/ocr-voice-check.mjs` 的断言锁定（统计「POST 且请求体含 PNG 字节」的请求数必须为 0，实测 0）。语音通道的唯一例外是浏览器厂商的语音识别服务——界面要求教师逐次点击「开始识别」才开麦，**但那条固定隐私提示已按用户要求从面板删除**，因此界面上不再有相关文案（事实记录见 [ocr-voice-design.md](ocr-voice-design.md) 2 节与 7.4 节）；成绩数据本身始终只在本页表单里处理，结果需教师确认后才写入，写入后仍需暂存才落库。

设计细节（管线、按轮生成的候选表、双线性旋转、行带自适应切分、列分配容差、学号安全匹配、选优与停止条件、耗时与已知边界）见 [ocr-voice-design.md](ocr-voice-design.md)。

---

### 3.4 统计与预测

#### GET `/statistics`
```json
{
  "count": 30,
  "mean": 72.5,
  "passRate": 86.67,
  "bands": {"0–59": 3, "60–69": 5, "70–79": 10, "80–89": 9, "90–100": 3},
  "analysis": {"content": "...", "version": 2},
  "anomalies": [
    {"studentId":"s1","rule":"THREE_SIGMA","message":"总评分数超过均值正负 3 标准差","value":95.5}
  ]
}
```
**异常规则：**
| rule | 条件 |
|------|------|
| CLASS_MEAN | 班级均分 < 50 或 > 95 |
| THREE_SIGMA | 单生超出均值 ±3σ |
| PERCENTILE_05 | 总评后 5% 且不及格 |
| HISTORY_SHIFT | 与历史均分相差 > 25 分 |
| MAKEUP_EXTREME | 补考卷面分 < 20 或 > 95 |

#### POST `/analysis`（教师）
```json
{
  "courseId": "net-2026",
  "content": "本次考试整体表现良好...",
  "version": 2
}
```
- 新建时 version = -1

#### POST `/predict`（学生/教师）
```json
{"courseId": "net-2026"}
```
使用 Weka `LinearRegression` + `REPTree` 现场训练并预测，**不写入数据库**（预测用的期末分只放在内存副本里）。

**权限与隐私**：只允许 `STUDENT` / `TEACHER` 调用（其他角色 403「预测隐私仅本人及授课教师可见」）；随后 `user.require("PREDICT")`（缺权限 403「没有此操作权限」）与 `courses.access(u, id, "PREDICT")` 两道校验：教师只能访问本人授课课程（403「只能访问本人授课课程」），学生必须存在该课程的选课记录（403「未选修该课程」）。

**训练样本（前置条件）**：取**同一课程代码、学期严格更早**的全部教学班的 `state=SUBMITTED` 成绩，且成绩载荷**同时含 `regular`、`lab`、`finalExam`** 三分项；年份取学期前 4 位（同一年的两个学期只算 1 年）。必须满足 **≥3 个不同年份 且 ≥24 条完整样本**，否则：

- 样本或年份不足 → **422**「至少需要 3 年、24 条完整历史成绩，当前数据不足，未生成预测」
- 样本够了但模型训练失败 → **422**「历史数据无法支持模型训练」（错误码 `MODEL_FAILURE`）

**预测对象**：该课程**当前成绩行里「已有平时与实验、期末未录入」的学生**（`regular != null && lab != null && finalExam == null`）——也就是「期末还没考」的人；已经录入期末的学生会被跳过，不会出现在 `results` 里。学生调用时只看本人的成绩行，教师调用时看该课程全部学生的成绩行。演示数据里当前学期可预测的学生带一条**暂存（`DRAFT`）**成绩（只有平时与实验分），暂存成绩不作为通过/挂科依据，只作为预测输入。

**空结果不是错误**：如果该课程的成绩都已录入期末（例如学生对自己**已经出分的历史课程**调用），接口仍返回 200，但 `results` 是**空数组**——因为不存在「期末未录入」的预测对象。因此**预测只对进行中的课程有意义**；前端会在学业预警页顶部提示「学业预警针对正在进行的课程：已录入平时与实验、期末尚未考试时，可以预估期末与总评成绩。请在上方课程选择里选一门当前学期（2026-1）的课程」，选中已出分课程时显示「本学期的期末成绩已经录入，预测对象为空……」。客户端不要把空 `results` 当成数据不足——数据不足是 **422**（见上文门槛）。

**响应字段：**

|字段|说明|
|---|---|
|`model`|固定为 `Weka LinearRegression + REPTree`|
|`trainingYears`|参与训练的年份集合（学期前 4 位，去重升序）|
|`samples`|参与训练的完整样本条数（≥24）|
|`validationYear`|留作时间验证的年份（最新年份，整数）|
|`holdoutRmse` / `treeHoldoutRmse`|线性回归与决策树在留出年份上的 RMSE|
|`linearFormula`|线性回归模型的可读公式（`linear.toString()`）|
|`tree`|决策树的文本结构（`tree.toString()`）|
|`results`|逐个「期末未考」学生的预测结果数组，见下|

`results[]` 的每个元素：

|字段|类型|说明|
|---|---|---|
|`studentId`|string|学生账号|
|`linearExam`|number|线性回归预测的期末卷面分（0–100，两位小数）|
|`treeExam`|number|决策树预测的期末卷面分（0–100，两位小数）|
|`predictedTotal`|number|用线性回归的期末分算出的加权总评|
|`treeTotal`|number|用决策树的期末分算出的加权总评|
|`low`|number|`predictedTotal − 1.96 × holdoutRmse × finalExam权重/100`，截断在 0–100|
|`high`|number|`predictedTotal + 1.96 × holdoutRmse × finalExam权重/100`，截断在 0–100|
|`warning`|boolean|两个模型的总评任一 < 55|
|`risk`|boolean|两个模型的总评任一 < 60|

- 区间口径说明：`low`/`high` 是「总评 ± 1.96 × 留出年份 RMSE × 期末权重」的经验近似，不是经过校准的严格置信区间
- 管理员没有该接口权限，拿不到个人预测分（隐私约束优先于「管理员可运行预测」）

#### GET `/transcript`（学生）
返回**本人**学业记录：只含 `state=SUBMITTED` 的成绩（暂存 `DRAFT` 不出现），**按学期倒序、同学期按课程代码升序**，便于「我的成绩」直接展示最近的修读。

```json
[{
  "id": "c21-cs102b", "code": "CS102", "name": "程序设计基础", "term": "2024-1",
  "credits": 4, "weights": {"regular":30,"attendance":0,"homework":0,"lab":20,"midterm":0,"finalExam":50},
  "teacher_id": "t1102", "college_id": "C01001", "class_id": "B01002", "status": "ACTIVE", "version": 0,
  "total": 52.0, "effective": 60.0, "makeup": 60, "rank": 2, "classSize": 3, "failed": false,
  "retake": true, "retakeLabel": "重修"
}]
```
- 每项 = 课程行全部列 + 计算字段：`total`（加权总评）、`effective`（补考封顶后的有效分）、`makeup`（补考分，封顶 60；无补考为 `null`）、`rank`/`classSize`（该教学班内的有效分竞争排名与人数）、`failed`（`effective < 60`）
- **`retake`(boolean) / `retakeLabel`（`"重修"` 或 `null`）**：同一 `code` 在**更早学期**还有一条修读记录时，本学期这条即重修。学业记录只含已提交成绩，而选课规则禁止已通过的课程再次修读、演示数据的 `verifyTranscriptIntegrity()` 也保证「跨学期同代码的前一次必是挂科」，因此「同一代码出现第二次」必然意味着第一次没通过——正是重修。正在修读（成绩未提交）的记录不在学业记录里，不参与判定
- 课程名与重修无关：重修是同一课程号在后续学年重新开设，**课程名保持原样**，前端只在课程名后加「重修」徽标
- 前端「我的成绩」的统计口径与重修一致：已获课程按课程代码去重（重修通过只算一门）、已获学分按代码去重只计通过的那次、未通过课程统计的是**至今仍未通过**的课程代码数（已重修通过的不再计入）
- 权限：`STUDENT` 且持 `QUERY`；其他角色 403「仅学生可查看本人学业记录」

---

### 3.5 人员与组织

#### GET `/users`（USER_ADMIN）
```http
GET /api/users?page=1&size=20 HTTP/1.1
```
返回剔除密码后的用户列表。

#### POST `/users/save`（USER_ADMIN）
```json
{
  "username": "alice",
  "name": "Alice Wang",
  "role": "TEACHER",
  "permissions": ["QUERY", "ENTRY"],
  "collegeName": "信息工程学院",
  "majorName": "软件工程",
  "className": "",
  "enabled": 1,
  "password": "StrongPass123"
}
```
- 角色：TEACHER / STUDENT / ADMIN，角色确定后不可变更。各角色的权限集合必须在 `Models.PERMISSIONS` 内：TEACHER `QUERY,ENTRY,MAINTAIN,PREDICT`；STUDENT `QUERY,PREDICT,SELECTION_ENROLL`；ADMIN `GRADE_ADMIN,USER_ADMIN,AUDIT,ORG_ADMIN,SELECTION_ADMIN`
- 组织归属字段同时接受编号与名称，键名可任选其一：`collegeId`/`collegeName`/`college`、`majorId`/`majorName`/`major`、`classId`/`className`/`class`
- 归属必填按角色分级：**ADMIN 不归属任何组织**（三级字段都必须为空，带了任意一级即 400「管理员不归属学院、专业或班级」，通过后写空串）；TEACHER 必须有学院与专业；STUDENT 必须有学院、专业与班级
- 层级一致性：所选专业必须属于该学院；所选班级必须属于该专业与该学院（否则 400）
- `department` 是服务端按 `AdminService.department` 派生的展示字段，请求体中的同名值不会被采纳
- 更新他人或本人账号组织归属时 `version` 递增；账号被修改后其全部会话记录被删除，需重新登录
- 不能停用自己，也不能撤销自己的 `USER_ADMIN` 权限
- 演示账号密码固定为用户名

---

### 3.6 审计与核查

#### GET `/audit`（AUDIT）
返回独立账本内容：
```json
{
  "verified": true,
  "head": "abc123...",
  "blocks": [{"index": 0, "hash": "...", "transaction": "0x..."}],
  "events": [
    {"id": "e1", "actor": "teacher", "action": "SUBMIT", "resource": "c1", "time": "...", "changes": [...]}
  ]
}
```
- 读取走 `LedgerService` 的**本地校验层**（哈希链 + HMAC）并使用两级缓存（`localVerified`/`fullVerified`，按文件大小 + mtime 失效，写入路径显式 `invalidate()`），因此不必每次读取都重跑 EVM 锚点核对；需要完整校验的路径仍会核到链上
- **账本被从尾部截断时返回 409**：本地校验只检查区块之间的链接，尾部截断不会破坏哈希链，因此服务端额外用 `anchors.json` 的区块数做「本地账本不得比链上锚点更短」检查，失败文案为「独立账本比链上锚点更短（本地 N 块 / 锚点 M 块），疑似被截断」。只检查「更短」——账本比锚点多属正常中间态（新区块先落本地账本、再逐块锚定）

#### GET `/integrity`（AUDIT）
对比账本原始快照与数据库当前状态，返回：
```json
{
  "verified": true,
  "checked": 100,
  "issues": [],
  "head": "...",
  "outbox": {"pending": 0}
}
```
- 实现上**一次取回全部成绩在内存比对**（同一份 RPC 一次拿全表），不再对账本里的每条记录各发一次查询；如果批量读取因「存在无法解密的行」失败（数据服务返回 409），会自动退化为 `verifyRowByRow()` 逐条读取，把问题精确落到具体行上，`checked`/`issues` 的语义与优化前完全一致
**问题类型：**
| issue | 含义 |
|-------|------|
| MISSING | 数据库中找不到该成绩 |
| MISMATCH | 数据库成绩与账本记录不一致 |
| CIPHERTEXT_TAMPERED | 密文损坏 |
| DELETED_RECORD_REAPPEARED | 已删除记录重新出现 |
| UNAUDITED_ROW | 数据库中存在未审计记录 |

#### POST `/audit/classify`（AUDIT）
触发 LSTM 异常检测，返回分类结果。

#### POST `/audit/review`（AUDIT）
```json
{"resource": "e1", "comment": "已核查，属正常操作"}
```

#### GET `/status`（ADMIN）
```json
{"pending": 0}
```
审计发件箱待同步数量。

---

### 3.7 组织管理（ORG_ADMIN）

组织为「学院—专业—班级」三级，分别落在 `colleges`、`majors`、`classes` 三张表，主键是 `C01001`/`M01001`/`B01001` 形式的可读编号。**这个主键就是组织唯一的编号**：它同时用于库内引用、接口寻址、审计 resource 与页面展示，第三轮已取消单独的「显示编号 `code`」。请求体中的组织字段既接受编号也接受名称；响应一律附带可读名称，前端不需要理解内部编号。班级**不设辅导员**，相关字段已从结构（`SCHEMA_VERSION = 3`）与所有响应中移除；组织表里遗留的 `code` 列仍在表结构中，但本节所有接口都不再读写它。

#### GET `/organizations`
```http
GET /api/organizations?level=CLASS&collegeId=C01001&majorId=M01001&page=1&size=100 HTTP/1.1
```
**查询参数：**

|参数|必填|说明|
|---|---|---|
|`level`|是|`COLLEGE`/`MAJOR`/`CLASS`，大小写不敏感|
|`collegeId` 或 `college`|否|仅 `MAJOR`/`CLASS` 使用，按学院过滤，可用编号或名称|
|`majorId` 或 `major`|否|仅 `CLASS` 使用，按专业过滤，可用编号或名称|
|`page`、`size`|否|默认 `1`/`100`；`size` 上限 300|

**响应：** 统一分页对象，`items` 为该层表的业务列（`id`、`name`…`enabled`、`version`）加上按层级计算的可读字段与计数：

|level|附加字段|
|---|---|
|`COLLEGE`|`majorCount`、`classCount`、`studentCount`|
|`MAJOR`|`collegeName`、`classCount`、`studentCount`|
|`CLASS`|`collegeName`、`majorName`、`studentCount`|

三个层级都会额外给出 camelCase 别名：`short_name` 同时以 `shortName` 返回，`grade_year` 同时以 `gradeYear` 返回，方便前端直接绑定驼峰字段而不用做列名映射。

```json
{
  "items": [{"id":"B01001","major_id":"M01001","college_id":"C01001","name":"2023级-软件工程-2301班","grade_year":"2023","gradeYear":"2023","enabled":1,"version":0,"collegeName":"信息工程学院","majorName":"软件工程","studentCount":3}],
  "total": 16, "page": 1, "size": 100
}
```
- `id` 就是组织编号（`C`/`M`/`B` + 5 位），新增时由服务端生成并在响应里返回；**列表不输出 `code` 键**（服务端用 `copy.remove("code")` 兜底，因为该列仍留在表结构中）
- `studentCount` 只统计 `role=STUDENT` 的账号，与页面上的「学生」列一致
- 排序按组织编号升序，因此列表顺序稳定
- 班级项**不包含 `counselor`**：需求已取消辅导员，服务端在返回前显式移除该键（旧库残留也不会下发），`/organizations/options` 同样不输出
- 需要 `ORG_ADMIN`，否则 403
- `level` 缺失 400「缺少组织层级 level」；取值非法 400「组织层级无效：…」

#### GET `/organizations/options`
返回三级组织的完整下拉数据，任何已登录用户都可读——课程维护、人员维护、选课范围与选课记录筛选都需要它。

```json
{
  "colleges": [{"id":"C01001","name":"信息工程学院","shortName":"信息学院"}],
  "majors":   [{"id":"M01001","name":"软件工程","collegeId":"C01001","collegeName":"信息工程学院"}],
  "classes":  [{"id":"B01001","name":"2023级-软件工程-2301班","collegeId":"C01001","collegeName":"信息工程学院","majorId":"M01001","majorName":"软件工程","gradeYear":"2023"}]
}
```
- 返回数组而不是分页对象；三个键始终存在
- 三个层级都只用主键 `id` 作编号，**不含 `code` 键**；班级项**不含 `counselor`**
- **不按 `enabled` 过滤**：停用的组织仍需在历史数据中显示名称

#### GET `/organizations/impact`
```http
GET /api/organizations/impact?level=COLLEGE&id=C01001 HTTP/1.1
```
删除前的影响面统计，**只读、不修改数据**。

```json
{"majors": 2, "classes": 3, "students": 12, "courses": 9, "total": 26}
```
|level|返回键|
|---|---|
|`COLLEGE`|`majors`、`classes`、`courses`、`total`|
|`MAJOR`|`classes`、`courses`、`total`|
|`CLASS`|`students`、`courses`、`total`|

- `total` 是下级与人员计数之和，不含 `courses`；前端在 `total > 0` 时提示「将拒绝直接删除」
- `courses` 按层级统计（`OrganizationService.affiliatedCourses`）：`COLLEGE` 统计 `courses.college_id` 等于该学院的课程；`CLASS` 统计 `courses.class_id` 等于该班级的课程；`MAJOR` 统计**该专业所属学院下的全部课程**（先由 `majors.college_id` 找到学院，再取该学院的课程），因此专业层的课程数会包含同学院其他专业的课程，是一个偏保守的上界
- 组织不存在 400「指定的学院/专业/班级不存在」；缺少 `id` 400「缺少…编号」

#### GET `/organizations/members`
```http
GET /api/organizations/members?level=CLASS&id=B01001&page=1&size=200 HTTP/1.1
```
返回该组织下所有账号（不按角色过滤：学院层会同时包含教师与学生）。每项是剔除 `password` 的用户行，并附加 `collegeName`、`majorName`、`className` 与服务端计算的 `department`。响应为统一分页对象。

- 管理员不归属任何组织（三级字段为空串），因此**不会**出现在任何一级组织的成员列表里；这是需求「管理员不归属学院/专业/班级」的期望结果

#### GET `/organizations/students`
```http
GET /api/organizations/students?classId=B01001&unassigned=false&search=许&page=1&size=200 HTTP/1.1
```
组织管理页面的**学生名册**接口：页面上的「批量调入学生」用它取名单（`GET /organizations/students?size=300`），因此只持有 `ORG_ADMIN` 的管理员不必依赖需要 `USER_ADMIN` 的 `/users` 就能完成人员归属调整。需要 `ORG_ADMIN`。

**查询参数：**

|参数|说明|
|---|---|
|`collegeId`/`college`|按学院过滤，可用编号或名称|
|`majorId`/`major`|按专业过滤，可用编号或名称|
|`classId`/`className`/`class`|按班级过滤，可用编号或名称|
|`unassigned`|`true` 时只返回**尚未分班**（`class_id` 为空）的学生；为 `true` 时忽略 `classId`|
|`search`|按姓名或账号模糊匹配，大小写不敏感|
|`page`、`size`|统一分页，默认 `1`/`100`|

**响应：** 统一分页对象，按 `class_id`、`id` 排序（分页稳定）：

```json
{
  "items": [{
    "id": "20241530", "username": "20241530", "name": "许清和", "role": "STUDENT", "enabled": 1,
    "collegeId": "C01001", "collegeName": "信息工程学院",
    "majorId": "M01001", "majorName": "软件工程",
    "classId": "B01002", "className": "2024级-软件工程-2401班", "classNameRaw": "2024级-软件工程-2401班",
    "department": "软件工程·2024级-软件工程-2401班"
  }],
  "total": 6, "page": 1, "size": 200
}
```
- 只返回 `role=STUDENT` 且 `enabled=1` 的账号
- `className` 与 `classNameRaw` 目前是同一个值：班级名称本身已是规范显示名（`年级-专业-班号`，如 `2023级-软件工程-2301班`），`OrganizationService.displayName` 直接返回 `name`。保留两个键是为了兼容按「展示名 / 原始名」两套语义编写的调用点。本接口的三个班级参数都经 `resolveOwn` 解析，编号与名称写法都能命中；但 `/organizations/assign` 的 `classId`/`class` 走的是 `requireOrganization`（只按 `id` 精确匹配），**必须传班级编号**
- `department` 由服务端派生，与 `/organizations/members` 的语义一致（学生为「专业·班级」）

#### POST `/organizations/save`
新增或修改一个组织对象。**提交 `id` 表示修改，省略 `id` 表示新增**；编号就是主键 `id`，新增时由服务端生成并在响应中返回，修改时响应仍是同一个主键。

```json
{
  "level": "CLASS",
  "name": "2027级-软件工程-2701班",
  "gradeYear": "2027",
  "college": "信息工程学院",
  "major": "软件工程",
  "enabled": 1
}
```
**响应：** `{"ok": true, "id": "B01017"}`

- `id` 是主键（`C`/`M`/`B` + 5 位），新增时由 `OrganizationService.nextId` 生成后写入，修改时**只作定位**、不会被请求体改写
- 请求体里带 `code` 不会报错，也不会写库（组织接口已不读写该遗留列）；带一个与路径对象不符的 `id` 同样不能改主键

按层级的字段：

|字段|COLLEGE|MAJOR|CLASS|约束|
|---|---|---|---|---|
|`level`|必须|必须|必须|`COLLEGE`/`MAJOR`/`CLASS`|
|`id`|修改时必填|同左|同左|必须已存在；仅用于定位，响应回显同一个主键|
|`name`|必须|必须|必须|非空，≤100|
|`code`|忽略|忽略|忽略|遗留列：组织接口不再读写，传了既不生效也不报错|
|`shortName`|可选|—|—|≤50|
|`description`|可选|—|—|≤500|
|`college`/`collegeId`|—|**必须**|可选|MAJOR 用；可用编号或名称|
|`degree`|—|可选|—|≤50|
|`years`|—|可选|—|1–10，新建默认 4|
|`major`/`majorId`|—|—|**必须**|CLASS 用；可用编号或名称|
|`gradeYear`|—|—|可选|≤20|
|`enabled`|可选|可选|可选|接受 `1/0`、`true/false` 或 `"true"/"false"`，缺省沿用旧值（新建为启用）|

- 班级的 `college_id` 由所选专业推导，请求体里的学院值仅用于校验，因此三级归属只有一份真相；**班级没有 `counselor` 字段**（结构版本 3 已删除该列，传了也会被忽略）
- 主键 `id` 的生成规则，固定 6 位 =「1 位层前缀 + 2 位号段 + 3 位本级序号」：学院取现有学院第 2–3 位的最大值 +1 作为自己的号段，本级序号固定为 `001`（`C01001`、`C02001`…）；专业与班级沿用上级编号的号段，本级序号在同一号段内递增（`C01001` 下的专业是 `M0100x`，`M01001` 下的班级是 `B0100x`）。编号生成后即作主键并由服务端拥有；由于序号取自「现存最大值 +1」，删除序号最大的对象后该编号会在下次新建时被再次分配，因此编号不保证永不复用
- 编号用尽时返回 409：学院号段上限 99、专业/班级本级序号上限 999；上级编号不满足「字母 + 5 位数字」时 400「缺少所属学院/专业，无法生成编号」
- 修改时 `version` 自动 `+1`（乐观并发）；请求体不需要携带 `version`
- 可选字段未提供时保留旧值（新建写空串），因此局部编辑不会清空未提交的字段
- 错误：缺少层级 400；名称无效 400「字段无效：name」；同级重名 400「同名…已存在：…」；缺上级 400「必须指定所属学院/专业」；上级不存在 400「指定的…不存在：…」；班级与所选学院不一致 400「所选专业「软件工程」不属于该学院，请检查学院与专业的对应关系」（`OrganizationService.checkClassConsistency`，只在请求体同时给出学院与专业时校验）；学制越界 400「学制年限无效」；编号用尽 409「…编号已用尽」/ 上级号段非法 400「缺少所属学院/专业，无法生成编号」；无权限 403

#### POST `/organizations/delete`
```json
{"level": "COLLEGE", "id": "C01003", "force": true}
```
**响应：** `{"ok": true}`

- `force` 缺省为 `false`，表示不级联删除下级；为 `true` 时先递归删除下级再删除本级
- `force` **只**跳过「仍有下级」这一条确认，不跳过课程与账号引用检查
- 错误（全部 409）：该组织下仍有课程；仍有下级且未 `force`（提示「需确认级联删除」）；下级组织中仍有课程；仍被账号引用（提示先调整人员归属）
- 缺少 `id` 400「字段无效：id」；组织不存在 400

#### POST `/organizations/assign`
批量调整人员归属。学生只能进入班级，教师只能进入专业。

```json
{
  "level": "CLASS",
  "id": "B01002",
  "studentIds": ["20231530", "20231531"]
}
```
```json
{
  "level": "CLASS",
  "id": "B01002",
  "classId": "B01001"
}
```
```json
{
  "level": "MAJOR",
  "id": "M01001",
  "teacherIds": ["t1101"]
}
```
**响应：** `{"ok": true, "moved": 2}`，`moved` 等于本次事务写入的账号条数。

|参数|说明|
|---|---|
|`level`、`id`|目标组织层级与编号，必须已存在|
|`studentIds`|显式列出的学生账号，数组或逗号分隔字符串；仅 `level=CLASS` 允许|
|`teacherIds`|显式列出的教师账号，数组或逗号分隔字符串；仅 `level=MAJOR` 允许|
|`classId`/`class`|把该班级的**全部**学生整体调入目标班级（可与 `studentIds` 并用）；必须是已存在的班级**编号**（`requireOrganization` 只按 `id` 匹配，传名称会 400「指定的班级不存在」）|

- 三者至少要提供一个，否则 400「请指定要调整的学生或教师」
- 学生目标非班级 400「学生只能调整到班级」；教师目标非专业 400「教师只能调整到专业」
- 账号角色不符 400「只能调整学生/教师账号」；账号不存在 404「记录不存在」
- **管理员不归属任何组织**：无论把管理员列进 `studentIds` 还是 `teacherIds`，都先返回 400「管理员不归属学院/专业/班级」，不会写入任何归属字段
- 单次处理的账号数上限 `Models.MAX_BATCH_STUDENTS = 300`，超出 400「单次调整的账号数量过多」
- 写入内容包含 `college_id`/`major_id`/`class_id`、派生的 `department` 与递增后的 `version`，全部在同一个 `Protocol.Mutation` 中提交

---

### 3.8 网上选课

选课分为「教务发布批次」与「学生选课」两条线。批次保存在 `course_selections`，学生与教学班的关系保存在 `enrollments`，每次动作在 `enrollment_records` 留一条流水。批次状态只有三个：`OPEN`（进行中）、`CLOSED`（已结束）、`CANCELLED`（已取消）；**没有草稿态**，`POST /selections/save` 保存后立即是 `OPEN`。管理员界面上，本节接口与课程维护合并为「选课管理」页的三个标签页（课程与选课 / 选课批次 / 选课记录），按**课程**维度的批量选课走 3.2 节的 `POST /enrollments/batch`，按**批次**维度的批量选课仍是本节的 `POST /selections/batch`。

响应里的时间字段**同时给出 camelCase 与 snake_case 两种拼写**（`startTime`/`start_time`、`endTime`/`end_time`、`publishedBy`/`published_by`、`publishedAt`/`published_at`、`selectedAt`/`selected_at`、`createdAt`/`created_at`），选课记录同时返回 `courseId` 与 `course_id`，本人选课同时返回 `id` 与 `enrollmentId`：新前端统一用 camelCase，按数据库列名读取的调用点也能直接工作。状态另附 `statusName`/`actionName` 中文文案，前端不需要自己维护映射表。

**关于重修**：重修不是课程属性，而是**同一课程代码在后续学年重新开设**时的学生状态——两门课在系统里是同一门课（同一 `code`、不同 `term` 的教学班），课程名称、学分与权重保持一致，接口不下发任何带「重修」后缀的课程名。本节四处接口会给出重修信息：学生本人的 `/selections/my` 与 `/selections/available`、教师名单 `/roster`（`retake`/`retakeLabel`）、教务批次 `/selections`（课程级 `retakeCount`）。判定口径统一为「更早学期 + 同一 `code` + 成绩 `state=SUBMITTED` + 有效分 < 60」；成绩未出/未提交与已退课都不算。

#### GET `/selections`（SELECTION_ADMIN）
```http
GET /api/selections?status=OPEN&term=2026-1&page=1&size=100 HTTP/1.1
```
**查询参数：**

|参数|说明|
|---|---|
|`status`|按 `OPEN`/`CLOSED`/`CANCELLED` 精确过滤，缺省返回全部|
|`term`|按学期精确过滤|
|`page`、`size`|统一分页，默认 `1`/`100`|

**响应：** 统一分页对象，`items` 按学期降序、批次编号升序排列：

```json
{
  "items": [{
    "id": "0f2c…", "name": "2026-1 春季选课", "term": "2026-1",
    "status": "OPEN", "statusName": "进行中",
    "startTime": "2026-02-01T08:00", "endTime": "2026-02-28T23:59",
    "minEnroll": 10, "maxCredits": 30,
    "allowAdd": true, "allowDrop": true, "allowRetake": false,
    "note": "…", "publishedBy": "admin", "publishedAt": "2026-01-05T09:00:00Z", "version": 0,
    "courseIds": ["net-2026", "ai-2026"],
    "courses": [{"id":"net-2026","code":"CS301","name":"网络软件与安全","term":"2026-1","credits":3,"teacherName":"赵老师","collegeName":"信息工程学院","className":"2024级-软件工程-2401班","retakeCount":2}],
    "courseNames": ["网络软件与安全"],
    "courseCount": 2, "selectedCount": 7,
    "scopeCollegeIds": ["C01001"], "scopeCollegeNames": ["信息工程学院"],
    "scopeMajorIds": [], "scopeMajorNames": [],
    "scopeClassIds": [], "scopeClassNames": [],
    "scopeNames": ["信息工程学院"], "scopeLabel": "信息工程学院"
  }],
  "total": 3, "page": 1, "size": 100
}
```
- `courseCount` 是仍然存在的课程数（课程被物理删除时会小于 `courseIds` 长度）
- `courses[]` 里每门课程都带 `retakeCount`(int)：本批次中**该课程有重修状态的学生数**（按「该生在本课程号的更早学期成绩已提交且有效分 < 60」判定，`SelectionService.FailedCodes`）。教务据此知道这门课里有学生在重修，而课程名与课程行本身不带任何重修标记；该字段只统计 `ACTIVE` 选课行
- `startTime`、`endTime`、`publishedBy`、`publishedAt` 同时以 `start_time`、`end_time`、`published_by`、`published_at` 返回
- `selectedCount` 统计该批次下**所有** `enrollments` 行（含 `DROPPED`），因此它是「历史上被选过的人次」，不是当前在册人数；当前在册人数在 `/selections/available` 的课程级 `enrolled` 字段
- `scopeLabel` 在范围为空时返回「全校不限」

#### GET `/selections/available`
学生视角的可选批次列表。**返回数组而不是分页对象**，排序按 `endTime` 升序（最先截止的批次排在最前）。

```json
[{
  "id": "0f2c…", "name": "2026-1 春季选课", "status": "OPEN", "statusName": "进行中",
  "startTime": "…", "endTime": "…", "minEnroll": 10, "maxCredits": 30,
  "allowAdd": true, "allowDrop": true, "allowRetake": false, "note": "…",
  "scopeLabel": "信息工程学院",
  "courses": [{
    "id": "net-2026", "code": "CS301", "name": "网络软件与安全", "term": "2026-1", "credits": 3,
    "teacherName": "赵老师", "collegeName": "信息工程学院", "className": "2024级-软件工程-2401班",
    "selected": false, "enrolled": 5, "eligible": true, "reason": null,
    "retake": true, "retakeLabel": "重修"
  }]
}]
```
- 只返回 `status=OPEN` 且**当前时间落在窗口内**的批次；未开始或已结束的批次直接不出现在列表里（`requireWindow` 抛出的异常被转为「跳过该批次」，不是报错）
- 学生账号还会按 `OrganizationService.inScope` 过滤：不在范围内的批次整个不返回
- 每门课程的 `eligible`/`reason` 由 `checkSelectable` 用与真正选课**完全相同**的规则计算，因此页面上显示「可选」就一定选得上，显示原因时（如「该课程此前已通过，不能重复修读」「超出学分上限：…」）就是真正选课时会拿到的 409/403 文案
- `selected` 表示本人是否已选上；`enrolled` 是当前 `ACTIVE` 选课人数
- `retake`(boolean) / `retakeLabel`（`"重修"` 或 `null`）：本人此前在**同一课程代码**的更早学期挂过科（成绩已提交且有效分 < 60），因此这门课本次属于重修。**课程名称与重修无关**——重修只是同一课程代码在后续学年重新开设的教学班，前端只加「重修」徽标、不改课程名；成绩未出或未提交（正在修读）以及已退课的历史都不算重修
- 需要 `SELECTION_ENROLL` 或 `SELECTION_ADMIN`；持 `SELECTION_ADMIN` 的管理员可预览批次，但跳过「仅学生可选」「选课范围」两条学生级规则

#### GET `/selections/my`（SELECTION_ENROLL）
返回本人选课记录数组（不分页），按 `selectedAt` 降序、只含 `status=ACTIVE` 的行：

```json
[{
  "enrollmentId": "e1", "courseId": "net-2026",
  "code": "CS301", "name": "网络软件与安全", "term": "2026-1", "credits": 3,
  "teacherName": "赵老师", "collegeName": "信息工程学院", "className": "2024级-软件工程-2401班",
  "publishId": "0f2c…", "source": "SELECTION", "selectedAt": "2026-02-03T10:12:00Z", "status": "ACTIVE",
  "retake": true, "retakeLabel": "重修"
}]
```
- `source` 取值：`SELECTION`（学生自选）、`ADMIN`（教务代选）、`SEED`（演示数据初始化）
- 每项同时带 `id`（等于 `enrollmentId`）、`course_id`（等于 `courseId`）与 `selected_at`（等于 `selectedAt`），三种写法都是同一个值
- `retake`(boolean) / `retakeLabel`（`"重修"` 或 `null`）：本人在**同一课程代码**的更早学期有「成绩已提交且有效分 < 60」的修读记录，因此本学期这条是重修（`SelectionService.failedCodes` 一次算好本人全部学期的选课与成绩）。**课程名保持原样**：重修只是同一课程代码在后续学年重新开设的教学班，接口不下发任何带「重修」后缀的课程名，前端用徽标展示状态
- 退课与自动退回后的行不会出现在这里（`status=DROPPED`），但仍在数据库与流水中；已退课的历史也不参与重修判定

#### GET `/selections/records`（SELECTION_ADMIN）
```http
GET /api/selections/records?publishId=0f2c&courseId=net-2026&studentId=20241530&action=SELECT&page=1&size=100 HTTP/1.1
```
**查询参数：** `publishId`、`courseId`、`studentId`、`action`、`page`、`size`（前四个均可省略，`action` 必须是 `Models.ENROLLMENT_ACTIONS` 之一，否则 400「未知的选课动作：…」）。

**响应：** 统一分页对象，按 `createdAt` 降序、`id` 升序：

```json
{
  "items": [{
    "id": "r1", "publishId": "0f2c…", "courseId": "net-2026",
    "studentId": "20241530", "studentName": "许清和",
    "courseName": "网络软件与安全", "code": "CS301", "term": "2026-1",
    "action": "SELECT", "actionName": "选课",
    "reason": null, "operator": "20241530", "createdAt": "2026-02-03T10:12:00Z"
  }],
  "total": 12, "page": 1, "size": 100
}
```
- `actionName`：`SELECT`→选课、`DROP`→退课、`ADMIN_ASSIGN`→教务选入、`ADMIN_REMOVE`→教务退课、`AUTO_REFUND`→自动退回
- `operator` 是实际操作人账号：学生选退课是学生本人，教务代选代退是管理员，自动结算是 `SYSTEM`
- `createdAt` 同时以 `created_at` 返回；`courseId` 同时以 `course_id` 返回
- `code` 优先取流水里保存的课程代码，课程已被删除时仍可读

#### POST `/selections/save`（SELECTION_ADMIN）
新建或修改批次。**提交 `id` 表示修改，省略 `id` 表示新建**；新建后状态即为 `OPEN`。

```json
{
  "name": "2026-1 春季选课",
  "term": "2026-1",
  "courseIds": ["net-2026", "ai-2026"],
  "scopeCollegeIds": ["信息工程学院"],
  "scopeMajorIds": [],
  "scopeClassIds": [],
  "startTime": "2026-02-01T08:00",
  "endTime": "2026-02-28T23:59",
  "minEnroll": 10,
  "maxCredits": 30,
  "allowAdd": true,
  "allowDrop": true,
  "allowRetake": false,
  "note": "仅信息工程学院"
}
```
**响应：** `{"ok": true, "id": "0f2c…"}`

|字段|必填|约束|
|---|---|---|
|`id`|修改时必填|必须已存在，否则 404「选课发布不存在」|
|`name`|是|非空，≤100|
|`term`|是|必须匹配 `20\d{2}-[12]`，否则 400「学期格式应为 2026-1」|
|`startTime`/`endTime`|是|支持 `2026-02-01T08:00`、`…T08:00:00`、`…T08:00:00Z`、`2026-02-01 08:00`；解析失败 400「时间格式错误」；`start` 不早于 `end` 时 400「开始时间必须早于结束时间」|
|`minEnroll`|是|整数 1–1000，否则 400「最低开课人数必须为 1–1000 的整数」|
|`maxCredits`|否|整数 0–40，缺省 `0` 表示不限；越界 400「学分上限必须为 0–40」|
|`allowAdd`|否|缺省 `true`；接受布尔或 `1`/`0`/`"true"`/`"false"`|
|`allowDrop`|否|缺省 `true`|
|`allowRetake`|否|缺省 `false`|
|`note`|否|≤500|
|`courseIds`|是|数组、逗号分隔字符串或单值；也可用 `courseNames`/`courses`。每项按「编号 → 名称 → 课程代码」解析（`SelectionService.findCourse`）|
|`scopeCollegeIds`|否|可写 `scopeColleges`；每项接受组织编号或名称，空表示不限|
|`scopeMajorIds`|否|同上，别名 `scopeMajors`|
|`scopeClassIds`|否|同上，别名 `scopeClasses`|

选课发布的前置校验（任一不满足即整体拒绝，不会部分写入）：

|校验|响应|
|---|---|
|课程不存在|400「课程不存在：…」|
|同一批次里重复选了同一门课|400「课程重复：…」|
|课程学期与批次学期不一致|400「课程学期与选课学期不一致：课程名」|
|课程 `status=CANCELLED`|409「已停开课程不能发布选课」|
|**该课程已有教师录入成绩**（`grades` 表存在任意状态的行，含 `DRAFT`）|409「该课程已有教师录入成绩，不能发布选课」|
|该课程已存在于其他进行中的发布|409「该课程已存在于其他进行中的选课发布」|
|范围里的组织不存在|400「指定的学院/专业/班级不存在：…」|

修改已有批次时保留原状态与发布人、`version+1`；只有 `OPEN` 批次才参与「同一课程不能出现在两个进行中发布」的冲突检查。写入动作记为 `SELECTION_PUBLISH`。

#### POST `/selections/close`（SELECTION_ADMIN）
```json
{"id": "0f2c…"}
```
**响应：** `{"ok": true, "id": "0f2c…"}`

只允许 `OPEN → CLOSED`，其他状态 409「选课未开放」；批次不存在 404「选课发布不存在」；缺少 `id` 400「缺少选课发布编号」。关闭后学生不能再选课（`/selections/select` 因 `requireOpen` 409），**也不能再退课**（`/selections/drop` 同样要求 `OPEN`）。动作记为 `SELECTION_CLOSE`。

#### POST `/selections/cancel`（SELECTION_ADMIN）
```json
{"id": "0f2c…", "reason": "教务调整开课计划"}
```
**响应：** `{"ok": true, "id": "0f2c…", "refunded": 7}`

整批作废：该批次所有 `ACTIVE` 选课记录被逐条置为 `DROPPED`，并写入 `AUTO_REFUND` 流水，`reason` 为「选课已取消」或「选课已取消：<管理员填写的原因>」，操作人是发起取消的管理员；最后批次状态置为 `CANCELLED`。整个过程是一次 `Protocol.Mutation`。已取消的批次重复取消返回 409「选课已取消」。动作记为 `SELECTION_CANCEL`。

#### POST `/selections/settle`（SELECTION_ADMIN）
```json
{"id": "0f2c…"}
```
**响应：**
```json
{
  "ok": true,
  "cancelled": [
    {"courseId": "ai-2026", "code": "CS403", "name": "人工智能导论", "selected": 3, "minEnroll": 10}
  ],
  "refunded": 3
}
```
结算逻辑（`SelectionService.settleBatch`）：

1. 只允许对 `OPEN` 批次结算，否则 409「选课未开放」；
2. 按批次的 `course_ids` 建桶，只统计 `publish_id` 等于本批次且 `status=ACTIVE` 的选课记录；
3. 人数 **小于** `min_enroll` 的课程判定为不开课：逐条把选课记录置为 `DROPPED`、写 `AUTO_REFUND` 流水（原因「课程X未达到最低开课人数N，已自动退回」）、把课程 `status` 置为 `CANCELLED` 并 `version+1`；
4. 最后把批次状态置为 `CLOSED`。

`cancelled` 的每一项含 `courseId`、`code`、`name`、`selected`、`minEnroll`；没有任何课程被取消时是空数组，`refunded` 为 `0`。动作记为 `SELECTION_SETTLE`。

> **自动结算**：`SelectionService.autoSettleExpired` 带 `@Scheduled(initialDelay = 15000, fixedDelay = 60000)`，每分钟扫描一次 `status=OPEN` 且 `endTime` 已过的批次，调用与上面**完全相同**的 `settleBatch`，操作人记为 `SYSTEM`。因此窗口结束后即使管理员不点结算，不足人数的课程也会自动退回。该任务整体 try/catch，单个批次失败只打印日志，不影响其他批次与调度线程。

#### POST `/selections/select`（SELECTION_ENROLL）
```json
{"publishId": "0f2c…", "courseId": "net-2026"}
```
**响应：** `{"ok": true}`

校验顺序固定（每条失败都会中断，不会产生半条写入）：

|顺序|校验|响应|
|---|---|---|
|1|批次存在|400「缺少选课发布编号」/ 404「选课发布不存在」|
|2|批次 `status=OPEN`|409「选课未开放」|
|3|当前时间在窗口内|400「选课尚未开始」/ 400「选课已结束」|
|4|`allow_add=1`|403「本次选课不允许选课」|
|5|角色是 `STUDENT`|403「仅学生可选课」|
|6|账号 `enabled=1`|403「账号已停用」|
|7|落在选课范围内|403「你不在此次选课范围内」|
|8|课程在批次的 `course_ids` 中|400「该课程不在本次选课范围内」|
|9|课程存在|404「课程不存在」|
|10|课程未停开|409「课程已停开」|
|11|课程学期等于批次学期|409「课程学期与选课学期不一致」|
|12|未重复选同一教学班|409「已选修该课程」|
|13|本学期没有同课程代码的其他教学班（除非此前挂科且 `allowRetake`）|409「本学期已选择同一课程代码的其他教学班」|
|14|该课程代码此前未通过|409「该课程此前已通过，不能重复修读」|
|15|此前挂科时必须 `allowRetake=1`|403「本次选课不允许重修」|
|16|本学期已选学分 + 本课程学分 ≤ `maxCredits`|409「超出学分上限：本学期已选 X 学分，本课程 Y 学分，上限 Z 学分」|

选课成功时：已经退过的记录会被**复用**（`UPDATE` 回 `ACTIVE` 并刷新 `source`/`publish_id`/`selected_at`），没有记录才 `INSERT`；随后写入一条 `SELECT` 流水，操作人是学生本人。`source` 固定为 `SELECTION`。动作记为 `SELECTION_SELECT`。

修读历史的判定只统计**仍然 `ACTIVE`** 的选课记录：已退课的记录不算「修过」；更晚学期的记录不算「此前修读」；成绩行必须 `state=SUBMITTED` 且能算出有效分才参与「通过 / 挂科」判定，未出成绩既不算通过也不算挂科。

#### POST `/selections/drop`
学生退本人的课；持 `SELECTION_ADMIN` 的管理员可通过 `studentId` 代退。

```json
{"publishId": "0f2c…", "courseId": "net-2026", "reason": "课程冲突"}
```
**响应：** `{"ok": true}`

- 需要 `SELECTION_ENROLL` 或 `SELECTION_ADMIN`
- 批次 `CANCELLED` → 409「选课已取消」；`allow_drop=0` → 403「本次选课不允许退课」
- **不校验时间窗口**：批次处于 `OPEN` 或 `CLOSED` 都能退，只有取消后不能退
- 非管理员传 `studentId` 会被拒绝：403「只能退选本人的课程」
- 本人没有该课程的 `ACTIVE` 选课记录 → 404「没有该课程的选课记录」
- 该课程该学生已有成绩行（任意状态）→ 409「教师已录入成绩，不能退课」
- 退课只把 `status` 改成 `DROPPED`，**不删除行**，并写一条 `DROP` 流水（`reason` 可选，操作人 `u.id()`）。动作记为 `SELECTION_DROP`

#### POST `/selections/batch`（SELECTION_ADMIN）
按班级或显式名单批量代选 / 代退。

```json
{"publishId": "0f2c…", "courseId": "net-2026", "className": "2024级-软件工程-2401班"}
```
```json
{"publishId": "0f2c…", "courseId": "net-2026", "studentIds": ["20241530"], "remove": true}
```
**响应：**
```json
{
  "ok": true,
  "added": 6, "skipped": 2, "removed": 0,
  "failed": [{"studentId": "20241530", "reason": "超出学分上限：本学期已选 28 学分，本课程 3 学分，上限 30 学分"}]
}
```

|参数|说明|
|---|---|
|`publishId`、`courseId`|必填；批次必须 `OPEN`，课程必须存在|
|`remove`|缺省 `false`；为 `true` 时是批量代退，要求 `allow_drop=1`|
|`classId`/`className`/`class`|班级编号或名称（`resolveOwn` 解析），取该班**全部启用学生**；班级不存在 400「指定的班级不存在」|
|`studentIds`|显式名单，数组或逗号分隔字符串；班级解析不到学生时才使用它|
|`reason`|≤500；缺省时流水原因写「教务批量选课」或「教务批量退课」|

- 班级与学生名单都没有 → 400「必须指定班级或学生名单」；去重后人数超过 `Models.MAX_BATCH_STUDENTS`（300）→ 400「单次批量操作最多 300 名学生」
- **跳过时间窗口**（教务代选可以在窗口外执行），其余规则与单个 `select`/`drop` 完全一致
- 已经选过的学生在代选时计入 `skipped` 而不是报错；代退时没有 `ACTIVE` 记录同样计入 `skipped`
- 其他规则失败（超出学分上限、已通过、不允许重修等）逐个记录到 `failed`，**不影响其他学生**
- 成功与失败的学生在同一次 `repo.mutate` 中提交所有成功项，动作记为 `SELECTION_BATCH`；流水动作是 `ADMIN_ASSIGN`/`ADMIN_REMOVE`

---

## 四、内部 RPC 协议

### 4.1 查询接口（Data Service）
```json
{
  "table": "grades",
  "fields": ["id", "course_id", "student_id", "payload", "state", "version"],
  "where": {"course_id": "net-2026"},
  "orderBy": "id",
  "offset": 0,
  "limit": 500
}
```
**响应：** `String[][]`，列顺序与 fields 一致。

### 4.2 操纵接口
```json
{
  "operations": [
    {
      "type": "UPDATE",
      "table": "courses",
      "values": {"version": 1},
      "where": {"id": "net-2026", "version": 0},
      "expectedCount": 1
    }
  ],
  "actor": "teacher1",
  "action": "SUBMIT",
  "resource": "net-2026",
  "requestId": "corr-id-1"
}
```
- 单事务 1–500 条语句
- audits 表禁止直接修改
- 成绩更新自动加密

### 4.3 服务注册与发现（Gateway）
```json
// 注册
{"service": "business", "instance": "inst-1", "url": "https://localhost:9441"}

// 发现
{"service": "business"} → {"url": "https://localhost:9441"}
```

### 4.4 白名单表与字段（含本轮新增）

`RemoteRepository.FIELDS` 是业务服务唯一允许访问的表与列清单，字段顺序即 `Selection.fields` 的顺序，也是 `String[][]` 响应的列顺序。数据服务用 `SchemaCatalog` 再校验一次表名与列名，两层白名单必须同时命中。

|表|字段|说明|
|---|---|---|
|`users`|`id, username, password, name, role, permissions, department, college_id, major_id, class_id, enabled, version`|新增 `college_id`/`major_id`/`class_id` 三级归属；`department` 为派生展示字段|
|`courses`|`id, code, name, term, teacher_id, credits, weights, college_id, class_id, status, version`|新增 `college_id`（开设院系）、`class_id`（面向班级，可空）、`status`（`ACTIVE`/`CANCELLED`）|
|`enrollments`|`id, course_id, student_id, source, publish_id, selected_at, status`|新增 `source`（来源）、`publish_id`（选课批次，可空）、`selected_at`（选课时间）、`status`（`ACTIVE`/`DROPPED`）|
|`grades`|`id, course_id, student_id, payload, state, version`|不变；`payload` 在数据服务按 AAD 解密|
|`analyses`|`id, course_id, content, version`|不变|
|`sessions`|`id, user_id, csrf, expires`|不变|
|`login_limits`|`id, failures, locked_until`|不变|
|`colleges`|`id, name, code, short_name, description, enabled, version`|**新增**。`id` 为 `C` + 5 位数字，就是组织编号；`code` 为遗留列（第三轮起不再由接口读写）|
|`majors`|`id, college_id, name, code, degree, years, enabled, version`|**新增**。`id` 为 `M` + 5 位数字，就是组织编号；`college_id` 指向学院；`code` 为遗留列|
|`classes`|`id, major_id, college_id, name, grade_year, code, enabled, version`|**新增**。`id` 为 `B` + 5 位数字，就是组织编号；`college_id` 由 `major_id` 推导；**第二轮删除 `counselor` 列**（结构版本 3）；`code` 为遗留列|
|`course_selections`|`id, name, term, course_ids, scope_college_ids, scope_major_ids, scope_class_ids, start_time, end_time, min_enroll, max_credits, allow_add, allow_drop, allow_retake, status, published_by, published_at, note, version`|**新增**。选课批次；`course_ids` 与 `scope_*_ids` 为逗号分隔编号，空串表示不限|
|`enrollment_records`|`id, publish_id, course_id, code, student_id, term, action, reason, operator, created_at`|**新增**。选课动作流水，只增不改；`action` 取值见 `Models.ENROLLMENT_ACTIONS`|

新增表的内部查询要点：

- 三级组织之间的引用是编号字符串，不是外键；`OrganizationService.requireOrganization`/`resolveOwn` 在业务侧校验存在性，因此数据服务不需要联表。
- `colleges(name)`、`majors(college_id,name)`、`classes(major_id,name)` 是唯一索引，同级同名写入会被数据库直接拒绝；`OrganizationService.save` 在写入前先给出 400 的业务提示。
- 三张组织表还各有一个 `code` 列，是第二轮「显示编号」遗留的物理列：**表结构里保留它**（避免再触发一次整库重建），但第三轮起组织接口不再读写，`/organizations`、`/organizations/options` 的响应里也没有该键；`Models` 里的 `MAX_CODE`/`parseCode`/`formatCode` 同样已无调用方。
- `enrollments(course_id,student_id)` 仍是组合唯一索引，选课、教务代选与按课程批量选课共用同一条约束，因此「重复选课」既被服务端规则拦截，也有数据库兜底；按课程批量选课对已退课的行只能 `UPDATE`，不能 `INSERT`。
- `enrollments(publish_id,student_id)` 有普通索引，用于按批次统计已选人数与结算退回。
- `course_selections` 的 `course_ids`/`scope_*_ids`/`note` 按 `SchemaCatalog.sqlType` 建成大文本类型（H2 `CLOB`、MySQL `TEXT`、SQL Server `NVARCHAR(MAX)`），因此 `where` 只能做等值匹配，不能对集合列做包含查询——范围判定在业务服务用 `OrganizationService.split`/`inScope` 完成。
- 组织与选课的写入全部是标准 `INSERT`/`UPDATE`/`DELETE` 操作，没有新增远程方法，也没有绕开 `expectedCount` 检查的批量 SQL。

---

## 五、内部签名协议

服务间请求头：
| 头名 | 说明 |
|------|------|
| X-Service | 调用方服务名 |
| X-Time | 秒级时间戳 |
| X-Nonce | 随机字符串（≤100字符） |
| X-Signature | HMAC-SHA256(消息) |

**签名内容：**
```
METHOD
/internal/xxx
1700000000
abc123
<request_body_sha256>
```

时间窗 30 秒，Nonce 60 秒内去重。

**签名密钥来自动态密钥表，不是常量**：`RpcClient` 用 `Settings.get(caller.toUpperCase() + "_KEY")`（即 `X-Service` 的大写形态加 `_KEY`）取密钥，例如调用方 `business` 用 `BUSINESS_KEY`、`data` 用 `DATA_KEY`、`audit` 用 `AUDIT_KEY`、`gateway` 用 `GATEWAY_KEY`；服务间 HTTPS 的信任库口令则来自 `ConfigGuard.secret("TLS_PASSWORD")`。这四项由 `scripts/setup.mjs` 随机生成并注入（环境变量 / `-D` / `.runtime/secrets.json`），源码与 `application.yml` 里只有空占位 —— 因此**不存在可以写进配置文件里的固定密钥**，多实例部署时所有副本必须使用同一份密钥表。整库加密（`DB_CIPHER_KEY` / `DB_PASSWORD`）与这一层签名无关，也不改变任何请求头或请求体。

---

## 六、错误语义

| 状态码 | 含义 | 客户端处理 |
|--------|------|-----------|
| 400 | 格式、分数、系数校验失败 | 修正输入 |
| 401 | 会话失效或签名失败 | 重新登录 |
| 403 | 角色、归属、CSRF 拒绝 | 停止操作 |
| 404 | 记录不存在 | 刷新列表 |
| 409 | 版本冲突、状态错误、完整性错误 | 刷新；完整性错误联系管理员 |
| 413 | 请求或结果过大 | 减少批次 |
| 422 | 模型数据不足 | 补足历史数据 |
| 429 | 登录尝试过多 | 等待 5 分钟 |
| 503 | 服务不可用或审计待同步 | 等待重试 |
| 500 | 未预期故障 | 查看日志 |

组织与选课功能复用上表，其中最常出现的四类语义是：400 用于参数与规则前置条件（层级非法、时间格式、学期格式、范围不一致、窗口未开始/已结束）；403 用于权限与开关（缺 `ORG_ADMIN`/`SELECTION_ADMIN`/`SELECTION_ENROLL`、范围外、`allow_add=0`/`allow_drop=0`、不允许重修、只能退本人课程）；404 用于对象不存在（组织、批次、课程、选课记录）；409 用于状态与冲突（重名、仍有课程或账号、重复选课、同代码冲突、已通过、超学分、已取消、未开放、已有成绩）。

---

## 七、字段约定

- Web 请求使用 camelCase：`courseId`, `studentId`, `teacherId`, `courseVersion`
- 数据库列使用 snake_case：`course_id`, `student_id`, `teacher_id`
- `RemoteRepository` 负责转换；主键放 `where.id`，版本放 `where.version`
- 查询响应为 `{items, total, page, size}`，页从 1 开始，单页 1–300
- 组织字段双向兼容：请求可用编号或名称（`collegeId`/`collegeName`/`college`、`majorId`/`majorName`/`major`、`classId`/`className`/`class`），响应统一附 `collegeName`/`majorName`/`className`；`level` 参数取 `COLLEGE`/`MAJOR`/`CLASS`
- 组织只有一个编号：主键 `id`（`C`/`M`/`B` + 5 位），用于引用、寻址与页面展示；`resolveOwn` 按 `id` 与 `name` 解析组织，组织表遗留的 `code` 列不参与读写，任何响应都不返回它。`POST /organizations/save` 的响应是 `{ok: true, id}`，请求体里的 `code` 与多余的 `id` 都不会改写主键
- 选课接口的时间字段同时提供 camelCase 与 snake_case 两种拼写（`startTime`/`start_time`、`endTime`/`end_time`、`publishedAt`/`published_at`、`selectedAt`/`selected_at`、`createdAt`/`created_at`），`courseId`/`course_id`、`id`/`enrollmentId` 同理；请求体两种写法都接受（`startTime` 与 `start_time`、`endTime` 与 `end_time`）
- `/selections/available`、`/selections/my`、`/organizations/options`、`/users/teachers` 返回数组；其余列表接口返回分页对象
- 重修相关字段只有三个，都是**派生**值而不是数据库列：`retake`(boolean)、`retakeLabel`（`"重修"` / `null`）、`retakeCount`(int)。它们由更早学期的历史成绩推出（同一 `code`、`state=SUBMITTED`、有效分 < 60），课程名与课程行上没有任何重修标记；`retakeCount` 只出现在教务批次列表的 `courses[]` 里

---

## 八、成绩状态机

```
DRAFT ──SUBMIT──→ SUBMITTED
  ↑                  │
  └──WITHDRAW───────┘
  │
  └──SMALL_REVOKE / DELETE_ALL ──→ （删除）
```

- 补考成绩上限 60 分（effective），原始分保留在 payload
- 状态变更后重新加密，不能只更新状态列
