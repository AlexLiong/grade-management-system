#!/usr/bin/env node
/**
 * 前端页面的浏览器验证（Playwright + 本机 Edge，无头模式）。
 *
 * 前置：Vite 开发服务器已在 https://127.0.0.1:5173 运行，四个 Java 服务已启动。
 * 用法：node scripts/browser-check.mjs
 *
 * 覆盖前端需求（R2 四条 + R3 两处调整）：
 *   1. 管理员导航「安全审计」排在最后
 *   2. 左下角身份显示「角色 · 学院 · 专业 · 班级」；导航栏宽度改为**鼠标拖动**调节（无折叠按钮）
 *   3. 顶栏/页脚/登录页不再出现硬编码学院名
 *   4. 管理员「网上选课」与「课程与选课」合并为「选课管理」，每门课程有「选课」按钮
 *   5. 组织管理的编号直接用数据库主键 id，表单不再有编号输入框
 * 另验证组织管理页（无辅导员）与学生选课台。
 *
 * 为什么不用 frontend/tests/browser.spec.js：它依赖不存在的 .runtime/config.json，
 * 且按重构前的演示数据（net-2025 / 2026-1）编写，与本项目的初始化数据不一致。
 */
import fs from "node:fs";
import path from "node:path";
import https from "node:https";
import { fileURLToPath, pathToFileURL } from "node:url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
// playwright 装在 frontend/node_modules 下，这里按相对路径加载，避免在仓库根再做一次安装。
const playwrightEntry = path.join(root, "frontend", "node_modules", "playwright", "index.mjs");
if (!fs.existsSync(playwrightEntry)) {
  console.error(`找不到 playwright：${playwrightEntry}（可先运行 npm --prefix frontend install）`);
  process.exit(1);
}
const { chromium } = await import(pathToFileURL(playwrightEntry).href);

const BASE = process.env.FRONTEND_URL || "https://127.0.0.1:5173";
const EXECUTABLE =
  process.env.BROWSER_EXECUTABLE ||
  "C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe";

const results = [];
let failures = 0;
function record(name, ok, detail) {
  results.push({ name, ok: !!ok, detail: detail || "" });
  console.log(`  [${ok ? "PASS" : "FAIL"}] ${name}${!ok && detail ? " — " + detail : ""}`);
  if (!ok) failures++;
}

/** 等待前端可达，避免脚本在服务刚启动时误报。 */
async function waitForFrontend(timeoutMs = 60000) {
  const agent = new https.Agent({ rejectUnauthorized: false });
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const ok = await new Promise((resolve) => {
      const req = https.get({ hostname: "127.0.0.1", port: 5173, path: "/", agent }, (res) => {
        res.resume();
        resolve(res.statusCode === 200);
      });
      req.on("error", () => resolve(false));
      req.setTimeout(3000, () => {
        req.destroy();
        resolve(false);
      });
    });
    if (ok) return true;
    await new Promise((r) => setTimeout(r, 1500));
  }
  return false;
}

const shots = path.join(root, "test-results", "browser");
fs.mkdirSync(shots, { recursive: true });

console.log(`\n=== 前端页面浏览器验证 @ ${BASE} ===`);
if (!(await waitForFrontend())) {
  console.error("Vite 开发服务器不可达，请先运行：npm --prefix frontend run dev");
  process.exit(1);
}
if (!fs.existsSync(EXECUTABLE)) {
  console.error(`找不到浏览器：${EXECUTABLE}（可用 BROWSER_EXECUTABLE 环境变量指定）`);
  process.exit(1);
}

const browser = await chromium.launch({ executablePath: EXECUTABLE, headless: true });
const context = await browser.newContext({
  ignoreHTTPSErrors: true,
  viewport: { width: 1440, height: 900 },
});
const page = await context.newPage();

const consoleErrors = [];
const pageErrors = [];
page.on("console", (m) => {
  if (m.type() === "error") consoleErrors.push(m.text());
});
page.on("pageerror", (e) => pageErrors.push(e.message));

