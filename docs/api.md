# HTTP API 与远程命名约定

> 更新时间：2026-09-13

浏览器基址 `https://localhost:8443/api`，UTF-8 JSON。除登录外，所有接口需要会话 Cookie；所有 POST 还需 `X-CSRF-Token`。API 对不允许的角色返回 403，对会话缺失/过期返回 401。公开入口不接受 table、SQL 或任意调用服务 URL。

---

## 一、模块划分

| 模块 | 职责 | 端口（默认） |
|------|------|-------------|
| `gateway` | HTTPS 统一入口、服务注册发现、静态页面 | 8443 |
| `business-service` | 认证、权限、课程成绩流程、统计分析 | 9442（内部） |
| `data-service` | SQL 编译、参数绑定、事务、成绩加密、审计发件箱 | 9443（内部） |
| `audit-service` | 独立加密账本、哈希链、EVM 锚定 | 9444（内部） |
| `chain-worker` | Ganache EVM 测试链、LSTM 推理 | 9445（内部） |

网关只接受 GET/POST，业务路径 `/api/*` 转发至 business-service 的 `/internal/api`。各服务间通过 HMAC 签名 + 时间窗 + Nonce 认证。

---

## 二、Web 接口总览

| 方法 | 路径 | 权限 | 简述 |
|------|------|------|------|
| POST | `/login` | 公开 | 登录，返回会话 Cookie + CSRF |
| GET | `/me` | 已登录 | 当前用户信息 |
| POST | `/logout` | 已登录 | 登出 |
| POST | `/password` | 已登录 | 修改密码 |
| GET | `/courses` | QUERY/GRADE_ADMIN/AUDIT | 课程列表（分页） |
| GET | `/roster` | QUERY/GRADE_ADMIN | 课程选课名单 |
| GET | `/grades` | QUERY/GRADE_ADMIN | 成绩列表（分页） |
| POST | `/grades/save` | ENTRY（教师） | 批量暂存成绩 |
| POST | `/grades/transition` | MAINTAIN/GRADE_ADMIN | 提交/撤回/撤销 |
| POST | `/weights` | MAINTAIN（教师） | 设置六项系数 |
| GET | `/statistics` | QUERY/GRADE_ADMIN | 班级统计与异常 |
| POST | `/analysis` | MAINTAIN（教师） | 保存教学分析文本 |
| POST | `/predict` | PREDICT | 学业预测（不落库） |
| GET | `/transcript` | QUERY（学生） | 本人在校成绩 |
| GET | `/users` | USER_ADMIN | 人员列表（分页） |
| POST | `/users/save` | USER_ADMIN | 新增/修改人员 |
| POST | `/courses/save` | GRADE_ADMIN | 新增/修改课程 |
| POST | `/enrollments` | GRADE_ADMIN | 选课/退选 |
| GET | `/audit` | AUDIT | 独立账本读取 |
| GET | `/integrity` | AUDIT | 成绩核查 |
| POST | `/audit/classify` | AUDIT | LSTM 日志检测 |
| POST | `/audit/review` | AUDIT | 复核事件 |
| GET | `/status` | ADMIN | 待同步审计数量 |

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

#### GET `/roster`
```http
GET /api/roster?courseId=c1 HTTP/1.1
```
返回选课学生列表（id, name, username）。

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
- 使用 Weka LinearRegression + REPTree 训练
- 需要至少 3 年、24 条完整历史成绩
- 返回预测区间：`predictedTotal ± 1.96 × RMSE × finalExam权重`
- **不写入数据库**

#### GET `/transcript`（学生）
返回本人在校所有已提交成绩的列表，含总评、有效分、排名、是否挂科。

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
  "department": "计算机学院",
  "enabled": 1,
  "password": "StrongPass123"
}
```
- 角色：TEACHER / STUDENT / ADMIN
- 角色确定后不可变更
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
{"service": "business", "instance": "inst-1", "url": "https://localhost:9442"}

// 发现
{"service": "business"} → {"url": "https://localhost:9442"}
```

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

---

## 七、字段约定

- Web 请求使用 camelCase：`courseId`, `studentId`, `teacherId`, `courseVersion`
- 数据库列使用 snake_case：`course_id`, `student_id`, `teacher_id`
- `RemoteRepository` 负责转换；主键放 `where.id`，版本放 `where.version`
- 查询响应为 `{items, total, page, size}`，页从 1 开始，单页 1–300

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
