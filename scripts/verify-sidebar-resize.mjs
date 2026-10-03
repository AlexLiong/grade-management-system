#!/usr/bin/env node
/**
 * 侧栏拖拽调节 + 组织管理编号只读的浏览器验证（一次性检查脚本）。
 *
 * 前置：Vite 在 https://127.0.0.1:5173，四个 Java 服务已启动。
 * 用法：node scripts/verify-sidebar-resize.mjs
 */
import fs from "node:fs";
import path from "node:path";
import https from "node:https";
import { fileURLToPath, pathToFileURL } from "node:url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const playwrightEntry = path.join(root, "frontend", "node_modules", "playwright", "index.mjs");
const { chromium } = await import(pathToFileURL(playwrightEntry).href);

const BASE = process.env.FRONTEND_URL || "https://127.0.0.1:5173";
const EXECUTABLE =
  process.env.BROWSER_EXECUTABLE || "C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe";

const results = [];
let failures = 0;
function record(name, ok, detail) {
  results.push({ name, ok: !!ok, detail: detail || "" });
  console.log(`  [${ok ? "PASS" : "FAIL"}] ${name}${!ok && detail ? " — " + detail : ""}`);
  if (!ok) failures++;
}

const shots = path.join(root, "test-results", "browser");
fs.mkdirSync(shots, { recursive: true });

const browser = await chromium.launch({ executablePath: EXECUTABLE, headless: true });
const ctx = await browser.newContext({ ignoreHTTPSErrors: true, viewport: { width: 1440, height: 900 } });
const page = await ctx.newPage();

const pageErrors = [];
page.on("pageerror", (e) => pageErrors.push(e.message));