async function login(account) {
  await page.goto(`${BASE}/`, { waitUntil: "domcontentloaded" });
  await page.getByLabel("账号", { exact: true }).fill(account);
  await page.getByLabel("密码", { exact: true }).fill("passwd");
  await page.getByRole("button", { name: "登录", exact: true }).click();
  await page.getByRole("button", { name: "退出登录" }).waitFor({ timeout: 20000 });
}
async function logout() {
  await page.getByRole("button", { name: "退出登录" }).click();
  await page.waitForTimeout(1200);
}
/** 侧栏导航项的文字（按显示顺序）。 */
async function navNames() {
  const texts = await page.locator("aside.sidebar nav button").allInnerTexts();
  return texts.map((t) => t.trim()).filter(Boolean);
}
/** 身份行文字：`.identity-meta` 里是「角色 · 学院 · 专业 · 班级」，空值已在组件里过滤。 */
async function identityText() {
  const meta = page.locator(".sidebar .identity .identity-meta").first();
  if ((await meta.count()) === 0) return "";
  const text = await meta.innerText();
  return text.replace(/\s+/g, " ").trim();
}
/** 身份按钮的 title，用于确认完整文本也挂在悬浮提示上。 */
async function identityTitle() {
  return (await page.locator(".sidebar .identity").first().getAttribute("title")) || "";
}

/** 关闭当前弹窗：优先点关闭按钮，再退化为 Esc / 点击遮罩空白处。 */
async function closeDialog() {
  const closers = [
    page.getByRole("button", { name: /关闭|取消|×/ }).first(),
    page.locator(".modal .icon-button").first(),
    page.locator('[aria-label="关闭"]').first(),
  ];
  for (const closer of closers) {
    if ((await closer.count()) > 0) {
      await closer.click({ timeout: 3000 }).catch(() => {});
      await page.waitForTimeout(500);
      if ((await page.getByRole("dialog").count()) === 0) return true;
    }
  }
  await page.keyboard.press("Escape");
  await page.waitForTimeout(500);
  if ((await page.getByRole("dialog").count()) === 0) return true;
  await page.locator(".modal-backdrop").first().click({ position: { x: 5, y: 5 } }).catch(() => {});
  await page.waitForTimeout(500);
  return (await page.getByRole("dialog").count()) === 0;
}

