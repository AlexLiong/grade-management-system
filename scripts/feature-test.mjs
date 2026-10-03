#!/usr/bin/env node
/**
 * 组织管理与网上选课系统的端到端功能测试。
 *
 * 前置条件：四个 Java 服务与 chain-worker 已按 README 启动，网关监听 https://localhost:8443。
 * 用法：node scripts/feature-test.mjs
 *
 * 测试通过网关的 /api 统一入口发起请求（与浏览器完全同一条路径），覆盖：
 *  - 学院—专业—班级三级组织的增删改查与审计
 *  - 选课发布的发布、约束校验、学生选退课、批量选课、最低开课人数自动退回
 *  - 选课系统的安全性与鲁棒性规则、权限边界
 */
import fs from "node:fs";
import path from "node:path";
import https from "node:https";
import { fileURLToPath } from "node:url";
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const GATEWAY = process.env.GATEWAY_URL || "https://localhost:8443";

const agent = new https.Agent({ rejectUnauthorized: false, keepAlive: false });

/** 每个账号一份 Cookie（会话 + CSRF）。 */
const jars = new Map();

function jar(account) {
  if (!jars.has(account)) jars.set(account, new Map());
  return jars.get(account);
}

function cookieHeader(account) {
  const entries = [...jar(account).entries()];
  return entries.length ? entries.map(([k, v]) => `${k}=${v}`).join("; ") : "";
}

function absorbCookies(account, response) {
  // node:https 把 set-cookie 放在 headers 对象上（可能是数组），浏览器风格是 getSetCookie()。
  const raw =
    typeof response.headers.getSetCookie === "function"
      ? response.headers.getSetCookie()
      : [].concat(response.headers["set-cookie"] || []);
  const store = jar(account);
  for (const line of raw) {
    const [pair] = String(line).split(";");
    const index = pair.indexOf("=");
    if (index <= 0) continue;
    const key = pair.slice(0, index).trim();
    const value = pair.slice(index + 1).trim();
    if (value === "") store.delete(key);
    else store.set(key, value);
  }
}

/**
 * 调用统一入口。与浏览器一致：GET 把条件放在 query 串，POST 把 JSON 放在 body。
 * 网关会把它们重新打包成内部信封转发给业务服务。
 *
 * 这里直接用 node:https 而不是 fetch：本机是自签名证书，需要在 agent 上关闭校验
 * （等价于浏览器对本地测试站点确认例外），fetch 不接受该选项。
 */
function request(account, apiPath, { body, query } = {}) {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(query || {}))
    if (value !== undefined && value !== null && value !== "") search.append(key, value);
  const suffix = search.toString();
  const isWrite = body !== undefined;
  const payload = isWrite ? JSON.stringify(body) : "";
  const target = new URL(`/api${apiPath}${suffix ? "?" + suffix : ""}`, GATEWAY);
  const headers = {
    "Content-Type": "application/json",
    Cookie: cookieHeader(account),
    "X-CSRF-Token": jar(account).get("CAMPUS_CSRF") || "",
  };
  if (isWrite) headers["Content-Length"] = Buffer.byteLength(payload);
  return new Promise((resolve, reject) => {
    const req = https.request(
      {
        hostname: target.hostname,
        port: target.port || 443,
        path: target.pathname + target.search,
        method: isWrite ? "POST" : "GET",
        headers,
        agent,
      },
      (response) => {
        absorbCookies(account, response);
        let text = "";
        response.setEncoding("utf8");
        response.on("data", (chunk) => (text += chunk));
        response.on("end", () => {
          let data;
          try {
            data = text ? JSON.parse(text) : null;
          } catch {
            data = { raw: text.slice(0, 300) };
          }
          const status = response.statusCode || 0;
          if (status < 200 || status >= 300) {
            const error = new Error(
              (data && (data.message || data.error)) || `HTTP ${status}`,
            );
            error.status = status;
            error.data = data;
            reject(error);
          } else resolve(data);
        });
      },
    );
    req.on("error", (e) => reject(new Error(`网关不可达（${GATEWAY}）：${e.message}`)));
    if (isWrite) req.write(payload);
    req.end();
  });
}

const get = (account, apiPath, query) => request(account, apiPath, { query });
const post = (account, apiPath, body) => request(account, apiPath, { body });

// ------------------------------------------------------------------ 断言

const results = [];
let failures = 0;

function record(name, condition, detail) {
  const okValue = !!condition;
  results.push({ name, ok: okValue, detail: detail || "" });
  console.log(`  [${okValue ? "PASS" : "FAIL"}] ${name}${!okValue && detail ? " — " + detail : ""}`);
  if (!okValue) failures++;
}

/** 期望调用被拒绝，并匹配状态码与提示片段。 */
async function rejected(name, fn, status, fragment) {
  try {
    await fn();
    record(name, false, "请求意外成功，本应被拒绝");
  } catch (e) {
    const statusOk = status === undefined || e.status === status;
    const textOk = fragment === undefined || (e.message || "").includes(fragment);
    record(
      name,
      statusOk && textOk,
      `status=${e.status} message=${e.message}` +
        (statusOk ? "" : `（期望 ${status}）`) +
        (textOk ? "" : `（期望提示包含「${fragment}」）`),
    );
  }
}

async function accepted(name, fn) {
  try {
    const value = await fn();
    record(name, true);
    return value;
  } catch (e) {
    record(name, false, `status=${e.status} message=${e.message}`);
    return null;
  }
}

async function login(account, password = "passwd") {
  return (await post(account, "/login", { username: account, password })).user;
}

/** 不会与既有数据冲突的测试标识。 */
const stamp = Date.now().toString().slice(-6);
const label = (prefix) => `${prefix}${stamp}`;

// ------------------------------------------------------------------ 主体

console.log(`\n=== 组织管理与选课系统功能测试 @ ${GATEWAY} ===`);

console.log("\n[0] 服务可达性与登录");
// 账号名（Cookie 分桶键）与用户对象分开保存：请求只接受账号名字符串，
// 用户对象只用于读取角色与权限，避免把对象当成会话键导致 401。
const ACC = {
  admin: "admin",
  teacherInfo: "t1101",
  teacherEcon: "t2101",
  student: "20241530",
  studentEcon: "20241536",
  // 20231530 在 2023-1 挂科 CS102、2024-1（下一学年同一课程号）重修通过，
  // 用于「挂科可重修 / 已通过不得重选」。注意重修不体现在课程名上。
  retake: "20231530",
  // 正在重修的学生（2024-1 挂 CS102 → 2026-1 重修，成绩未提交）与重修班的授课教师，
  // 用于验证学生端的「重修」状态与教师端名单上的「重修」标记。
  retaking: "20241531",
  retakeTeacher: "t1102",
};
let admin, teacherInfo, teacherEcon, student, studentEcon, retake, retaking, retakeTeacher;
try {
  admin = await accepted("管理员 admin 登录", () => login(ACC.admin));
  teacherInfo = await accepted("教师 t1101 登录（信息工程学院）", () => login(ACC.teacherInfo));
  teacherEcon = await accepted("教师 t2101 登录（经济与管理学院）", () => login(ACC.teacherEcon));
  student = await accepted("学生 20241530 登录（2024级-软件工程-2401班）", () => login(ACC.student));
  studentEcon = await accepted("学生 20241536 登录（2024级-工商管理-2401班）", () => login(ACC.studentEcon));
  // 重修样本账号必须真的登录，否则后续 /transcript 与选课都会 401。
  retake = await accepted("学生 20231530 登录（2023级-软件工程-2301班，已重修通过样本）", () => login(ACC.retake));
  retaking = await accepted("学生 20241531 登录（正在重修 CS102 的样本）", () => login(ACC.retaking));
  retakeTeacher = await accepted("教师 t1102 登录（重修班授课教师）", () => login(ACC.retakeTeacher));
} catch (e) {
  console.error(`\n登录阶段异常：${e.message}`);
  console.error("请确认四个 Java 服务与 chain-worker 已启动。");
  process.exit(1);
}
if (!admin || !teacherInfo || !student) {
  console.error("\n关键账号登录失败，测试中止。请确认服务已启动并完成数据初始化。");
  process.exit(1);
}

