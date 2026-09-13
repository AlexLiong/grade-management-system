# 程序设计模型

## 模型与代码的一致性

完整类图在 [class-diagrams.md](class-diagrams.md)，由 JDK `JavacTask` 解析真实 Java 语法树产生；方法/字段清单在 [classes.md](classes.md)，JSON 清单在 `source-inventory.json`。执行 `node scripts/generate-docs.mjs --check` 可验证源码与文档是否一致。

本文件的 use case、状态、部署、时序图描述行为，不表示新增 Java 类。ER 图节点严格对应 `SchemaCatalog` 的八张实际数据表。代码采用远程值对象与 JDBC，并没有不存在的 `StudentEntity` 或 `GradeRepository` JPA 类。

## 用例模型

```mermaid
flowchart LR
  Teacher([教师]) --> Login(认证 / 改密)
  Student([学生]) --> Login
  Admin([管理员]) --> Login
  Teacher --> Weights(设置成绩系数)
  Teacher --> Entry(正考与补考暂存)
  Teacher --> OCR(OCR 识别 / 人工核对)
  OCR --> Entry
  Teacher --> Submit(整课提交)
  Teacher --> Withdraw(撤回成绩)
  Teacher --> Stats(统计 / 教学分析 / 打印)
  Teacher --> History(往年课程查询)
  Teacher --> Predict(主动生成学业预警)
  Student --> Predict
  Student --> Transcript(本人学期 / 在校成绩)
  Student --> Rank(本人课程排名)
  Student --> Print(打印成绩单)
  Admin --> Revoke(小撤销 / 大撤销)
  Admin --> Users(组织人员维护 / 权限分配)
  Admin --> Course(课程及选课维护)
  Admin --> Integrity(独立成绩证据 / 完整性核查)
  Admin --> Audit(操作复核 / LSTM 日志检测)
```

## 核心类协作图

```mermaid
classDiagram
  GatewayController --> RegistryController
  GatewayController --> RpcClient
  ApiController --> AuthService
  ApiController --> CourseService
  ApiController --> GradeService
  ApiController --> AnalyticsService
  ApiController --> AdminService
  GradeService --> CourseService
  GradeService --> AnalyticsService
  GradeService --> RemoteRepository
  AuthService --> RemoteRepository
  CourseService --> RemoteRepository
  AnalyticsService --> RemoteRepository
  AdminService --> RemoteRepository
  RemoteRepository --> RpcClient
  DataRpcController ..|> SelectInterface
  DataRpcController ..|> ManipulationInterface
  DataRpcController --> TransactionService
  TransactionService --> SqlCompiler
  TransactionService --> SchemaCatalog
  AuditController --> LedgerService
  LedgerService --> RpcClient
```

图中 `SelectInterface`、`ManipulationInterface` 为 `Protocol` 的真实嵌套接口。它们使用 HTTP JSON Webservice 进行值传递，Java 业务对象不共享进程内存。

## 成绩状态图

```mermaid
stateDiagram-v2
  [*] --> Absent
  Absent --> DRAFT: 授课教师暂存
  DRAFT --> DRAFT: 修改 / 校验 / 版本+1
  DRAFT --> SUBMITTED: 全部学生成绩完整后提交
  SUBMITTED --> DRAFT: 教师撤回 / 管理员小撤销
  SUBMITTED --> Absent: 管理员大撤销
  note right of SUBMITTED
    学生可查询
    禁止改分与修改系数
    关键操作写审计和链锚定
  end note
  note right of Absent
    大撤销只删除业务行
    原始快照保留在独立账本
  end note
```

`Absent` 是业务行不存在的概念状态，并非数据库枚举值；真实 `grades.state` 仅 DRAFT / SUBMITTED。

## 提交与审计时序

