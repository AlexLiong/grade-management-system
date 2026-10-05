import fs from "node:fs";
import path from "node:path";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const run = spawnSync(
  "java",
  [path.join(root, "scripts/SourceInventory.java"), root],
  { encoding: "utf8" },
);
if (run.status !== 0) throw new Error(run.stderr);
const types = JSON.parse(run.stdout);
const descriptions = {
  Protocol:
    "远程值传递契约容器。查询与操纵分开定义，字段顺序显式传递，不允许浏览器携带 SQL。",
  Settings:
    "运行配置读取器。环境变量优先，缺少配置时拒绝启动；初始化工具生成随机密钥和密码。server 配置 TLS 和回环地址，json 统一序列化。",
  ConfigGuard:
    "认证密钥与数据库口令的统一入口：从环境变量/启动参数/密钥文件读取，开发档缺失时随机生成 32 字节并写入 .runtime/secrets.json；生产档（CAMPUS_PROFILE=prod）缺密钥、仍是占位值或长度不足即拒绝启动。另计算 dataFingerprint 供结构表比对，并提供 H2 整库加密所需的两段式口令。",
  DatabaseBootstrap:
    "进程级引导，四个主类在 Spring 之前调用：先加载/注入密钥，再检查并清理『未加密的 H2 明文库』（开启 CIPHER=AES 后旧明文库无法打开），使启动顺序完全确定。",
  ConfigEnvironmentPostProcessor:
    "在配置绑定之前把密钥与数据源凭据注入 Environment（环境变量与 -D 优先，密钥文件兜底），保证 IDEA 直接运行主类也能连上加密库；注册于 META-INF/spring.factories。",
  DbCredentials:
    "数据源 URL、用户名与会话口令的解析入口。口令我安全优先取环境变量/-D，否则由密钥表拼成 H2 要求的『文件口令 空格 用户口令』两段式；describePassword 只输出来源、长度与空格数，不泄露口令。",
  DataSourceConfig:
    "显式装配 Hikari 数据源，把整库加密的两段式口令直接交给 H2 驱动，绕开自动配置的取值链路（取到单段口令时 H2 只报 90050，极难定位）。",
  Crypto:
    "加密与完整性原语。AES-GCM 使用随机 96 位 nonce；AAD 绑定成绩身份、状态和版本；HMAC-SHA256 认证内部请求和账本；解密失败统一返回完整性错误。",
  ApiException:
    "可预期的业务异常，携带 HTTP 状态、稳定错误码和可展示说明。require 将业务前置条件变成失败响应。",
  ErrorAdvice:
    "跨控制器异常映射。业务错误保留状态；格式错误返回 400；内部错误仅暴露追踪编号，避免堆栈与 SQL 泄漏。",
  InternalSecurity:
    "所有 /internal 路径的服务间鉴权过滤器。限定调用方角色、30 秒时间窗、一次性 nonce、请求体大小与 HMAC；缓存请求体后继续 MVC 解析。",
  RpcClient:
    "Webservice 远程调用客户端。HTTPS 使用可信证书校验，连接/请求超时有上限；服务发现后签名调用；远端业务错误保持 HTTP 语义。",
  ServiceHeartbeat:
    "每五秒向网关续租，进程实例 UUID 区分副本；注册失败后下个周期重试，不阻止已有进程继续启动。",
  GatewayApplication: "网关启动入口，设置服务身份、默认端口 8443 和调度器。",
  RegistryController:
    "服务注册与发现。URL 白名单防 SSRF，20 秒租约清除过期实例，轮询选择；health 只公开服务名和实例编号。",
  GatewayController:
    "统一收集浏览器请求信封，校验来源/方法/长度，仅转发 Cookie 与 CSRF 等必要信息；内部签名由网关重新生成。",
  WebConfiguration:
    "将构建后的前端发布到 HTTPS 网关，并在嵌套过滤器中统一输出 CSP、HSTS、防嗅探、禁止嵌入与 no-store 头。",
  SystemController:
    "网关的系统状态页，直接把 classpath 下的 system/index.html 原样返回，供运维检查注册与心跳。",
  DataApplication:
    "独立数据访问进程入口。默认启动加密 H2 文件库，根据 DB_URL/DB_USER/DB_PASSWORD 切换外部数据库。",
  SchemaCatalog:
    "数据库结构、列名和类型白名单。主键、唯一索引、厂商大文本类型初始化；外部生产数据库建议通过迁移管理工具预建结构。",
  SqlCompiler:
    "将 Selection/Operation 编译为 SQL 与绑定参数列表。仅标识符进入 SQL 文本，按元数据把数字转换成整数/Decimal；更新删除必须有主键。",
  DataRpcController:
    "实现 SelectInterface 和 ManipulationInterface，分别暴露二维字符串查询和布尔事务操纵接口；内部签名过滤器先行鉴权。",
  TransactionService:
    "数据事务协调器。查询成绩时验证 AAD；批量维护时检查 expectedCount，写前/写后快照与数据库发件箱同事务持久化；flush 网络同步且按事件 ID 重试。",
  BusinessApplication:
    "业务服务启动入口，默认端口 9441；无 JDBC 配置和数据库驱动依赖。",
  RemoteRepository:
    "领域服务访问数据层的唯一客户端。维护字段顺序、循环分页、记录存在检查和操作构造；调用独立审计服务。",
  Models:
    "领域基础规则与转换。限定角色权限集合、有限数值、长度验证、成绩解析、加权总评和封顶补考有效分；User.require 进行功能权限断言。",
  AuthService:
    "BCrypt 密码认证、失败锁定、随机会话、CSRF、Cookie、停用校验和密码修改。会话存储在数据服务，可被多个业务副本共享；密码或权限更新撤销旧会话。",
  CourseService:
    "课程归属授权、学期/授课人/学分管理、六项权重及选课维护。权重改变拒绝破坏补考资格，课程版本与成绩操作协调。",
  GradeService:
    "成绩写入与状态机。只允许授课教师录入，校验选课、重复学生、范围、完整性、补考资格；课程/成绩双版本保护；学生成绩单仅本人已提交数据。",
  AnalyticsService:
    "成绩统计、教学分析、异常检测和学业预测。使用 Weka 成熟模型，隔离训练历史与当前数据；三年门槛、留后验证和隐私过滤；预测不落库。",
  AdminService:
    "人员组织与角色权限管理、账号停用/重置、审计复核和数据库完整性核对。独立账本快照提供数据库之外的原始成绩；禁止管理员获得个人预测结果。",
  ApiController:
    "网关内部业务信封路由。登录之外全部验证共享会话，POST 校验 CSRF，转入领域服务；拒绝访问形成独立审计事件；页面查询统一分页。",
  Routes:
    "领域路由注册表。每个业务域声明自己负责的路径并实现 dispatch，ApiController 只做信封解析、会话/CSRF 校验与命中分发，新增业务域无需修改分发代码。",
  CoreRoutes:
    "既有成绩、课程、统计、人员与审计路径的注册实现。迁移到路由注册表后行为保持不变，供新增域作为写法参照。",
  OrganizationService:
    "学院—专业—班级三级组织。库中以独立编号作主键互相引用，前端按名称操作：resolveOwn 把名称归一化成编号，names/enrichOwn 回填可读名称；负责编号生成、删除影响面与级联校验、选课范围匹配。",
  OrganizeRoutes:
    "组织管理的 HTTP 路由。提供学院/专业/班级的分页列表、下拉选项、影响面、成员、保存、删除与批量调整人员归属，全部写操作经统一事务进入审计。",
  SelectionService:
    "教务选课系统。发布选课信息（选课名、起止时间、选课范围、最低开课人数、可选可退），按十四条顺序规则校验学生选课，处理退课、按班级批量选课与最低开课人数结算自动退回；所有写操作写选课流水并进入审计。",
  SelectionRoutes:
    "选课系统的 HTTP 路由与定时任务。暴露发布、关闭、取消、结算、选课、退课、批量与流水查询路径，并在选课窗口结束后自动结算不满足最低开课人数的课程。",
  DemoInitializer:
    "演示数据初始化。结构版本变化或表为空时整库重建：写入多学院/多专业/多班级组织、按学院归属的师生账号、同学期课程代码唯一的教学班与历史成绩，并同步重建独立审计账本。",
  AuditApplication: "独立审计进程启动入口，默认 9443；不依赖业务数据库。",
  LedgerService:
    "独立文件账本与 EVM 双重验证。AES-GCM 保护快照，哈希/HMAC 绑定顺序；完整校验后读写，事件 ID 幂等；文件落盘 fsync；输出原始证据与日志分类。",
  AuditController:
    "签名审计 Webservice：追加、健康校验、读取已验证账本和主动 LSTM 分类。",
};
let doc =
  "# 各类详细功能说明\n\n由 `node scripts/generate-docs.mjs` 调用 JDK 编译器语法树生成成员清单。此处列出的均为 `src/main/java` 实际声明类型，包含 record 与命名内部类，不把第三方模型或数据库表伪写成本项目 Java 类。构造器以 `<init>` 表示。\n\n";