// /login 返回 {user, csrf}；带组织名称的当前用户由 /me 返回，两者都要校验。
const me = await accepted("读取当前用户", () => get(ACC.student, "/me"));
record(
  "当前用户返回学院/专业/班级名称",
  me && me.collegeName && me.majorName && me.className,
  `college=${me?.collegeName} major=${me?.majorName} class=${me?.className}`,
);
record("学生归属到具体班级（编号）", !!me?.classId, `classId=${me?.classId}`);

// 管理员不应归属任何组织（需求：人员组织中管理员角色应不存在组织）。
const adminMe = await accepted("读取管理员当前用户", () => get(ACC.admin, "/me"));
record(
  "管理员没有学院/专业/班级归属",
  !adminMe?.collegeId && !adminMe?.majorId && !adminMe?.classId,
  `collegeId=${adminMe?.collegeId} majorId=${adminMe?.majorId} classId=${adminMe?.classId}`,
);

console.log("\n[1] 组织数据初始化（多学院、多专业、多班级）");
const options = await accepted("读取组织下拉选项", () => get(ACC.admin, "/organizations/options"));
record("学院数 >= 4（多学院）", (options?.colleges?.length || 0) >= 4, `colleges=${options?.colleges?.length}`);
record("专业数 >= 8", (options?.majors?.length || 0) >= 8, `majors=${options?.majors?.length}`);
record("班级数 >= 16", (options?.classes?.length || 0) >= 16, `classes=${options?.classes?.length}`);

const collegePage = await accepted("学院列表", () => get(ACC.admin, "/organizations", { level: "COLLEGE", size: 100 }));
const collegeItems = collegePage?.items || [];
record("学院使用独立编号作主键", collegeItems.length > 0 && collegeItems.every((c) => /^C\d{5}$/.test(c.id)), JSON.stringify(collegeItems.map((c) => c.id)));
record(
  "学院项附带专业/班级/学生计数",
  collegeItems.every((c) => c.majorCount !== undefined && c.classCount !== undefined && c.studentCount !== undefined),
);
const majorPage = await accepted("专业列表", () => get(ACC.admin, "/organizations", { level: "MAJOR", size: 100 }));
record("专业编号形如 M01001", (majorPage?.items || []).every((c) => /^M\d{5}$/.test(c.id)), JSON.stringify((majorPage?.items || []).map((c) => c.id)));
record("专业项带学院名称", (majorPage?.items || []).every((c) => !!c.collegeName));
const classPage = await accepted("班级列表", () => get(ACC.admin, "/organizations", { level: "CLASS", size: 100 }));
record("班级编号形如 B01001", (classPage?.items || []).every((c) => /^B\d{5}$/.test(c.id)), JSON.stringify((classPage?.items || []).map((c) => c.id)));
record("班级项带学院与专业名称", (classPage?.items || []).every((c) => c.collegeName && c.majorName));
record(
  "班级采用 XXXX级-XX专业-XX班 的形式",
  (classPage?.items || []).length > 0 &&
    (classPage?.items || []).every((c) => /^\d{4}级-.+-\d+班$/.test(c.name || "")),
  JSON.stringify((classPage?.items || []).map((c) => c.name)),
);
// 需求：班级不设辅导员。
record(
  "班级列表不含辅导员字段",
  (classPage?.items || []).every((c) => !("counselor" in c) && !("counselorName" in c)),
  JSON.stringify(Object.keys((classPage?.items || [])[0] || {})),
);

// 需求（第三轮调整）：组织管理的编号**就是数据库主键 `id`**，
// 不再有单独的「显示编号 code」；编号由后端自动生成，前端没有编号输入框。
const idsAreUnique = (items) => new Set(items.map((c) => c.id)).size === items.length;
record("学院列表不含 code 字段", (collegeItems || []).every((c) => !("code" in c)), JSON.stringify(Object.keys(collegeItems[0] || {})));
record(
  "专业/班级列表不含 code 字段",
  [...(majorPage?.items || []), ...(classPage?.items || [])].every((c) => !("code" in c)),
);
record(
  "下拉选项三级都不含 code 字段",
  ["colleges", "majors", "classes"].every((k) => (options?.[k] || []).every((c) => !("code" in c))),
  JSON.stringify(Object.keys(options?.colleges?.[0] || {})),
);
record("专业编号唯一", idsAreUnique(majorPage?.items || []));
record("班级编号唯一", idsAreUnique(classPage?.items || []));

console.log("\n[2] 组织管理：新建 → 层级校验 → 编号规则 → 删除保护");
const newCollege = await accepted("按名称新建学院", () =>
  post(ACC.admin, "/organizations/save", { level: "COLLEGE", name: label("测试学院"), shortName: "测试院", description: "端到端测试用临时学院", enabled: 1 }),
);
record("新建学院返回主键编号（C 开头 5 位）", /^C\d{5}$/.test(newCollege?.id || ""), JSON.stringify(newCollege));
record(
  "新建学院响应只含 ok 与 id（不再有 code/codeEditable）",
  newCollege && !("code" in newCollege) && !("codeEditable" in newCollege),
  JSON.stringify(Object.keys(newCollege || {})),
);
const newMajor = await accepted("按学院名称新建专业", () =>
  post(ACC.admin, "/organizations/save", { level: "MAJOR", name: label("测试专业"), degree: "工学", years: 4, college: label("测试学院"), enabled: 1 }),
);
record("新建专业返回主键编号（M 开头 5 位）", /^M\d{5}$/.test(newMajor?.id || ""), JSON.stringify(newMajor));
const newClass = await accepted("按专业名称新建班级", () =>
  post(ACC.admin, "/organizations/save", { level: "CLASS", name: `2026级-测试专业-${stamp}班`, gradeYear: "2026", college: label("测试学院"), major: label("测试专业"), enabled: 1 }),
);
record("新建班级返回主键编号（B 开头 5 位）", /^B\d{5}$/.test(newClass?.id || ""), JSON.stringify(newClass));

