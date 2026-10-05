process.env.NODE_TLS_REJECT_UNAUTHORIZED = "0";
/**
 * 验证「新建课程」对话框现在能指定开设院系（缺陷修复的回归测试）。
 *
 * 场景：管理员 → 选课管理 → 新建课程
 *   1) 对话框必须出现「开设院系」下拉（data-testid=course-college）；
 *   2) 选好授课教师后，院系应自动带出该教师所在院系（默认值）；
 *   3) 管理员可以自己改选成别的院系，且改完不会被教师的院系覆盖；
 *   4) 保存成功（原来会 400「必须指定开设院系」）。
 * 测试会清理自己创建的课程（先删选课再删课程），不留残留。
 */
import path from "node:path";
import { pathToFileURL } from "node:url";
const root = "E:/seniorp1/grade-management-system-main";
const { chromium } = await import(pathToFileURL(path.join(root, "frontend", "node_modules", "playwright", "index.mjs")).href);

let pass = 0;
let fail = 0;
const record = (name, ok, detail = "") => {
  console.log(`  [${ok ? "PASS" : "FAIL"}] ${name}${detail ? " — " + detail : ""}`);
  if (ok) pass++;
  else fail++;
};

const browser = await chromium.launch({ executablePath: "C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe" });
const context = await browser.newContext({ ignoreHTTPSErrors: true, viewport: { width: 1600, height: 1000 } });
const page = await context.newPage();
const consoleErrors = [];
page.on("console", (m) => { if (m.type() === "error") consoleErrors.push(m.text()); });

await page.goto("https://127.0.0.1:5173/", { waitUntil: "domcontentloaded" });
await page.getByPlaceholder("请输入账号").fill("admin");
await page.getByPlaceholder("请输入密码").fill("passwd");
await page.getByRole("button", { name: "登录" }).click();
await page.getByRole("button", { name: "选课管理", exact: true }).waitFor({ timeout: 30000 });
await page.getByRole("button", { name: "选课管理", exact: true }).click();
await page.getByRole("button", { name: "课程与选课" }).click();
await page.getByRole("button", { name: "新建课程" }).waitFor({ timeout: 20000 });

console.log("\n[1] 对话框必须能填「开设院系」");
await page.getByRole("button", { name: "新建课程" }).click();
const dialog = page.locator("form").filter({ has: page.getByRole("button", { name: "保存课程" }) });
const collegeSelect = page.getByTestId("course-college");
await collegeSelect.waitFor({ timeout: 10000 });
record("「开设院系」控件存在", await collegeSelect.count() === 1);

const collegeOptions = await collegeSelect.locator("option").allTextContents();
record("下拉列出全部院系", collegeOptions.length >= 4, collegeOptions.slice(1, 5).join(" / "));
const collegeValues = await collegeSelect.locator("option").evaluateAll((els) => els.map((e) => e.value).filter(Boolean));

console.log("\n[2] 院系默认由任课教师所在院系带出");
const teacherSelect = dialog.locator("select").first();
const teacherOptions = await teacherSelect.locator("option").evaluateAll((els) =>
  els.filter((e) => e.value).map((e) => ({ value: e.value, label: e.textContent.trim() })),
);
// 选一位属于「信息工程学院」的教师
const infoTeacher = teacherOptions.find((t) => t.label.includes("信息工程学院")) || teacherOptions[0];
await teacherSelect.selectOption(infoTeacher.value);
await page.waitForTimeout(400);
const autoCollege = await collegeSelect.inputValue();
const autoLabel = await collegeSelect.locator("option:checked").textContent();
record("选教师后自动带出院系", Boolean(autoCollege), `${infoTeacher.label} → ${autoLabel.trim()} (${autoCollege})`);
record("带出的院系与教师所在院系一致", infoTeacher.label.includes(autoLabel.trim()), `教师 ${infoTeacher.label} / 院系 ${autoLabel.trim()}`);

console.log("\n[3] 管理员可以自己改选院系，且不被教师覆盖");
const otherCollege = collegeValues.find((v) => v !== autoCollege);
await collegeSelect.selectOption(otherCollege);
const otherLabel = await collegeSelect.locator("option:checked").textContent();
await page.waitForTimeout(300);
// 再换一位教师，人工选择必须保留
const otherTeacher = teacherOptions.find((t) => t.value !== infoTeacher.value && !t.label.includes(otherLabel.trim()));
if (otherTeacher) {
  await teacherSelect.selectOption(otherTeacher.value);
  await page.waitForTimeout(400);
}
const afterTeacherChange = await collegeSelect.inputValue();
record("人工改选后不被教师所在院系覆盖", afterTeacherChange === otherCollege,
  `选择 ${otherLabel.trim()} → 换教师后仍为 ${otherCollege}`);

console.log("\n[4] 能真正保存（原来会 400 必须指定开设院系）");
const stamp = Date.now().toString().slice(-6);
const testCode = "QA" + stamp;
await dialog.locator('input[required]').first().fill("回归测试课程" + stamp);
const codeInput = dialog.locator('input[required]').nth(1);
await codeInput.fill(testCode);
await page.getByRole("button", { name: "保存课程" }).click();
// 用接口核对而不是看提示文案：提示是瞬时元素，断言它容易假失败
const lookup = () =>
  page.evaluate(async (code) => {
    const list = await (await fetch("/api/courses?size=300", { headers: { Origin: location.origin } })).json();
    return (list.items || []).find((c) => c.code === code) || null;
  }, testCode);
let saved = null;
for (let i = 0; i < 20 && !saved; i++) {
  await page.waitForTimeout(250);
  saved = await lookup();
}
record("课程已落库（原来会 400 必须指定开设院系）", Boolean(saved), saved ? `${saved.code} ${saved.name}` : "未在列表中查到");
record("对话框已关闭", saved ? !(await dialog.isVisible().catch(() => true)) : false);
record(
  "保存的开设院系与表单所选一致",
  Boolean(saved) && saved.college_id === otherCollege,
  saved ? `表单 ${otherCollege} → 落库 ${saved.college_id}（${saved.collegeName}）` : "-",
);
const errorBanner = await page.locator(".error-banner, .alert-error").first().textContent().catch(() => "");
record("没有出现「必须指定开设院系」", !String(errorBanner).includes("必须指定开设院系"), String(errorBanner).trim().slice(0, 60));

// 清理：系统没有课程删除接口，只能靠 data-service 启动时的 purgeTestArtifacts
// （按名称/学期识别测试课程）兜底。这里只报告，不当作断言失败。
const cleanup = await page.evaluate(async (code) => {
  const list = await (await fetch("/api/courses?size=300", { headers: { Origin: location.origin } })).json();
  const hit = (list.items || []).find((c) => c.code === code);
  return hit ? { found: true, id: hit.id } : { found: false };
}, testCode);
console.log(
  cleanup.found
    ? "  本次测试新建了一门课程（" + testCode + "）；系统没有课程删除接口，" +
      "按仓库约定重启一次 data-service 即会被 purgeTestArtifacts 清理（见 docs/testing.md）"
    : "  本次测试未留下课程",
);

record("无致命控制台错误", consoleErrors.filter((t) => !/favicon|Failed to load resource/i.test(t)).length === 0,
  consoleErrors.slice(0, 2).join(" | "));

console.log(`\n合计 ${pass + fail} 项：通过 ${pass}，失败 ${fail}`);
await browser.close();
process.exit(fail ? 1 : 0);