for (const t of types) {
  const outer = t.name.split(".")[0];
  doc += `## ${t.package}.${t.name}\n\n源码：[${t.file}](../${t.file})。类型：${t.kind}。\n\n${descriptions[outer] || "协议或组件的内部类型。"}\n\n`;
  if (t.name.includes("."))
    doc +=
      "此命名内部类型属于上面的组件职责；字段即值传递载荷或内部状态，成员清单以编译器解析结果为准。\n\n";
  if (t.fields.length)
    doc +=
      "字段：\n\n" + t.fields.map((f) => "- `" + f + "`").join("\n") + "\n\n";
  if (t.methods.length)
    doc +=
      "方法及构造器：\n\n" +
      t.methods.map((m) => "- `" + m + "`").join("\n") +
      "\n\n";
}
doc +=
  "## 前端与工具职责\n\n`App.vue` 维护角色可见视图、课程状态、成绩表草稿、模态框及交互，所有服务器结果通过 Vue 文本绑定渲染。`api.js` 是统一同源请求客户端，读取 CSRF Cookie 并映射失败。`server.mjs` 维护独立 EVM、串行锚定请求和 LSTM 模型。`setup.mjs` 生成证书/随机配置，`start.sh` 一键启动与停止全部服务，OCR 离线资源随源码置于 `frontend/public/ocr/`。测试脚本不属于业务运行入口。\n";