// 需求：编号自动生成、不允许重复。主键由 nextId 按「同层最大 + 1」生成。
const sameLevel = (await get(ACC.admin, "/organizations", { level: "CLASS", size: 300 })).items || [];
record("新建班级的编号不与任何已有班级重复", sameLevel.filter((c) => c.id === newClass.id).length === 1, `新=${newClass.id} 总数=${sameLevel.length}`);
record("班级编号唯一", idsAreUnique(sameLevel), JSON.stringify(sameLevel.map((c) => c.id)));
// 新建的学院应排在已有 4 所学院之后（C01001..C04001 → C05001）
const collegeIds = (await get(ACC.admin, "/organizations", { level: "COLLEGE", size: 100 })).items.map((c) => c.id);
record(
  "新建学院编号沿用「同层最大 + 1」的号段规则",
  collegeIds.includes("C05001"),
  JSON.stringify(collegeIds),
);

// 客户端传入的编号必须被忽略：新建时传 code 不会生效，编号一律由后端生成。
// （注意：传 id 的语义是「修改该条」，所以这里用另一个名称先建、再对比生成的编号。）
const forced = await accepted("新建专业时传入 code 应被忽略", () =>
  post(ACC.admin, "/organizations/save", { level: "MAJOR", name: label("编号测试专业"), degree: "工学", years: 4, college: label("测试学院"), code: "97", enabled: 1 }),
);
record(
  "新建时忽略客户端传入的编号，改用后端生成的主键",
  forced?.id !== "97" && /^M\d{5}$/.test(forced?.id || "") && forced.id !== newMajor.id,
  `id=${forced?.id}`,
);
record(
  "传 code 的新建响应同样只含 ok 与 id",
  forced && !("code" in forced) && !("codeEditable" in forced),
  JSON.stringify(Object.keys(forced || {})),
);
// 修改已存在的对象时，请求体里的 id 只作为「定位哪一条」，不能被用来改写主键
const rewrite = await accepted("修改专业时不改写主键", () =>
  post(ACC.admin, "/organizations/save", { id: newMajor.id, level: "MAJOR", name: label("测试专业"), degree: "工学", years: 4, college: label("测试学院"), code: "77", enabled: 1 }),
);
record("修改响应仍返回原主键", rewrite?.id === newMajor.id, JSON.stringify(rewrite));

await rejected("同级重名被拒绝", () => post(ACC.admin, "/organizations/save", { level: "COLLEGE", name: label("测试学院") }), 400, "已存在");
await rejected("缺少上级专业时创建班级被拒绝", () => post(ACC.admin, "/organizations/save", { level: "CLASS", name: `2026级-无上级-${stamp}班` }), 400, "必须指定");
await rejected(
  "班级与专业不匹配被拒绝",
  () =>
    post(ACC.admin, "/organizations/save", {
      level: "CLASS",
      name: `2026级-错配测试-${stamp}班`,
      gradeYear: "2026",
      college: "信息工程学院",
      major: label("测试专业"),
    }),
  400,
);

// 删除测试用专业（它不属于任何班级/账号），随后校验「编号不可修改」需要它还存在，故放在后面。
const impact = await accepted("删除前影响面统计", () => get(ACC.admin, "/organizations/impact", { level: "COLLEGE", id: newCollege.id }));
record("影响面包含下级数量", impact && impact.total !== undefined, JSON.stringify(impact));
await rejected("有账号的班级不允许删除", () => post(ACC.admin, "/organizations/delete", { level: "CLASS", id: "B01001" }), 409);
await rejected("有课程的学院不允许删除", () => post(ACC.admin, "/organizations/delete", { level: "COLLEGE", id: "C01001" }), 409);
await rejected(
  "管理员不能被分配到组织",
  () => post(ACC.admin, "/organizations/assign", { level: "CLASS", id: newClass.id, studentIds: ["admin"] }),
  400,
  "管理员",
);

console.log("\n[3] 编号即主键、确定后不可修改 + 组织敏感操作进入审计");
// 编号就是主键 id：修改时只能改名称等属性，请求体里塞一个别的 id 也不能改写主键。
await accepted("修改学院信息（请求体里带一个不同的 id 与 code）", () =>
  post(ACC.admin, "/organizations/save", { id: newCollege.id, level: "COLLEGE", name: label("测试学院"), code: "88", shortName: "测试院改", enabled: 1 }),
);
const afterEdit = (await get(ACC.admin, "/organizations", { level: "COLLEGE", size: 100 })).items.find((c) => c.id === newCollege.id);
record("修改后主键编号保持不变（仍能按原 id 找到）", !!afterEdit && afterEdit.id === newCollege.id, `原=${newCollege.id} 现=${afterEdit?.id}`);
record("修改后不会多出 code 字段", afterEdit && !("code" in afterEdit), JSON.stringify(Object.keys(afterEdit || {})));
record("修改后名称已生效", afterEdit?.shortName === "测试院改", JSON.stringify(afterEdit));
// 用一个不存在的编号去修改必须被拒绝，而不是悄悄新建或改到别的对象
await rejected(
  "用不存在的编号修改被拒绝",
  () => post(ACC.admin, "/organizations/save", { id: "C99999", level: "COLLEGE", name: label("不存在学院") }),
  400,
);

const ledgerOrg = await accepted("读取审计账本", () => get(ACC.admin, "/audit"));
const orgEvents = (ledgerOrg?.events || []).filter((e) => ["ORG_SAVE", "ORG_DELETE", "ORG_ASSIGN"].includes(e.action));
record("账本中存在组织管理审计事件", orgEvents.length > 0, `count=${orgEvents.length}`);
const orgChange = orgEvents.flatMap((e) => e.changes || []).find((c) => ["colleges", "majors", "classes"].includes(c.table));
record("组织审计事件带前后快照", !!orgChange && !!orgChange.after, JSON.stringify(orgChange || {}).slice(0, 200));

console.log("\n[4] 课程开设院系与学期唯一性");
const catalog = await accepted("课程目录", () => get(ACC.admin, "/courses/catalog", { size: 300 }));
const catalogItems = catalog?.items || [];
record("课程数量 >= 8", catalogItems.length >= 8, `count=${catalogItems.length}`);
record("课程带开设院系名称", catalogItems.every((c) => !!c.collegeName), JSON.stringify(catalogItems.filter((c) => !c.collegeName).map((c) => c.code)));
record("至少两个学院开设课程", new Set(catalogItems.map((c) => c.collegeName)).size >= 2, JSON.stringify([...new Set(catalogItems.map((c) => c.collegeName))]));
// 初始化数据中同一学生不得在两个学期修读相同课程代码——由 [4] 的学业记录断言校验。
// 同一学期允许多个教学班使用相同课程代码（不同教师分别开课），这是选课规则 10 的前提。

// 每个学生在同一课程代码上是否出现过两个学期都修读的情况
const studentTranscript = await accepted("读取学生学业记录", () => get(ACC.student, "/transcript"));
if (Array.isArray(studentTranscript)) {
  const seen = new Map();
  let repeat = null;
  for (const row of studentTranscript) {
    if (seen.has(row.code)) repeat = `${row.code}: ${seen.get(row.code)} / ${row.term}`;
    seen.set(row.code, row.term);
  }
  record("初始化数据中同一学生不会在两个学期修读相同课程代码", !repeat, repeat || "");
}