try {
  // ------------------------------------------------------------ 登录页
  await page.goto(`${BASE}/`, { waitUntil: "domcontentloaded" });
  const loginText = await page.locator("body").innerText();
  record("登录页不再出现硬编码学院名", !loginText.includes("信息工程学院"), loginText.slice(0, 160));

  // ------------------------------------------------------------ 教务管理员
  await login("admin");
  record("管理员登录成功", true);

  const nav = await navNames();
  record(
    "管理员导航顺序正确且「安全审计」在最后",
    nav[nav.length - 1] === "安全审计" && nav.includes("组织管理") && nav.includes("选课管理"),
    JSON.stringify(nav),
  );
  record("管理员导航不再有「课程与选课」", !nav.includes("课程与选课"), JSON.stringify(nav));
  record("管理员导航不再有独立的「网上选课」", !nav.includes("网上选课"), JSON.stringify(nav));

  const shellText = await page.locator("body").innerText();
  record("顶栏/页脚不再出现硬编码学院名", !shellText.includes("信息工程学院"), shellText.slice(0, 200));

  const identity = await identityText();
  record("管理员身份只显示角色、不含组织", identity.includes("管理员") && !identity.includes("·"), identity);
  record("身份行为空组织时不显示 null", !identity.includes("null") && !identity.includes("undefined"), identity);

  // 拖动调节侧栏宽度（本轮把原来的折叠按钮改成了拖拽手柄）
  const widthOf = () => page.locator("aside.sidebar").evaluate((el) => el.getBoundingClientRect().width);
  const widthBefore = await widthOf();
  record("原来的折叠按钮已移除", (await page.locator(".collapse-toggle").count()) === 0);
  const resizer = page.locator(".sidebar-resizer").first();
  record("存在侧栏宽度拖拽手柄", (await resizer.count()) > 0);
  if ((await resizer.count()) > 0) {
    const grip = await resizer.boundingBox();
    // 向右拖 110px → 变宽
    await page.mouse.move(grip.x + grip.width / 2, grip.y + grip.height / 2);
    await page.mouse.down();
    await page.mouse.move(grip.x + grip.width / 2 + 110, grip.y + grip.height / 2, { steps: 10 });
    await page.mouse.up();
    await page.waitForTimeout(300);
    const wider = await widthOf();
    record("向右拖动可加宽侧栏并实时生效", wider > widthBefore + 80, `${widthBefore} -> ${wider}`);
    const marginFollows = await page
      .locator(".main-shell")
      .evaluate((el) => parseFloat(getComputedStyle(el).marginLeft));
    record("主内容区左边距同步跟随", Math.abs(marginFollows - wider) < 6, `margin=${marginFollows} w=${wider}`);
    await page.screenshot({ path: path.join(shots, "sidebar-resized-wide.png"), fullPage: true });

    // 向左拖到仅图标档
    const grip2 = await resizer.boundingBox();
    await page.mouse.move(grip2.x + grip2.width / 2, grip2.y + grip2.height / 2);
    await page.mouse.down();
    await page.mouse.move(grip2.x + grip2.width / 2 - 300, grip2.y + grip2.height / 2, { steps: 10 });
    await page.mouse.up();
    await page.waitForTimeout(300);
    const narrow = await widthOf();
    record("向左拖动可收窄到最小 68px（仅图标档）", Math.abs(narrow - 68) < 2, `w=${narrow}`);
    record(
      "仅图标档隐藏导航文字",
      !(await page.locator("aside.sidebar nav button .nav-name").first().isVisible()),
    );
    const navTitles = await page
      .locator("aside.sidebar nav button")
      .evaluateAll((els) => els.map((e) => e.getAttribute("title") || ""));
    record("仅图标档导航按钮仍有悬浮提示", navTitles.length > 0 && navTitles.every((t) => t.length > 0), JSON.stringify(navTitles.slice(0, 3)));
    const overflowNarrow = await page.evaluate(
      () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
    );
    record("收窄后无横向滚动条", overflowNarrow <= 2, `overflow=${overflowNarrow}px`);
    await page.screenshot({ path: path.join(shots, "sidebar-resized-icon-only.png"), fullPage: true });

    // 宽度持久化 + 双击恢复默认
    record(
      "拖过的宽度写入 localStorage",
      (await page.evaluate(() => localStorage.getItem("campus.sidebarWidth"))) === String(Math.round(narrow)),
    );
    await page.reload({ waitUntil: "domcontentloaded" });
    await page.getByRole("button", { name: "退出登录" }).waitFor({ timeout: 20000 });
    record("刷新后宽度保持", Math.abs((await widthOf()) - narrow) < 2, `w=${await widthOf()}`);
    const grip3 = await page.locator(".sidebar-resizer").first().boundingBox();
    await page.mouse.dblclick(grip3.x + grip3.width / 2, grip3.y + grip3.height / 2);
    await page.waitForTimeout(400);
    record("双击手柄恢复默认 216px", Math.abs((await widthOf()) - 216) < 2, `w=${await widthOf()}`);
  }

  // 选课管理页（合并后）
  await page.getByRole("button", { name: "选课管理" }).first().click();
  await page.waitForTimeout(2000);
  const selBody = await page.locator("body").innerText();
  record(
    "选课管理页含「课程与选课 / 选课批次 / 选课记录」三个入口",
    selBody.includes("课程与选课") && selBody.includes("选课批次") && selBody.includes("选课记录"),
    selBody.replace(/\s+/g, " ").slice(0, 240),
  );
  record(
    "选课管理页能看到课程列表",
    selBody.includes("CS301") || selBody.includes("网络软件与安全"),
    selBody.replace(/\s+/g, " ").slice(0, 200),
  );
  await page.screenshot({ path: path.join(shots, "selection-admin.png"), fullPage: true });

  // 每门课程的「选课」按钮 → 按课程选课弹窗
  const enrollButtons = page.getByRole("button", { name: "选课", exact: true });
  const enrollCount = await enrollButtons.count();
  record("课程列表每行有「选课」按钮", enrollCount > 0, `count=${enrollCount}`);
  if (enrollCount > 0) {
    await enrollButtons.first().click();
    await page.waitForTimeout(2000);
    const dialogText = (await page.getByRole("dialog").innerText().catch(() => "")).replace(/\s+/g, " ");
    record("「选课」按钮打开按课程选课弹窗", dialogText.length > 0, dialogText.slice(0, 160));
    record(
      "弹窗可同时按学生与整班处理",
      dialogText.includes("学生") && dialogText.includes("班级"),
      dialogText.slice(0, 260),
    );
    await page.screenshot({ path: path.join(shots, "enroll-dialog.png"), fullPage: true });
    record("按课程选课弹窗可正常关闭", await closeDialog());
  }

  // 组织管理页：班级无辅导员、编号只读
  await page.getByRole("button", { name: "组织管理" }).first().click();
  await page.waitForTimeout(1800);
  const orgBody = await page.locator("body").innerText();
  record("组织管理页加载学院数据", orgBody.includes("信息工程学院"), orgBody.replace(/\s+/g, " ").slice(0, 160));
  record("组织管理页不出现「辅导员」", !orgBody.includes("辅导员"), orgBody.replace(/\s+/g, " ").slice(0, 200));

  // 学院标签页：列表里的编号就是数据库主键（C01001 形式），不再有单独的两位显示编号
  const collegeRows = await page.locator("tbody tr").allInnerTexts();
  record(
    "学院列表展示主键编号（C + 5 位）",
    collegeRows.length > 0 && collegeRows.every((r) => /C\d{5}/.test(r)) && !/^\s*0[1-9]\s*$/m.test(collegeRows.join("\n")),
    JSON.stringify(collegeRows.slice(0, 3).map((r) => r.replace(/\s+/g, " ").trim())),
  );

  const classTab = page.getByRole("button", { name: /班级/ }).first();
  if (await classTab.count()) {
    await classTab.click();
    await page.waitForTimeout(1400);
    const classBody = await page.locator("body").innerText();
    record("班级标签显示规范班名", /\d{4}级-.+-\d+班/.test(classBody), classBody.replace(/\s+/g, " ").slice(0, 200));
    record("班级标签不出现「辅导员」", !classBody.includes("辅导员"));
    const classRows = await page.locator("tbody tr").allInnerTexts();
    record(
      "班级列表展示主键编号（B + 5 位）",
      classRows.length > 0 && classRows.every((r) => /B\d{5}/.test(r)),
      JSON.stringify(classRows.slice(0, 2).map((r) => r.replace(/\s+/g, " ").trim())),
    );
    await page.screenshot({ path: path.join(shots, "org-classes.png"), fullPage: true });
  } else {
    record("班级标签显示规范班名", false, "未找到班级标签");
  }

  const newClassBtn = page.locator("button", { hasText: /新建班级|新增班级/ }).first();
  record("组织管理页存在「新建班级」按钮", (await newClassBtn.count()) > 0);
  if (await newClassBtn.count()) {
    await newClassBtn.click({ timeout: 8000 }).catch(() => {});
    await page.waitForTimeout(1500);
    const dialogText = (await page.getByRole("dialog").innerText().catch(() => "")).replace(/\s+/g, " ");
    const textInputs = await page
      .getByRole("dialog")
      .locator('input:not([type="checkbox"])')
      .count();
    record(
      "新建班级表单不再有「编号」输入框",
      !dialogText.includes("编号"),
      dialogText.slice(0, 160),
    );
    record(
      "新建班级表单只剩班名/年级等业务字段",
      textInputs <= 3 && !dialogText.includes("编号"),
      `textInputs=${textInputs} | ${dialogText.slice(0, 140)}`,
    );
    await closeDialog();
  } else {
    record("新建班级表单不再有「编号」输入框", false, "未找到「新建班级」按钮");
  }

  // ------------------------------------------------------------ 教师
  await logout();
  await login("t1101");
  const teacherIdentity = await identityText();
  record(
    "教师身份显示「教师 · 学院 · 专业」",
    /教师\s*·\s*信息工程学院\s*·\s*软件工程/.test(teacherIdentity),
    teacherIdentity,
  );
  record(
    "教师身份不含班级、不含 null",
    !teacherIdentity.includes("null") && !teacherIdentity.includes("undefined") && !/级-/.test(teacherIdentity),
    teacherIdentity,
  );

  // ------------------------------------------------------------ 学生
  await logout();
  await login("20241530");
  const studentNav = await navNames();
  record(
    "学生导航只有「网上选课」入口",
    studentNav.includes("网上选课") && !studentNav.includes("选课管理"),
    JSON.stringify(studentNav),
  );
  record("学生导航不出现「组织管理」", !studentNav.includes("组织管理"), JSON.stringify(studentNav));

  const studentIdentity = await identityText();
  record(
    "学生身份显示「学生 · 学院 · 专业 · 班级」",
    /学生\s*·\s*信息工程学院\s*·\s*软件工程\s*·\s*\d{4}级-.+-\d+班/.test(studentIdentity),
    studentIdentity,
  );
  record(
    "学生身份不含 null",
    !studentIdentity.includes("null") && !studentIdentity.includes("undefined"),
    studentIdentity,
  );

  await page.getByRole("button", { name: "网上选课" }).first().click();
  await page.waitForTimeout(2000);
  const studentSel = await page.locator("body").innerText();
  record(
    "学生选课台渲染（含我的选课）",
    studentSel.includes("选课") && (studentSel.includes("我的选课") || studentSel.includes("选课中心")),
    studentSel.replace(/\s+/g, " ").slice(0, 220),
  );
  await page.screenshot({ path: path.join(shots, "selection-student.png"), fullPage: true });

  const overflow = await page.evaluate(
    () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
  );
  record("学生选课页无页面级横向溢出", overflow <= 2, `overflow=${overflow}px`);

  // ------------------------------------------------------------ 重修（R4）
  // 学生端：正在重修的学生在「我的选课」里看到「重修」徽标，且课程名不带任何重修后缀。
  await logout();
  await login("20241531");
  await page.getByRole("button", { name: "网上选课" }).first().click();
  await page.waitForTimeout(2200);
  const retakingBody = await page.locator("body").innerText();
  const retakeBadges = await page.locator(".badge.amber").allInnerTexts();
  record(
    "学生端显示重修徽标",
    retakeBadges.some((t) => t.includes("重修")),
    JSON.stringify(retakeBadges),
  );
  record(
    "学生端的课程名不含「（重修）」后缀",
    !/（重修）|\(重修\)/.test(retakingBody),
    retakingBody.replace(/\s+/g, " ").slice(0, 200),
  );
  record(
    "重修徽标出现在程序设计基础（CS102）那一行",
    /程序设计基础[\s\S]{0,80}重修/.test(retakingBody.replace(/\s+/g, " ")),
    retakingBody.replace(/\s+/g, " ").slice(0, 400),
  );
  await page.screenshot({ path: path.join(shots, "retake-student.png"), fullPage: true });

  // 教师端：带重修班的教师能看到「重修」标记，且课程名同样与重修无关。
  await logout();
  await login("t1102");
  await page.waitForTimeout(2200);
  const courseSelect = page.locator("select").filter({ hasText: "程序设计基础" }).first();
  const optionLabels = await courseSelect.locator("option").allInnerTexts();
  const retakeOption = optionLabels.find((o) => /程序设计基础/.test(o) && /2026-1/.test(o));
  record("教师课程下拉里能找到 2026-1 的重修班", !!retakeOption, JSON.stringify(optionLabels));
  if (retakeOption) {
    await courseSelect.selectOption({ label: retakeOption });
    await page.waitForTimeout(2200);
    const teacherBody = await page.locator("body").innerText();
    const teacherBadges = await page.locator(".badge.amber").allInnerTexts();
    record(
      "教师端名单标出重修学生",
      teacherBadges.filter((t) => t.trim() === "重修").length >= 2,
      JSON.stringify(teacherBadges),
    );
    record(
      "教师端课程名同样是「程序设计基础」（无重修后缀）",
      /程序设计基础/.test(teacherBody) && !/（重修）|\(重修\)/.test(teacherBody),
      teacherBody.replace(/\s+/g, " ").slice(0, 200),
    );
    await page.screenshot({ path: path.join(shots, "retake-teacher-roster.png"), fullPage: true });
  }

  // ------------------------------------------------- 我的成绩 + 学业预警（R5）
  // 学生端「我的成绩」要能看到某门课是不是重修；学业预警要能真的出预测结果。
  await logout();
  await login("20231530");
  await page.getByRole("button", { name: "我的成绩" }).first().click();
  await page.waitForTimeout(2200);
  const transcriptBody = (await page.locator("body").innerText()).replace(/\s+/g, " ");
  const transcriptBadges = await page.locator("tbody .badge.amber").allInnerTexts();
  record(
    "「我的成绩」显示重修徽标",
    transcriptBadges.some((t) => t.includes("重修")),
    JSON.stringify(transcriptBadges),
  );
  record(
    "「我的成绩」里 CS102 的两行课程名一致且不含「（重修）」",
    !/（重修）|\(重修\)/.test(transcriptBody) && (transcriptBody.match(/程序设计基础/g) || []).length >= 2,
    transcriptBody.slice(0, 300),
  );
  record(
    "「我的成绩」有 2 行程序设计基础（挂科 + 重修）",
    (transcriptBody.match(/程序设计基础/g) || []).length >= 2,
    transcriptBody.slice(0, 300),
  );
  await page.screenshot({ path: path.join(shots, "transcript-retake.png"), fullPage: true });

  // 学业预警：学生点「生成预测」应得到预测行，而不是「数据不足」提示
  // （教师导航里没有学业预警；教师侧的预测入口在课程成绩页，接口相同）
  await logout();
  await login("20231530");
  await page.getByRole("button", { name: "学业预警" }).first().click();
  await page.waitForTimeout(2200);
  const predictBtn = page.getByRole("button", { name: /生成预警|生成预测/ }).first();
  record("学业预警页有生成预测按钮", (await predictBtn.count()) > 0);
  if (await predictBtn.count()) {
    await predictBtn.click();
    await page.waitForTimeout(4000);
    const predBody = (await page.locator("body").innerText()).replace(/\s+/g, " ");
    record(
      "学业预警不再提示「数据不足」",
      !predBody.includes("数据不足"),
      predBody.slice(0, 300),
    );
    record(
      "学业预警给出训练年份与样本数",
      /历史年份/.test(predBody) && /条训练样本/.test(predBody),
      predBody.slice(0, 300),
    );
    const predRows = await page.locator("tbody tr").count();
    record("学业预警渲染出预测结果行", predRows > 0, `rows=${predRows}`);
    await page.screenshot({ path: path.join(shots, "prediction-result.png"), fullPage: true });
  }

  record("浏览器无未捕获的页面错误", pageErrors.length === 0, pageErrors.slice(0, 3).join(" | "));
  const fatal = consoleErrors.filter((t) => !/favicon|net::ERR_|Failed to load resource/i.test(t));
  record("浏览器无致命控制台错误", fatal.length === 0, fatal.slice(0, 3).join(" | "));
} catch (e) {
  record("浏览器验证执行完成", false, e.message);
} finally {
  await browser.close();
}

const passed = results.filter((r) => r.ok).length;
console.log(`\n=== 结果：${passed}/${results.length} 通过，${failures} 失败 ===`);
fs.mkdirSync(path.join(root, ".runtime", "logs"), { recursive: true });
fs.writeFileSync(
  path.join(root, ".runtime", "logs", "browser-check.json"),
  JSON.stringify(
    { generatedAt: new Date().toISOString(), base: BASE, total: results.length, passed, failed: failures, results },
    null,
    2,
  ) + "\n",
);
console.log("证据已写入 .runtime/logs/browser-check.json，截图在 test-results/browser/");
process.exit(failures === 0 ? 0 : 1);