try {
  await page.goto(`${BASE}/`, { waitUntil: "domcontentloaded" });
  await page.getByLabel("账号", { exact: true }).fill("admin");
  await page.getByLabel("密码", { exact: true }).fill("passwd");
  await page.getByRole("button", { name: "登录", exact: true }).click();
  await page.getByRole("button", { name: "退出登录" }).waitFor({ timeout: 20000 });

  // ---------------------------------------------------------------- 1) 拖拽调节
  record("原来的折叠按钮已移除", (await page.locator(".collapse-toggle").count()) === 0);
  const resizer = page.locator(".sidebar-resizer").first();
  record("存在拖拽手柄", (await resizer.count()) > 0);
  record("手柄可拖拽（cursor: col-resize）", (await resizer.evaluate((e) => getComputedStyle(e).cursor)) === "col-resize");

  const width = () => page.locator("aside.sidebar").evaluate((el) => el.getBoundingClientRect().width);
  const margin = () =>
    page.locator(".main-shell").evaluate((el) => parseFloat(getComputedStyle(el).marginLeft));
  const box = await resizer.boundingBox();
  const startWidth = await width();
  record("默认宽度为 216px", Math.abs(startWidth - 216) < 2, `w=${startWidth}`);

  // 向右拖 120px → 变宽
  await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
  await page.mouse.down();
  await page.mouse.move(box.x + box.width / 2 + 120, box.y + box.height / 2, { steps: 12 });
  const duringWidth = await width();
  await page.mouse.up();
  await page.waitForTimeout(300);
  const wider = await width();
  record("向右拖动时实时变宽", duringWidth > startWidth + 80, `during=${duringWidth}`);
  record("松手后宽度保持（约 336px）", Math.abs(wider - (startWidth + 120)) < 6, `w=${wider}`);
  record("主内容区左边距同步跟随", Math.abs((await margin()) - wider) < 6, `margin=${await margin()} w=${wider}`);
  await page.screenshot({ path: path.join(shots, "sidebar-resized-wide.png") });

  // 向左拖 260px → 变窄到紧凑档
  const box2 = await resizer.boundingBox();
  await page.mouse.move(box2.x + box2.width / 2, box2.y + box2.height / 2);
  await page.mouse.down();
  await page.mouse.move(box2.x + box2.width / 2 - 190, box2.y + box2.height / 2, { steps: 12 });
  await page.mouse.up();
  await page.waitForTimeout(300);
  const compact = await width();
  record("向左拖动可收窄到 146px 左右", Math.abs(compact - 146) < 8, `w=${compact}`);
  record(
    "紧凑档仍显示导航文字",
    await page.locator("aside.sidebar nav button .nav-name").first().isVisible(),
  );
  const overflowCompact = await page.evaluate(
    () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
  );
  record("收窄后无横向滚动条", overflowCompact <= 2, `overflow=${overflowCompact}px`);
  await page.screenshot({ path: path.join(shots, "sidebar-resized-compact.png") });

  // 继续拖到极小 → 仅图标档
  const box3 = await resizer.boundingBox();
  await page.mouse.move(box3.x + box3.width / 2, box3.y + box3.height / 2);
  await page.mouse.down();
  await page.mouse.move(box3.x + box3.width / 2 - 200, box3.y + box3.height / 2, { steps: 12 });
  await page.mouse.up();
  await page.waitForTimeout(300);
  const iconOnly = await width();
  record("可拖到最小 68px（仅图标）", Math.abs(iconOnly - 68) < 2, `w=${iconOnly}`);
  record(
    "仅图标档隐藏导航文字",
    !(await page.locator("aside.sidebar nav button .nav-name").first().isVisible()),
  );
  const titles = await page
    .locator("aside.sidebar nav button")
    .evaluateAll((els) => els.map((e) => e.getAttribute("title") || ""));
  record("仅图标档仍保留 title 悬浮提示", titles.length > 0 && titles.every((t) => t.length > 0));
  await page.screenshot({ path: path.join(shots, "sidebar-resized-icononly.png") });

  // 上限与持久化
  const stored = await page.evaluate(() => localStorage.getItem("campus.sidebarWidth"));
  record("宽度写入 localStorage", stored === String(Math.round(iconOnly)), `stored=${stored}`);
  await page.reload({ waitUntil: "domcontentloaded" });
  await page.getByRole("button", { name: "退出登录" }).waitFor({ timeout: 20000 });
  record("刷新后宽度保持", Math.abs((await width()) - iconOnly) < 2, `w=${await width()}`);
  await page.screenshot({ path: path.join(shots, "sidebar-resized-persisted.png") });

  // 双击恢复默认
  const box4 = await resizer.boundingBox();
  await page.mouse.dblclick(box4.x + box4.width / 2, box4.y + box4.height / 2);
  await page.waitForTimeout(400);
  record("双击手柄恢复默认 216px", Math.abs((await width()) - 216) < 2, `w=${await width()}`);
  record(
    "恢复默认后清除持久化值",
    (await page.evaluate(() => localStorage.getItem("campus.sidebarWidth"))) === null,
  );

  // 拖到超宽上限
  const box5 = await resizer.boundingBox();
  await page.mouse.move(box5.x + box5.width / 2, box5.y + box5.height / 2);
  await page.mouse.down();
  await page.mouse.move(box5.x + 900, box5.y + box5.height / 2, { steps: 10 });
  await page.mouse.up();
  await page.waitForTimeout(300);
  record("宽度有上限 420px", Math.abs((await width()) - 420) < 2, `w=${await width()}`);
  await page.mouse.dblclick((await resizer.boundingBox()).x + 3, 400);
  await page.waitForTimeout(300);

  // ------------------------------------------------- 2) 组织管理：编号只用主键 id
  await page.getByRole("button", { name: "组织管理" }).first().click();
  await page.waitForTimeout(1800);
  const orgText = await page.locator("body").innerText();
  record("列表展示主键编号 C01001", /C0\d{4}/.test(orgText), orgText.replace(/\s+/g, " ").slice(0, 160));
  record("列表不再出现两位显示编号（01/02…）", !/^\s*0[1-9]\s*$/m.test(orgText));

  const newBtn = page.locator("button", { hasText: /新增学院/ }).first();
  await newBtn.click({ timeout: 8000 }).catch(() => {});
  await page.waitForTimeout(1200);
  const dialogText = (await page.getByRole("dialog").innerText()).replace(/\s+/g, " ");
  record("新增学院弹窗已打开", dialogText.includes("学院名称"), dialogText.slice(0, 120));
  record("弹窗不再有「编号」字段", !dialogText.includes("编号"), dialogText);
  const dialogInputs = await page
    .getByRole("dialog")
    .locator('input:not([type="checkbox"])')
    .count();
  record("弹窗只剩名称/简称/简介三个文本输入框", dialogInputs === 3, `inputs=${dialogInputs}`);
  await page.screenshot({ path: path.join(shots, "org-form-no-code.png") });

  // 真实新建一个学院，确认回执里的编号是主键格式，然后删除
  const stamp = String(Date.now()).slice(-6);
  const rowsBeforeCreate = await page.locator("tbody tr").count();
  await page.getByRole("dialog").locator("input").first().fill(`测试学院${stamp}`);
  await page.getByRole("dialog").getByRole("button", { name: "保存" }).click();
  await page.waitForTimeout(2200);
  const toast = await page.locator("body").innerText();
  const created = (toast.match(/C0\d{4}/g) || []).pop();
  record("新建回执给出主键编号", !!created && /^C0\d{4}$/.test(created), `id=${created} ${toast.replace(/\s+/g, " ").slice(0, 140)}`);
  record("新建后列表多出一行", (await page.locator("tbody tr").count()) === rowsBeforeCreate + 1, `before=${rowsBeforeCreate} after=${await page.locator("tbody tr").count()}`);
  await page.screenshot({ path: path.join(shots, "org-created-by-id.png") });

  if (created) {
    // 删除确认弹窗的按钮文案是「确认删除」，不是「确认」。
    // 注意两点：① 要删除的是「刚建的那一行」，不是列表第一行；
    // ② 删除成功的提示里也会出现学院名，所以断言要看「列表行」，不能看整页文本。
    const targetRow = page.locator("tbody tr").filter({ hasText: `测试学院${stamp}` }).first();
    await targetRow.getByRole("button", { name: "删除" }).click();
    await page.waitForTimeout(900);
    await page.getByRole("button", { name: "确认删除" }).click().catch(() => {});
    await page.waitForTimeout(2200);
    const remaining = await page.locator("tbody tr").allInnerTexts();
    record(
      "可删除刚建的测试学院",
      !remaining.some((r) => r.includes(`测试学院${stamp}`)),
      `remaining=${remaining.length} rows`,
    );
    record(
      "删除后列表行数回到新建前",
      (await page.locator("tbody tr").count()) === rowsBeforeCreate,
      `rows=${await page.locator("tbody tr").count()} expected=${rowsBeforeCreate}`,
    );
  }

  record("浏览器无未捕获的页面错误", pageErrors.length === 0, pageErrors.slice(0, 3).join(" | "));
} catch (e) {
  record("验证脚本执行完成", false, e.message);
} finally {
  await browser.close();
}

const passed = results.filter((r) => r.ok).length;
console.log(`\n=== 结果：${passed}/${results.length} 通过，${failures} 失败 ===`);
fs.mkdirSync(path.join(root, ".runtime", "logs"), { recursive: true });
fs.writeFileSync(
  path.join(root, ".runtime", "logs", "sidebar-resize-check.json"),
  JSON.stringify({ generatedAt: new Date().toISOString(), total: results.length, passed, failed: failures, results }, null, 2) + "\n",
);
process.exit(failures === 0 ? 0 : 1);