console.log("\n[5] 教务发布选课信息（使用独立测试学期，避免与演示批次互相占用课程）");
// 演示数据预置了一个 2026-1 的进行中批次；为了让断言可控，测试批次单独使用 2027-2，
// 并把新学期的测试课程按场景建好：
//   courseA    必修课（学生可正常选）
//   courseB    选修课（同一批次内可退）
//   courseB2   与 courseB 同课程代码、不同教师（用于规则 10）
//   courseC    CS102：20231530 在 2024-1 已通过，用于规则 11「已通过不得重选」
//   courseD    CS102 的重修班（allowRetake 开关的对照）
const term = "2027-2";
const createdCourses = await accepted("创建测试教学班", async () => {
  const specs = [
    { code: label("TA").slice(0, 8), name: label("测试必修"), teacherId: "t1101", credits: 2 },
    { code: label("TB").slice(0, 8), name: label("测试选修"), teacherId: "t1102", credits: 3 },
  ];
  for (const spec of specs)
    await post(ACC.admin, "/courses/save", {
      code: spec.code,
      name: spec.name,
      term,
      teacherId: spec.teacherId,
      credits: spec.credits,
      college: "信息工程学院",
    });
  const list = await get(ACC.admin, "/courses/catalog", { term, size: 300 });
  return list.items || [];
});
record("测试教学班创建成功", createdCourses.length >= 2, `count=${createdCourses.length}`);
const courseA = createdCourses.find((c) => c.name.startsWith("测试必修"));
const courseB = createdCourses.find((c) => c.name.startsWith("测试选修"));

// 同课程代码、不同教师的第二个教学班（规则 10 的前提）
const courseB2 = await accepted("创建同课程代码不同教师的教学班", async () => {
  await post(ACC.admin, "/courses/save", {
    code: courseB.code,
    name: courseB.name,
    term,
    teacherId: "t2101",
    credits: 3,
    college: "信息工程学院",
  });
  const list = await get(ACC.admin, "/courses/catalog", { term, size: 300 });
  return (list.items || []).find((c) => c.code === courseB.code && c.id !== courseB.id);
});
record("同代码不同教师教学班已创建", !!courseB2?.id, JSON.stringify(courseB2 || {}));

// 已通过课程 CS102 的新教学班（规则 11）
const courseC = await accepted("创建已通过课程代码的新教学班（CS102）", async () => {
  await post(ACC.admin, "/courses/save", {
    code: "CS102",
    name: label("测试已通过课程"),
    term,
    teacherId: "t1102",
    credits: 4,
    college: "信息工程学院",
  });
  const list = await get(ACC.admin, "/courses/catalog", { term, size: 300 });
  return (list.items || []).find((c) => c.code === "CS102");
});
record("已通过课程代码的新教学班已创建", !!courseC?.id, JSON.stringify(courseC || {}));

const publish = await accepted("发布选课信息", () =>
  post(ACC.admin, "/selections/save", {
    name: label("测试选课批次"),
    term,
    courseIds: [courseA.id, courseB.id, courseB2.id, courseC.id],
    scopeCollegeIds: ["信息工程学院"],
    startTime: "2027-02-01T08:00",
    endTime: "2027-02-28T23:59",
    minEnroll: 2,
    maxCredits: 20,
    allowAdd: true,
    allowDrop: true,
    allowRetake: false,
    note: "端到端测试批次",
  }),
);
record("发布返回批次编号", !!publish?.id, JSON.stringify(publish));
const selectionPage = await accepted("教务查看选课列表", () => get(ACC.admin, "/selections", { size: 100 }));
const publishRow = (selectionPage?.items || []).find((s) => s.id === publish?.id);
record("选课列表含课程数与已选人数", publishRow && publishRow.courseCount !== undefined && publishRow.selectedCount !== undefined, JSON.stringify(publishRow || {}));
record("选课列表状态为 OPEN", publishRow?.status === "OPEN", publishRow?.status);
record("选课列表带状态中文名", !!publishRow?.statusName, publishRow?.statusName);

console.log("\n[6] 选课鲁棒性校验（发布阶段）");
// 需要一门「已有教师录入成绩」的课程：初始化数据只给已结束的历史学期写成绩，
// 当前学期（2026-1）故意留空供教师现场录入，因此这里必须选历史学期的课程。
const gradedCourse =
  catalogItems.find((c) => c.term === "2025-2") ||
  catalogItems.find((c) => /^202[3-5]-/.test(c.term));
record("找到已录入成绩的历史课程用于校验", !!gradedCourse, JSON.stringify(gradedCourse || {}));
await rejected(
  "已有教师录入成绩的课程不允许发布选课",
  () =>
    post(ACC.admin, "/selections/save", {
      name: label("非法批次"),
      term: gradedCourse.term,
      courseIds: [gradedCourse.id],
      startTime: "2027-02-01T08:00",
      endTime: "2027-02-28T23:59",
      minEnroll: 1,
    }),
  409,
  "成绩",
);
await rejected(
  "同一课程不能出现在两个进行中的发布",
  () => post(ACC.admin, "/selections/save", { name: label("重复批次"), term, courseIds: [courseA.id], startTime: "2027-02-01T08:00", endTime: "2027-02-28T23:59", minEnroll: 1 }),
  409,
);
await rejected(
  "结束时间早于开始时间被拒绝",
  () => post(ACC.admin, "/selections/save", { name: label("时间错误"), term, courseIds: [courseB.id], startTime: "2027-03-01T08:00", endTime: "2027-02-01T08:00", minEnroll: 1 }),
  400,
);
await rejected(
  "最低开课人数超出范围被拒绝",
  () => post(ACC.admin, "/selections/save", { name: label("人数错误"), term, courseIds: [courseB.id], startTime: "2027-02-01T08:00", endTime: "2027-02-28T23:59", minEnroll: 0 }),
  400,
);
await rejected(
  "非教务角色不能发布选课",
  () => post(ACC.student, "/selections/save", { name: label("越权批次"), term, courseIds: [courseB.id], startTime: "2027-02-01T08:00", endTime: "2027-02-28T23:59", minEnroll: 1 }),
  403,
);

console.log("\n[7] 学生选课：窗口与范围校验");
await rejected("窗口未开始时不能选课", () => post(ACC.student, "/selections/select", { publishId: publish.id, courseId: courseA.id }), 400, "尚未开始");

const now = Date.now();
const windowStart = new Date(now - 3600_000).toISOString().slice(0, 16);
const windowEnd = new Date(now + 86400_000).toISOString().slice(0, 16);
await accepted("教务把选课窗口调整为进行中", () =>
  post(ACC.admin, "/selections/save", {
    id: publish.id,
    name: label("测试选课批次"),
    term,
    courseIds: [courseA.id, courseB.id, courseB2.id, courseC.id],
    scopeCollegeIds: ["信息工程学院"],
    startTime: windowStart,
    endTime: windowEnd,
    minEnroll: 2,
    maxCredits: 20,
    allowAdd: true,
    allowDrop: true,
    allowRetake: false,
    note: "端到端测试批次",
  }),
);