```mermaid
sequenceDiagram
  actor Teacher as 教师
  participant Vue as App.vue
  participant Gateway as GatewayController
  participant API as ApiController
  participant Auth as AuthService
  participant Grades as GradeService
  participant Repo as RemoteRepository
  participant Data as TransactionService
  participant DB as 关系数据库
  participant Ledger as LedgerService
  participant EVM as EVM Worker
  Teacher->>Vue: 提交全部成绩
  Vue->>Gateway: HTTPS /api/grades/transition + CSRF
  Gateway->>API: HMAC 签名请求信封
  API->>Auth: 会话 / 权限 / CSRF
  Auth-->>API: User
  API->>Grades: transition
  Grades->>Repo: 查询归属、选课、权重、版本
  Repo->>Data: Selection
  Data-->>Repo: String[][]
  Grades->>Repo: Mutation(课程版本 + 全班成绩更新)
  Repo->>Data: HTTPS ManipulationInterface
  Data->>Ledger: 校验账本及待同步状态
  Data->>DB: BEGIN
  Data->>DB: CAS 更新 + AES-GCM 成绩 + 审计发件箱
  alt 版本不符或任何 SQL 失败
    Data->>DB: ROLLBACK
    Data-->>Vue: 409，刷新重试
  else 事务成功
    Data->>DB: COMMIT
    Data->>Ledger: 发送审计事件
    Ledger->>EVM: 锚定密文快照哈希
    EVM-->>Ledger: 成功交易回执
    Ledger->>Ledger: JSONL append + fsync
    Ledger-->>Data: 事件已记录 / 重复事件复用
    Data->>DB: 发件箱 delivered=1
    Data-->>Vue: true
  end
```

## 查询与预测时序

```mermaid
sequenceDiagram
  actor Student as 学生
  participant API as ApiController
  participant Model as AnalyticsService
  participant Course as CourseService
  participant Repo as RemoteRepository
  participant Weka as Weka LinearRegression / REPTree
  Student->>API: POST /predict
  API->>Model: predict(User, courseId)
  Model->>Course: PREDICT 权限 / 选课归属
  Model->>Repo: 同课程代码且早于目标学期的已提交历史
  Repo-->>Model: 解密后的完整历史样本
  alt 少于三年或 24 条
    Model-->>Student: 422 数据不足
  else 样本足够
    Model->>Weka: 历史训练 / 留后一年验证
    Model->>Repo: 查询本人当前课程部分成绩
    Weka-->>Model: 两模型预测 / RMSE / 树结构
    Model-->>Student: 本人临时预测结果
  end
  Note over Model,Repo: 不调用 Mutation，不写正式成绩表
```

## 数据库 ER 图

```mermaid
erDiagram
  users ||--o{ courses : teaches
  users ||--o{ enrollments : studies
  courses ||--o{ enrollments : contains
  users ||--o{ grades : owns
  courses ||--o{ grades : assesses
  courses ||--o| analyses : explains
  users ||--o{ sessions : authenticates
  users {
    varchar id PK
    varchar username UK
    varchar password
    varchar name
    varchar role
    varchar permissions
    varchar department
    integer enabled
    integer version
  }
  courses {
    varchar id PK
    varchar code
    varchar name
    varchar term
    varchar teacher_id
    decimal credits
    varchar weights
    integer version
  }
  enrollments {
    varchar id PK
    varchar course_id
    varchar student_id
  }
  grades {
    varchar id PK
    varchar course_id
    varchar student_id
    clob payload
    varchar state
    integer version
  }
  analyses {
    varchar id PK
    varchar course_id UK
    clob content
    integer version
  }
  sessions {
    varchar id PK
    varchar user_id
    varchar csrf
    varchar expires
  }
  audits {
    varchar id PK
    clob payload
    integer delivered
  }
  login_limits {
    varchar id PK
    integer failures
    varchar locked_until
  }
```

关系线为业务引用，当前初始化 DDL 未声明 FOREIGN KEY。`enrollments(course_id,student_id)` 与 `grades(course_id,student_id)` 有组合唯一索引。`sessions.id` 保存会话令牌 SHA-256；`login_limits.id` 保存登录账号 SHA-256；`grades.payload`、`audits.payload` 为 AES-GCM 密文。`weights` 和成绩载荷为结构化 JSON，具体成绩项在 [API 文档](api.md) 中定义。

## 故障响应活动图

```mermaid
flowchart TD
  A[敏感维护请求] --> B{待发送审计为零?}
  B -->|否| C[尝试重新发送]
  C --> D{同步成功?}
  D -->|否| E[503 暂停敏感写入]
  D -->|是| F[验证独立账本与 EVM]
  B -->|是| F
  F --> G{完整性通过?}
  G -->|否| E
  G -->|是| H[数据库事务 / 版本检查]
  H --> I{所有操作成功?}
  I -->|否| J[完整回滚]
  I -->|是| K[提交并发送审计]
  K --> L[失败保留发件箱 / 下次阻断写入]
```