let uml =
  "# 与源码对应的完整类型图\n\n本文件由 JDK 语法树生成。每个节点对应一个真实命名类型；字段引用关系只表示代码依赖，不假造继承。完整方法签名见 classes.md。\n\n";
for (const pkg of [...new Set(types.map((t) => t.package))]) {
  uml += `## ${pkg}\n\n\`\`\`mermaid\nclassDiagram\n`;
  const group = types.filter((t) => t.package === pkg);
  for (const t of group) {
    const id = t.name.replaceAll(".", "_");
    uml += `    class ${id}["${t.name}"]\n`;
    if (t.kind === "INTERFACE") uml += `    <<interface>> ${id}\n`;
    if (t.kind === "RECORD") uml += `    <<record>> ${id}\n`;
    if (t.name.includes(".")) uml += `    ${t.name.split(".")[0]} *-- ${id}\n`;
  }
  for (const t of group)
    for (const other of group)
      if (t !== other && t.fields.some((f) => f.split(" : ")[1] === other.name))
        uml += `    ${t.name.replaceAll(".", "_")} --> ${other.name.replaceAll(".", "_")}\n`;
  uml += "```\n\n";
}
function output(file, content) {
  const target = path.join(root, file);
  if (process.argv.includes("--check")) {
    if (!fs.existsSync(target) || fs.readFileSync(target, "utf8") !== content)
      throw new Error("Outdated generated documentation: " + file);
  } else fs.writeFileSync(target, content);
}
output("docs/classes.md", doc);
output("docs/class-diagrams.md", uml);
output("docs/source-inventory.json", JSON.stringify(types, null, 2) + "\n");
console.log(
  `${types.length} actual named Java types inventoried; documentation ${process.argv.includes("--check") ? "verified" : "generated"}.`,
);