const available = await accepted("学生查看可选课程", () => get(ACC.student, "/selections/available"));
const availableBatch = (available || []).find((p) => p.id === publish.id);
record("可选批次包含课程明细", (availableBatch?.courses?.length || 0) >= 2, JSON.stringify((availableBatch?.courses || []).map((c) => c.code)));
record("课程明细带教师与学院名称", (availableBatch?.courses || []).every((c) => c.teacherName && c.collegeName), JSON.stringify(availableBatch?.courses?.[0] || {}));
record("课程明细带可选状态与原因", (availableBatch?.courses || []).every((c) => c.eligible !== undefined && "reason" in c), JSON.stringify(availableBatch?.courses?.[0] || {}));

// 范围校验必须在窗口内进行，否则先被“尚未开始”拦下，测不到范围规则。
// 测试批次把范围限定为信息工程学院，因此经济与管理学院的学生必须被拒绝。
if (studentEcon) {
  await rejected(
    "不在选课范围内的学生被拒绝",
    () => post(ACC.studentEcon, "/selections/select", { publishId: publish.id, courseId: courseA.id }),
    403,
    "范围",
  );
} else {
  record("不在选课范围内的学生被拒绝", false, "经济与管理学院学生账号未登录成功");
}

await accepted("学生选择必修课", () => post(ACC.student, "/selections/select", { publishId: publish.id, courseId: courseA.id }));
const mySelections = await accepted("学生查看本人选课", () => get(ACC.student, "/selections/my"));
record("本人选课包含刚选的课程", (mySelections || []).some((r) => r.courseId === courseA.id), JSON.stringify((mySelections || []).map((r) => r.code)));
record("本人选课带课程名称与来源", (mySelections || []).every((r) => r.name && r.source), JSON.stringify((mySelections || [])[0] || {}));
await rejected("重复选择同一课程被拒绝", () => post(ACC.student, "/selections/select", { publishId: publish.id, courseId: courseA.id }), 409, "已选");

console.log("\n[8] 鲁棒性：不同教师的同一门课 / 已通过课程不得重选");
// 规则 10 按「同学期 + 同课程代码 + 不同教学班」判定：同一批次里放了 courseB 与 courseB2，
// 两者课程代码相同、教师不同。学生选了 courseB 之后再选 courseB2 必须被拒绝。
await accepted("学生选择选修课", () => post(ACC.student, "/selections/select", { publishId: publish.id, courseId: courseB.id }));
// 先确认选修课确实选上了，后面的退课用例才有明确的前置条件，失败时也能定位到具体步骤。
const afterPickB = await accepted("确认选修课已选上", () => get(ACC.student, "/selections/my"));
record(
  "选修课已进入本人选课（退课用例的前置条件）",
  (afterPickB || []).some((r) => r.courseId === courseB.id),
  JSON.stringify((afterPickB || []).map((r) => r.code)),
);
await rejected(
  "不允许选择不同教师开设的同一门课（按课程代码判定）",
  () => post(ACC.student, "/selections/select", { publishId: publish.id, courseId: courseB2.id }),
  409,
);

// 规则 11：20231530 在 2024-1 已通过 CS102（初始化数据里是「2023-1 挂科 → 2024-1 重修通过」），
// 因此同一课程号的课在后续学期不能再选。
const retakeTranscript = await accepted("读取重修学生的学业记录", () => get(ACC.retake, "/transcript"));
const passedCs102 = (retakeTranscript || []).find((r) => r.code === "CS102" && r.failed === false);
record(
  "初始化数据含 20231530 已通过的 CS102",
  !!passedCs102,
  JSON.stringify((retakeTranscript || []).filter((r) => r.code === "CS102").map((r) => `${r.term}:${r.effective}`)),
);
const failedCs102 = (retakeTranscript || []).find((r) => r.code === "CS102" && r.failed === true);
record("初始化数据含 20231530 挂科的 CS102（重修样本）", !!failedCs102, JSON.stringify((retakeTranscript || []).filter((r) => r.code === "CS102").map((r) => `${r.term}:${r.effective}`)));
await rejected(
  "不允许重选此前学期已通过的同一门课",
  () => post(ACC.retake, "/selections/select", { publishId: publish.id, courseId: courseC.id }),
  409,
  "已通过",
);

console.log("\n[8b] 重修语义：同名同课程号、不同学年；重修只是一次修读状态");
// 需求：重修不体现在课程名里。同一课程代码在「挂科学期」与「重修学期」是同一门课，
// 课程名必须完全一致，且都不能带「重修」字样。
// 注意排除本脚本自己新建的 CS102 教学班（名字带时间戳），只看初始化数据里的教学班。
const seededTerms = new Set(["2023-1", "2024-1", "2025-1", "2025-2", "2026-1"]);
const retakeCourses = catalogItems.filter((c) => c.code === "CS102" && seededTerms.has(c.term));
record(
  "初始化数据里 CS102 有多学期的教学班（同一门课多次开课）",
  retakeCourses.length >= 2,
  JSON.stringify(retakeCourses.map((c) => `${c.term}/${c.id}/${c.name}`)),
);
record(
  "同一课程代码在不同学年使用完全相同的课程名",
  retakeCourses.length >= 2 && new Set(retakeCourses.map((c) => c.name)).size === 1,
  JSON.stringify([...new Set(retakeCourses.map((c) => c.name))]),
);
record(
  "初始化数据的课程名不含「重修」字样",
  catalogItems.filter((c) => seededTerms.has(c.term)).every((c) => !/重修|补考|清考/.test(c.name || "")),
  JSON.stringify(catalogItems.filter((c) => seededTerms.has(c.term) && /重修|补考|清考/.test(c.name || "")).map((c) => c.name)),
);

// 学生端：当前学期正在重修的学生，其选课记录必须带 retake 状态。
const retakingStudentSelections = await accepted("读取正在重修学生的选课记录", () => get(ACC.retaking, "/selections/my"));
const retakeRows = (retakingStudentSelections || []).filter((r) => r.retake === true);
record(
  "学生端把重修的课标记为重修状态",
  retakeRows.length >= 1,
  JSON.stringify((retakingStudentSelections || []).map((r) => `${r.code}@${r.term}:retake=${r.retake}`)),
);
record(
  "重修记录带 retakeLabel",
  retakeRows.every((r) => r.retakeLabel === "重修"),
  JSON.stringify(retakeRows.map((r) => r.retakeLabel)),
);
const nonRetakeRows = (retakingStudentSelections || []).filter((r) => r.code !== "CS102");
record(
  "非重修课程不会被误标为重修",
  nonRetakeRows.every((r) => !r.retake),
  JSON.stringify(nonRetakeRows.map((r) => `${r.code}:retake=${r.retake}`)),
);

// 教师端：带该重修班的教师能在名单里看到谁在重修。
const currentTermRetakeCourse = retakeCourses.find((c) => c.term === "2026-1");
record("当前学期存在重修教学班", !!currentTermRetakeCourse, JSON.stringify(currentTermRetakeCourse || {}));
if (currentTermRetakeCourse) {
  const retakeRoster = await accepted("教师读取重修教学班名单", () =>
    get(ACC.retakeTeacher, "/roster", { courseId: currentTermRetakeCourse.id }),
  );
  const marked = (retakeRoster || []).filter((r) => r.retake === true);
  record(
    "教师端名单标出重修学生",
    marked.length >= 1 && marked.every((r) => r.retakeLabel === "重修"),
    JSON.stringify((retakeRoster || []).map((r) => `${r.username}:retake=${r.retake}`)),
  );
  const distinctStudents = new Set((retakeRoster || []).map((r) => r.id));
  record(
    "重修教学班名单里的学生都是重修生",
    (retakeRoster || []).length > 0 && marked.length === distinctStudents.size,
    `roster=${(retakeRoster || []).length} marked=${marked.length}`,
  );
}

// 重修判定只认「成绩已提交且不及格」：正在修读（成绩未提交）不算，已通过也不算。
// 注意 /transcript 只列**已提交**的成绩，因此当前学期的重修（未提交）不会出现在这里；
// 它只用来确认「更早学期那条确实是挂科」。
const retakingTranscript = await accepted("读取正在重修学生的成绩单", () => get(ACC.retaking, "/transcript"));

console.log("\n[8c] 我的成绩：学业记录标注重修状态");
// 20231530 是「2023-1 挂 CS102 → 2024-1 重修通过」的样本，学业记录里应能看到重修标记。
const retakeHistory = await accepted("读取已重修通过学生的学业记录", () => get(ACC.retake, "/transcript"));
const cs102History = (retakeHistory || []).filter((r) => r.code === "CS102");
record(
  "学业记录含同一课程代码的两个学期（挂科 + 重修）",
  cs102History.length === 2,
  JSON.stringify(cs102History.map((r) => `${r.term}:${r.effective}:failed=${r.failed}:retake=${r.retake}`)),
);
record(
  "学业记录里重修那一条带 retake 与 retakeLabel",
  cs102History.some((r) => r.retake === true && r.retakeLabel === "重修"),
  JSON.stringify(cs102History.map((r) => `${r.term}:retake=${r.retake}/${r.retakeLabel}`)),
);
record(
  "学业记录里第一次修读（挂科那年）不标重修",
  cs102History.filter((r) => r.retake === true).length === 1,
  JSON.stringify(cs102History.map((r) => `${r.term}:retake=${r.retake}`)),
);
record(
  "学业记录里同一课程代码的课程名完全一致且不含「重修」",
  new Set(cs102History.map((r) => r.name)).size === 1 && !/重修/.test(cs102History[0]?.name || ""),
  JSON.stringify([...new Set(cs102History.map((r) => r.name))]),
);
record(
  "学业记录按学期倒序（最近的修读在前）",
  (retakeHistory || []).length > 1 &&
    (retakeHistory || []).every((r, i, arr) => i === 0 || arr[i - 1].term >= r.term),
  JSON.stringify((retakeHistory || []).map((r) => r.term)),
);
record(
  "只修读一次的课程不会被标成重修",
  (retakeHistory || []).filter((r) => r.code !== "CS102").every((r) => r.retake === false),
  JSON.stringify((retakeHistory || []).filter((r) => r.code !== "CS102").map((r) => `${r.code}:${r.retake}`)),
);

const cs102Attempts = (retakingTranscript || []).filter((r) => r.code === "CS102");
record(
  "正在重修的学生在成绩单里有一条已提交的 CS102 挂科记录",
  cs102Attempts.length === 1 && cs102Attempts[0].failed === true,
  JSON.stringify(cs102Attempts.map((r) => `${r.term}:${r.effective}:failed=${r.failed}`)),
);
record(
  "重修的判定依据是「更早学期已提交且不及格」的成绩",
  cs102Attempts.some((r) => Number(r.effective) < 60),
  JSON.stringify(cs102Attempts.map((r) => `${r.term}:${r.effective}`)),
);
// 而「本学期正在重修」这件事由选课记录体现（上一段的 retake=true），两者合起来才是完整证据：
// 更早学期挂科 + 本学期重修同一课程号且成绩未提交。
record(
  "重修记录指向当前学期且成绩尚未提交",
  retakeRows.length >= 1 && retakeRows.every((r) => r.status === "ACTIVE"),
  JSON.stringify(retakeRows.map((r) => `${r.code}@${r.term}:${r.status}`)),
);
const retakingGradeForRetakeCourse = await accepted("读取当前重修课程的成绩状态", () =>
  get(ACC.retaking, "/grades", { courseId: currentTermRetakeCourse ? currentTermRetakeCourse.id : "" }),
);
record(
  "当前重修教学班的成绩尚未提交（供教师现场录入）",
  !retakingGradeForRetakeCourse ||
    (retakingGradeForRetakeCourse.items || []).every((g) => g.state !== "SUBMITTED"),
  JSON.stringify((retakingGradeForRetakeCourse || {}).items || retakingGradeForRetakeCourse || {}),
);

console.log("\n[8d] 学业预警：学生与教师的课程都能预测");
// 需求：老师与学生都能正常使用学业预警（此前提示「至少需要 3 年、24 条完整历史成绩」）。
// 逐门课调用 /predict，要求**每一门学生已选课程**都返回 200。
const studentCourses = await accepted("读取学生课程列表", () => get(ACC.retake, "/courses", { size: 300 }));
const studentCourseItems = (studentCourses?.items || studentCourses || []).filter(
  (c) => c.term !== "2027-2",
);
record("学生有可预测的课程", studentCourseItems.length > 0, `count=${studentCourseItems.length}`);
const predictFailures = [];
for (const c of studentCourseItems) {
  try {
    await post(ACC.retake, "/predict", { courseId: c.id });
  } catch (e) {
    predictFailures.push(`${c.code}@${c.term}: ${e.status || ""} ${e.message}`);
  }
}
record(
  "学生逐门课都能预测（不再提示数据不足）",
  predictFailures.length === 0,
  JSON.stringify(predictFailures),
);
// 当前学期的课程必须真的返回预测行（历史课程已出分，没有待预测对象属正常）。
// 用 2024 级学生（ACC.student）举例，他 2026-1 有两门在读课程。
const currentCourses = await accepted("读取学生在读课程", () =>
  get(ACC.student, "/courses", { size: 300 }),
);
const currentCourse = (currentCourses?.items || currentCourses || []).find((c) => c.term === "2026-1");
record(
  "学生有当前学期的课程",
  !!currentCourse,
  JSON.stringify((currentCourses?.items || currentCourses || []).map((c) => `${c.code}@${c.term}`)),
);
if (currentCourse) {
  const pred = await accepted("学生对当前学期课程生成预测", () =>
    post(ACC.student, "/predict", { courseId: currentCourse.id }),
  );
  record(
    "预测返回训练年份与样本数",
    (pred?.trainingYears?.length || 0) >= 3 && (pred?.samples || 0) >= 24,
    `years=${JSON.stringify(pred?.trainingYears)} samples=${pred?.samples}`,
  );
  record(
    "预测给出学生本人的预测行",
    (pred?.results || []).length >= 1,
    JSON.stringify(pred?.results || []),
  );
  const row = (pred?.results || [])[0] || {};
  record(
    "预测行字段完整（线性/决策树/区间/预警）",
    row.predictedTotal !== undefined &&
      row.treeTotal !== undefined &&
      row.low !== undefined &&
      row.high !== undefined &&
      "warning" in row &&
      "risk" in row,
    JSON.stringify(row),
  );
}
// 教师视角：带课教师对自己每门课都能预测
const teacherCourses = await accepted("读取教师课程列表", () => get(ACC.retakeTeacher, "/courses", { size: 300 }));
const teacherItems = (teacherCourses?.items || teacherCourses || []).filter((c) => c.term !== "2027-2");
const teacherFailures = [];
for (const c of teacherItems) {
  try {
    await post(ACC.retakeTeacher, "/predict", { courseId: c.id });
  } catch (e) {
    teacherFailures.push(`${c.code}@${c.term}: ${e.status || ""} ${e.message}`);
  }
}
record(
  "教师逐门课都能预测",
  teacherItems.length > 0 && teacherFailures.length === 0,
  `courses=${teacherItems.length} failures=${JSON.stringify(teacherFailures)}`,
);

console.log("\n[9] 教务按课程批量选课（1 人 / 多人 / 整班）与退课保护");
// 需求：批量选课并入每门课程的选课界面，可选一个、多个学生或某个班级的全部学生，
// 并写入选课记录。
const single = await accepted("按单个学生批量选课", () =>
  post(ACC.admin, "/enrollments/batch", { courseId: courseA.id, studentIds: ["20231531"], classStudents: 0 }),
);
record("单学生选课 added=1", single?.added === 1, JSON.stringify(single));

const rosterBefore = await accepted("批量选课前的教学班名单", () => get(ACC.admin, "/roster", { courseId: courseA.id }));
const wholeClass = await accepted("按班级整班选课", () =>
  post(ACC.admin, "/enrollments/batch", { courseId: courseA.id, className: "2024级-软件工程-2401班" }),
);
record("整班选课 added>=1", (wholeClass?.added || 0) >= 1, JSON.stringify(wholeClass));
const rosterAfter = await accepted("批量选课后的教学班名单", () => get(ACC.admin, "/roster", { courseId: courseA.id }));
record("批量选课后名单人数增加", (rosterAfter || []).length > (rosterBefore || []).length, `before=${(rosterBefore || []).length} after=${(rosterAfter || []).length}`);
record("名单带学生班级名称", (rosterAfter || []).every((r) => "className" in r), JSON.stringify((rosterAfter || [])[0] || {}));

const repeat = await accepted("重复批量选课进入 skipped", () =>
  post(ACC.admin, "/enrollments/batch", { courseId: courseA.id, className: "2024级-软件工程-2401班" }),
);
record("已选学生计入 skipped", (repeat?.skipped || 0) >= 1 && (repeat?.added || 0) === 0, JSON.stringify(repeat));

await rejected(
  "既不给学生也不给班级被拒绝",
  () => post(ACC.admin, "/enrollments/batch", { courseId: courseA.id }),
  400,
);
await rejected(
  "学生不能调用批量选课",
  () => post(ACC.student, "/enrollments/batch", { courseId: courseA.id, studentIds: ["20231531"] }),
  403,
);

// 退课保护：已有成绩的学生不能退课。必须挑**真正选了并且有成绩**的课程与学生，
// 否则批量退课只会把「没选过的人」计入 skipped 而成功返回，测不到保护规则。
const gradedRoster = await accepted("读取已录成绩课程的名单", () => get(ACC.admin, "/roster", { courseId: gradedCourse.id }));
const gradedStudent = (gradedRoster || [])[0];
record("已录成绩课程存在选课学生", !!gradedStudent, JSON.stringify((gradedRoster || []).slice(0, 3)));
if (gradedStudent) {
  const blocked = await accepted("已有成绩的学生批量退课（应进入 failed）", () =>
    post(ACC.admin, "/enrollments/batch", {
      courseId: gradedCourse.id,
      studentIds: [gradedStudent.id],
      remove: true,
    }),
  );
  record(
    "已有成绩的学生被拒绝退课并进入 failed",
    (blocked?.failed || []).some((f) => f.studentId === gradedStudent.id) && (blocked?.removed || 0) === 0,
    JSON.stringify(blocked),
  );
  // 单独退课同样必须被拒绝
  await rejected(
    "教务代退已有成绩的学生被拒绝",
    () =>
      post(ACC.admin, "/selections/drop", {
        publishId: publish.id,
        courseId: gradedCourse.id,
        studentId: gradedStudent.id,
      }),
    undefined,
    "成绩",
  );
}

await accepted("学生退课", () => post(ACC.student, "/selections/drop", { publishId: publish.id, courseId: courseB.id }));
const afterDrop = await accepted("退课后本人选课列表", () => get(ACC.student, "/selections/my"));
record("退课后课程不再出现在本人选课", !(afterDrop || []).some((r) => r.courseId === courseB.id));

console.log("\n[10] 最低开课人数不满足时自动退回");
// 已进行中的批次会独占课程，因此为低人数场景单独建一门课，避免“课程已存在于其他进行中的发布”。
const tinyCourse = await accepted("为低人数场景创建独立课程", async () => {
  await post(ACC.admin, "/courses/save", {
    code: label("TL").slice(0, 8),
    name: label("低人数课程"),
    term,
    teacherId: "t1101",
    credits: 2,
    college: "信息工程学院",
  });
  const list = await get(ACC.admin, "/courses/catalog", { term, size: 300 });
  return (list.items || []).find((c) => c.name.startsWith("低人数课程"));
});
record("低人数场景课程已创建", !!tinyCourse?.id, JSON.stringify(tinyCourse || {}));
const tinyPublish = await accepted("发布低人数批次", () =>
  post(ACC.admin, "/selections/save", {
    name: label("低人数批次"),
    term,
    courseIds: [tinyCourse.id],
    scopeCollegeIds: [],
    startTime: windowStart,
    endTime: windowEnd,
    minEnroll: 50,
    maxCredits: 0,
    allowAdd: true,
    allowDrop: true,
    allowRetake: true,
  }),
);
record("低人数批次发布成功", !!tinyPublish?.id, JSON.stringify(tinyPublish));
await accepted("学生选入低人数批次课程", () => post(ACC.student, "/selections/select", { publishId: tinyPublish.id, courseId: tinyCourse.id }));
const settle = await accepted("教务结算最低开课人数", () => post(ACC.admin, "/selections/settle", { id: tinyPublish.id }));
record("结算返回被取消课程列表", Array.isArray(settle?.cancelled), JSON.stringify(settle));
record("不足人数课程被判定取消", (settle?.cancelled || []).some((c) => c.courseId === tinyCourse.id), JSON.stringify(settle?.cancelled));
record("结算报告自动退回人数", (settle?.refunded || 0) >= 1, JSON.stringify(settle));
const afterRefund = await accepted("自动退回后本人选课列表", () => get(ACC.student, "/selections/my"));
record("自动退回后课程已从本人选课移除", !(afterRefund || []).some((r) => r.courseId === tinyCourse.id), JSON.stringify((afterRefund || []).map((r) => r.code)));

const records = await accepted("选课操作记录", () => get(ACC.admin, "/selections/records", { size: 200 }));
const actions = new Set((records?.items || []).map((r) => r.action));
record("选课记录含 SELECT 与 DROP", actions.has("SELECT") && actions.has("DROP"), JSON.stringify([...actions]));
record("选课记录含 AUTO_REFUND（自动退回）", actions.has("AUTO_REFUND"), JSON.stringify([...actions]));
record("选课记录带学生姓名与课程名称", (records?.items || []).every((r) => r.studentName !== undefined && r.courseName !== undefined), JSON.stringify((records?.items || [])[0] || {}));

const ledgerSelection = await accepted("再次读取审计账本", () => get(ACC.admin, "/audit"));
const selectionActions = new Set((ledgerSelection?.events || []).map((e) => e.action));
record(
  "选课发布/选课/退课进入审计账本",
  ["SELECTION_PUBLISH", "SELECTION_SELECT", "SELECTION_DROP"].every((a) => selectionActions.has(a)),
  JSON.stringify([...selectionActions].filter((a) => a.startsWith("SELECTION"))),
);
record(
  "自动退回进入审计账本",
  selectionActions.has("SELECTION_SETTLE"),
  JSON.stringify([...selectionActions].filter((a) => a.startsWith("SELECTION"))),
);

console.log("\n[11] 权限边界与跨域保护");
await rejected("学生不能查看教务选课列表", () => get(ACC.student, "/selections", { size: 10 }), 403);
await rejected("学生不能批量选课", () => post(ACC.student, "/enrollments/batch", { courseId: courseA.id, studentIds: ["20231531"] }), 403);
await rejected("教师不能读组织管理列表", () => get(ACC.teacherInfo, "/organizations", { level: "COLLEGE" }), 403);
await rejected("学生不能新建学院", () => post(ACC.student, "/organizations/save", { level: "COLLEGE", name: label("越权学院") }), 403);
await rejected("未登录访问被拒绝", () => get("anon", "/organizations", { level: "COLLEGE" }), 401);
/** 跨站来源必须被网关拒绝（Origin 白名单）。 */
const badOrigin = await new Promise((resolve) => {
  const target = new URL("/api/organizations?level=COLLEGE", GATEWAY);
  const req = https.request(
    {
      hostname: target.hostname,
      port: target.port || 443,
      path: target.pathname + target.search,
      method: "GET",
      headers: {
        Origin: "https://evil.example.com",
        Cookie: cookieHeader(ACC.admin),
        "X-CSRF-Token": jar(ACC.admin).get("CAMPUS_CSRF") || "",
      },
      agent,
    },
    (response) => {
      response.resume();
      response.on("end", () => resolve({ status: response.statusCode }));
    },
  );
  req.on("error", () => resolve({ status: 0 }));
  req.end();
});
record("跨站来源被网关拒绝", badOrigin.status === 403, `status=${badOrigin.status}`);

console.log("\n[12] 清理测试数据");
await accepted("关闭测试批次", () => post(ACC.admin, "/selections/close", { id: publish.id }));
await accepted("取消测试批次", () => post(ACC.admin, "/selections/cancel", { id: publish.id, reason: "端到端测试清理" }));
// 低人数批次已由 settle 关闭；这里只关闭主批次，验证关闭与取消路径。

// 测试期间新建的组织对象要删掉，否则演示数据里会残留「测试学院/测试专业/测试班」，
// 也会让下一次运行时 [1] 的班级命名格式断言看到非规范名称。
// 删除顺序必须是班级 → 专业 → 学院：后端会拒绝删除仍有下级或课程的对象。
// 这里额外用「名称含测试/编号」做一次兜底扫描：上一轮测试若在清理前被中断（Ctrl+C），
// 只按变量名删除会漏掉残留对象，兜底扫描能保证下一次运行的断言基线是干净的。
const leftovers = { CLASS: [], MAJOR: [], COLLEGE: [] };
for (const level of ["CLASS", "MAJOR", "COLLEGE"]) {
  const page = await accepted(`扫描残留的测试${level}`, () =>
    get(ACC.admin, "/organizations", { level, size: 300 }),
  );
  leftovers[level] = (page?.items || []).filter((c) => /测试|编号/.test(c.name || ""));
}
for (const level of ["CLASS", "MAJOR", "COLLEGE"]) {
  const ids = new Set([
    ...leftovers[level].map((c) => c.id),
    ...(level === "CLASS" ? [newClass?.id] : level === "MAJOR" ? [newMajor?.id] : [newCollege?.id]).filter(Boolean),
  ]);
  for (const id of ids)
    await accepted(`清理测试${level} ${id}`, () =>
      post(ACC.admin, "/organizations/delete", { level, id, force: true }),
    );
}

// 测试期间在 2027-2 新建的教学班与产生的选课，必须在下一次运行前清干净：
// 每轮测试都会新建一批课程（课程代码带时间戳），如果不把学生退出去，下一次运行
// 「已通过不得重选」等规则的判定基线就会被污染。这里按课程批量退课，
// 并顺带清空该学期的选课流水对应的选课记录。
// 课程本身保留（系统没有删除课程的接口，教务用取消教学班来下架），但已无学生、无成绩。
for (const course of [tinyCourse, courseC, courseB2, courseB, courseA]) {
  if (!course?.id) continue;
  const roster = await accepted(`清理测试课程名单 ${course.code}`, () => get(ACC.admin, "/roster", { courseId: course.id }));
  if (Array.isArray(roster) && roster.length) {
    const removed = await accepted(`按课程批量退课 ${course.code}`, () =>
      post(ACC.admin, "/enrollments/batch", {
        courseId: course.id,
        studentIds: roster.map((r) => r.id),
        remove: true,
      }),
    );
    record(`清理 ${course.code} 的学生`, (removed?.removed || 0) >= 1 || (removed?.failed || []).length > 0, JSON.stringify(removed || {}));
  }
}

// ------------------------------------------------------------------ 汇总

const passed = results.filter((r) => r.ok).length;
console.log(`\n=== 结果：${passed}/${results.length} 通过，${failures} 失败 ===`);
const evidenceDir = path.join(root, ".runtime", "logs");
fs.mkdirSync(evidenceDir, { recursive: true });
fs.writeFileSync(
  path.join(evidenceDir, "feature-test.json"),
  JSON.stringify({ generatedAt: new Date().toISOString(), gateway: GATEWAY, total: results.length, passed, failed: failures, results }, null, 2) + "\n",
);
console.log("证据已写入 .runtime/logs/feature-test.json");
process.exit(failures === 0 ? 0 : 1);
