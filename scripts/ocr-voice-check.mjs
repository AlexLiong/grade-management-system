#!/usr/bin/env node
/**
 * 成绩录入辅助（图片识别 + 语音录入）的浏览器级功能测试。
 *
 * 前置：
 *   1. 四个 Java 服务已启动（`scripts/start.ps1`，或 IDEA 里运行四个 Application）；
 *   2. 前端开发服务器在 https://127.0.0.1:5173 运行（`npm --prefix frontend run dev`）；
 *   3. 固定测试图集已生成（`node scripts/generate-ocr-fixtures.mjs`）。
 *
 * 用法：node scripts/ocr-voice-check.mjs
 * 证据：.runtime/logs/ocr-voice-check.json，截图落在 test-results/browser/
 *
 * 覆盖：
 *   [1] 图片识别：固定图集逐张走真实识别管线，统计字段级准确率与容错行为
 *   [2] 预览确认：未点"确认填入"时表单不变；点确认后才写进录入表单
 *   [3] 坏行处理：名册外/无分数的行不参与填入，其余行照常填入
 *   [4] 语音录入：解析、填入、越界拒绝；用 SpeechRecognition 桩验证真实接入路径
 *   [5] 隐私与接口：整轮识别不产生任何带图片的上行请求
 */
import fs from "node:fs";
import path from "node:path";
import https from "node:https";
import { fileURLToPath, pathToFileURL } from "node:url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const playwrightEntry = path.join(root, "frontend", "node_modules", "playwright", "index.mjs");
if (!fs.existsSync(playwrightEntry)) {
  console.error(`找不到 playwright：${playwrightEntry}（先执行 npm --prefix frontend install）`);
  process.exit(1);
}
const { chromium } = await import(pathToFileURL(playwrightEntry).href);

const BASE = process.env.FRONTEND_URL || "https://127.0.0.1:5173";
const EXECUTABLE =
  process.env.BROWSER_EXECUTABLE ||
  "C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe";
const TEACHER = process.env.OCR_TEACHER || "t1102";
const PASSWORD = process.env.OCR_PASSWORD || "passwd";
const COURSE_HINT = process.env.OCR_COURSE || "CS403";

const fixtureDir = path.join(root, "test-results", "ocr");
const manifestPath = path.join(fixtureDir, "manifest.json");
if (!fs.existsSync(manifestPath)) {
  console.error("缺少测试图集，请先执行 node scripts/generate-ocr-fixtures.mjs");
  process.exit(1);
}
const manifest = JSON.parse(fs.readFileSync(manifestPath, "utf8"));
const fixtureByName = new Map(manifest.fixtures.map((f) => [f.name, f]));

const results = [];
let failures = 0;
function record(name, ok, detail = "") {
  results.push({ name, ok: Boolean(ok), detail: detail || "" });
  console.log(`  [${ok ? "PASS" : "FAIL"}] ${name}${!ok && detail ? "  — " + detail : ""}`);
  if (!ok) failures++;
}

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

if (!(await waitForFrontend())) {
  console.error(`前端不可达：${BASE}（请先 npm --prefix frontend run dev）`);
  process.exit(1);
}

const browser = await chromium.launch({ executablePath: EXECUTABLE });
const context = await browser.newContext({ ignoreHTTPSErrors: true, viewport: { width: 1440, height: 900 } });

/* ------------------------------------------------ 注入语音识别桩（可选） */

await context.addInitScript(() => {
  // 测试用桩：只提供接口形态与可控的文本回放，不接触真实麦克风。
  class FakeSpeechRecognition {
    constructor() {
      this.lang = "";
      this.continuous = false;
      this.interimResults = false;
      this.maxAlternatives = 1;
      window.__fakeSpeech = this;
    }
    start() {
      this.started = true;
    }
    stop() {
      this.started = false;
      this.onend?.({});
    }
    abort() {
      this.started = false;
    }
    /** 测试驱动：回放一条最终识别结果。 */
    emit(text) {
      this.onresult?.({
        resultIndex: 0,
        results: Object.assign([{ 0: { transcript: text }, isFinal: true, length: 1 }], { length: 1 }),
      });
    }
  }
  window.SpeechRecognition = FakeSpeechRecognition;
  window.webkitSpeechRecognition = FakeSpeechRecognition;
});

const page = await context.newPage();
const consoleErrors = [];
page.on("console", (msg) => {
  if (msg.type() === "error") consoleErrors.push(msg.text());
});
const imageUploads = [];
page.on("request", (request) => {
  const body = request.postDataBuffer?.();
  if (request.method() === "POST" && body && body.includes(Buffer.from("PNG")))
    imageUploads.push(request.url());
});

/* --------------------------------------------------------------- 登录 */

async function login() {
  await page.goto(BASE + "/", { waitUntil: "domcontentloaded" });
  await page.getByPlaceholder("请输入账号").fill(TEACHER);
  await page.getByPlaceholder("请输入密码").fill(PASSWORD);
  await page.getByRole("button", { name: "登录" }).click();
  await page.getByRole("button", { name: "课程成绩", exact: true }).waitFor({ timeout: 30000 });
}

console.log("\n[1] 登录并进入成绩录入");
await login();
record("教师登录成功", true, TEACHER);

const courseOptions = await page.locator("select[aria-label='选择课程'] option").allTextContents();
// 端到端脚本会在测试学期留下空壳课程/课程名含「测试」的课，优先挑用户指定的课程，
// 其次挑第一门**确实有学生**的课，避免整轮检查因为选到空课程而失败。
const ordered = [
  ...courseOptions.filter((text) => text.includes(COURSE_HINT)),
  ...courseOptions.filter((text) => !text.includes(COURSE_HINT)),
];
let targetOption = "";
for (const option of ordered) {
  await page.locator("select[aria-label='选择课程']").selectOption({ label: option });
  await page.waitForTimeout(500);
  const students = await page.evaluate(
    () => new Set([...document.querySelectorAll(".grade-table input[aria-label]")].map((i) => i.getAttribute("aria-label").split(" ")[0])).size,
  );
  if (students > 0) {
    targetOption = option;
    break;
  }
}
if (!targetOption) {
  record("存在可录入的课程", false, "所有课程都没有学生");
  await browser.close();
  process.exit(1);
}
record("选择课程", true, targetOption.trim());

const loadInfo = await page.evaluate(() => {
  const table = document.querySelector(".grade-table");
  return {
    rows: table ? table.querySelectorAll("tbody tr").length : 0,
    hasOcrButton: !!document.querySelector("[data-testid='ocr-open']"),
    hasVoiceButton: !!document.querySelector("[data-testid='voice-open']"),
  };
});
record("成绩表渲染出学生行", loadInfo.rows > 0, `rows=${loadInfo.rows}`);
record("存在「识别成绩单」按钮", loadInfo.hasOcrButton);
record("存在「语音录入」按钮", loadInfo.hasVoiceButton);

// 名册直接从页面读取：录入表单每个分数格都有 `学号 成绩项` 的 aria-label，
// 这样测的是真实页面数据，不需要另开接口或注入假名册。
const roster = await page.evaluate(() => {
  const usernames = new Set();
  for (const input of document.querySelectorAll(".grade-table input[aria-label]")) {
    const match = input.getAttribute("aria-label").match(/^(\S+)\s/);
    if (match) usernames.add(match[1]);
  }
  return [...usernames].map((username, index) => ({
    id: "s" + index,
    username,
    name: "学生" + username.slice(-2),
  }));
});
await page.evaluate((list) => {
  window.__roster = list;
}, roster);
record("从页面读到名册", roster.length > 0, `count=${roster.length}`);

/* -------------------------------------------- [2] 图片识别：图集准确率 */

console.log("\n[2] 图片识别：固定图集 + 字段级准确率");

/** 每个图集用"与实际列数一致的"成绩项来测，避免把"没映射的列"算成错误。 */
const fixtureCases = {
  "clean-2col": ["regular", "finalExam"],
  "clean-3col": ["regular", "lab", "finalExam"],
  "clean-6col": ["regular", "attendance", "homework", "lab", "midterm", "finalExam"],
  "skew-2deg": ["regular", "lab", "finalExam"],
  "skew-4deg": ["regular", "lab", "finalExam"],
  "shadow-noise": ["regular", "lab", "finalExam"],
  "confusable-id": ["regular", "lab", "finalExam"],
  "dirty-missing-and-range": ["regular", "lab", "finalExam"],
  "dirty-unknown-student": ["regular", "lab", "finalExam"],
  "dirty-blank-row": ["regular", "lab", "finalExam"],
};
const LABELS = {
  regular: "平时",
  attendance: "考勤",
  homework: "作业",
  lab: "实验",
  midterm: "期中",
  finalExam: "期末",
};

/** 在页面里执行真实识别管线（与 App 走同一个 recognizeBest），返回结构化结果与统计。 */
async function runPipeline(imageBase64, components) {
  return page.evaluate(
    async ({ image, components: comps }) => {
      const ocr = await import("/src/ocr.js");
      const imageElement = await new Promise((resolve, reject) => {
        const img = new Image();
        img.onload = () => resolve(img);
        img.onerror = () => reject(new Error("image decode failed"));
        img.src = "data:image/png;base64," + image;
      });
      const canvas = ocr.loadCanvas(imageElement);
      const started = performance.now();
      const result = await ocr.recognizeBest(canvas, () => {}, {
        roster: window.__roster,
        components: comps,
        goodEnough: 1,
      });
      const elapsed = Math.round(performance.now() - started);
      const best = result.best;
      const detected = best ? ocr.detectColumns(best.rows) : { columns: [], tokens: [], rows: [] };
      const preview = best
        ? ocr.buildPreview(
            best.rows,
            comps,
            window.__roster,
            ocr.defaultColumnMap(detected.columns.length, comps.length),
          )
        : { columns: [], tokens: [], rows: [], previewRows: [], summary: { usable: 0 } };
      const rows = preview.previewRows.map((row) => ({
        username: row.username,
        matched: row.matched,
        corrected: row.corrected,
        cells: row.cells.map((c) => c.value),
        issues: row.issues,
        confidence: row.confidence,
      }));
      return {
        elapsed,
        skew: result.skew,
        variants: result.variants.length,
        best: best
          ? {
              id: best.id,
              label: best.label,
              columns: preview.columns.length,
              usable: preview.summary.usable,
              meanConfidence: rows.length
                ? Math.round((rows.reduce((t, r) => t + r.confidence, 0) / rows.length) * 10) / 10
                : 0,
              rows,
            }
          : null,
      };
    },
    { image: imageBase64, components },
  );
}

/** 把固定图集灌进名册（与演示账号无关，只用学号做匹配验证）。 */
const fixtureStudents = new Set();
for (const fixture of manifest.fixtures)
  for (const row of fixture.rows) fixtureStudents.add(String(row[0]));
await page.evaluate((usernames) => {
  window.__roster = usernames.map((username, index) => ({
    id: "s" + index,
    username,
    name: "学生" + username.slice(-2),
  }));
}, [...fixtureStudents].filter((u) => /^\d{8}$/.test(u)));

// 排障钩子：OCR_DEBUG=1 时把每个变体的中间结果落盘，便于定位"识别到了但没映射上"
await page.evaluate(() => {
  window.__debugPipeline = async (image, components, _unused) => {
    const ocr = await import("/src/ocr.js");
    const el = await new Promise((resolve, reject) => {
      const img = new Image();
      img.onload = () => resolve(img);
      img.onerror = reject;
      img.src = "data:image/png;base64," + image;
    });
    const canvas = ocr.loadCanvas(el);
    const result = await ocr.recognizeSheet(canvas, () => {}, { goodEnough: Infinity });
    return {
      roster: window.__roster,
      variants: result.variants.map((v) => {
        const detected = ocr.detectColumns(v.rows);
        const mapped = ocr.mapColumns({
          rows: detected.rows,
          tokens: detected.tokens,
          components,
          roster: window.__roster,
          columnMap: ocr.defaultColumnMap(detected.columns.length, components.length),
        });
        return {
          id: v.id,
          bandRows: v.rows.length,
          detectedRows: detected.rows.length,
          columns: detected.columns.map((c) => Math.round(c)),
          tokensPerRow: detected.tokens.map((t) => t.map((x) => (x ? x.text : null))),
          mapped: mapped.map((row) => ({ u: row.username, cells: row.cells.map((c) => c.value) })),
        };
      }),
    };
  };
});

const accuracyReport = [];
for (const fixture of manifest.fixtures) {
  const keys = fixtureCases[fixture.name] ?? ["regular", "lab", "finalExam"];
  const comps = keys.map((key) => ({ key, label: LABELS[key] }));
  const file = path.join(fixtureDir, `${fixture.name}.png`);
  const base64 = fs.readFileSync(file).toString("base64");
  const outcome = await runPipeline(base64, comps);
  if (process.env.OCR_DEBUG)
    fs.writeFileSync(
      path.join(root, ".runtime", "logs", `ocr-debug-${fixture.name}.json`),
      JSON.stringify({ fixture: fixture.name, components: comps, roster: null, outcome }, null, 2) + "\n",
    );
  const columns = fixture.header.length - 1;
  const expected = fixture.rows;
  const actual = outcome.best?.rows ?? [];

  let total = 0;
  let correct = 0;
  const missed = [];
  for (const want of expected) {
    const got = actual.find((row) => row.username === String(want[0]));
    for (let c = 0; c < columns; c++) {
      const rawWant = want[c + 1];
      const wantValue = rawWant === "" ? null : Number(rawWant);
      // 超界分（如 120）按设计必须留空，作为"期望 null"参与统计
      const normalizedWant = wantValue !== null && wantValue > 100 ? null : wantValue;
      total++;
      const gotValue = got ? got.cells[c] : null;
      if (gotValue === normalizedWant) correct++;
      else missed.push(`${want[0]}#${c}: 期望 ${normalizedWant} 实际 ${gotValue}`);
    }
  }
  const accuracy = total ? correct / total : 0;
  accuracyReport.push({
    fixture: fixture.name,
    note: fixture.note,
    components: keys,
    elapsedMs: outcome.elapsed,
    skew: outcome.skew,
    bestVariant: outcome.best?.id ?? "",
    variants: outcome.variants,
    rows: actual.length,
    matchedRows: actual.filter((row) => row.matched).length,
    expectedRows: expected.length,
    accuracy: Math.round(accuracy * 1000) / 10,
    correct,
    total,
    missed: missed.slice(0, 12),
  });
  console.log(
    `  · ${fixture.name}: 准确率 ${(accuracy * 100).toFixed(1)}% (${correct}/${total})` +
      ` 行 ${actual.length}/${expected.length} 变体 ${outcome.best?.id} 用时 ${outcome.elapsed}ms`,
  );
}

const cleanFixtures = accuracyReport.filter((r) => r.fixture.startsWith("clean"));
const cleanAccuracy =
  cleanFixtures.reduce((t, r) => t + r.correct, 0) /
  Math.max(1, cleanFixtures.reduce((t, r) => t + r.total, 0));
record(
  "清晰打印体字段级准确率 ≥ 90%",
  cleanAccuracy >= 0.9,
  `实测 ${(cleanAccuracy * 100).toFixed(1)}%（目标 ≥90%，理想 ≥95%）`,
);

const cleanRowRecall = cleanFixtures.reduce(
  (t, r) => t + Math.min(r.matchedRows, r.expectedRows),
  0,
);
const cleanRowTotal = cleanFixtures.reduce((t, r) => t + r.expectedRows, 0);
record(
  "清晰打印体行匹配率 ≥ 90%",
  cleanRowRecall / Math.max(1, cleanRowTotal) >= 0.9,
  `${cleanRowRecall}/${cleanRowTotal}`,
);

const confusable = accuracyReport.find((r) => r.fixture === "confusable-id");
record(
  "学号混淆（0→O）能被纠正并匹配",
  Boolean(confusable) && confusable.matchedRows === confusable.expectedRows,
  confusable ? `匹配 ${confusable.matchedRows}/${confusable.expectedRows}` : "缺少用例",
);

const unknown = accuracyReport.find((r) => r.fixture === "dirty-unknown-student");
record(
  "名册外学号被标记为未匹配且不中断整批",
  Boolean(unknown) && unknown.rows === 3,
  unknown ? `共 ${unknown.rows} 行，其中 ${unknown.matchedRows} 行匹配到名册` : "缺少用例",
);

const blank = accuracyReport.find((r) => r.fixture === "dirty-blank-row");
record(
  "整行无分数的行有明确问题提示",
  Boolean(blank) && blank.matchedRows >= 2,
  blank ? `匹配 ${blank.matchedRows} 行` : "缺少用例",
);

const skewed = accuracyReport.find((r) => r.fixture === "skew-2deg");
record(
  "倾斜 2° 的图片仍能识别出行",
  Boolean(skewed) && skewed.matchedRows >= skewed.expectedRows - 1,
  skewed ? `匹配 ${skewed.matchedRows}/${skewed.expectedRows}` : "缺少用例",
);

const noisy = accuracyReport.find((r) => r.fixture === "shadow-noise");
record(
  "阴影 + 噪点图片仍能识别出行",
  Boolean(noisy) && noisy.matchedRows >= Math.max(1, noisy.expectedRows - 2),
  noisy ? `匹配 ${noisy.matchedRows}/${noisy.expectedRows}` : "缺少用例",
);

/** 渲染一张成绩单 PNG（用当前课程真实学生学号，保证与页面名册一致）。 */
async function renderSheet(rows, header = ["ID", "REG", "LAB", "FIN"]) {
  const html = `<!doctype html><html><head><meta charset="utf-8"><style>
    body { margin:0; background:#fff; font-family:"Times New Roman", monospace; padding:28px 34px; }
    table { border-collapse:collapse; font-size:30px; letter-spacing:1.5px; }
    th,td { padding:14px 26px; border:1px solid #9a9a9a; }
    td.id { font-weight:700; letter-spacing:2.5px; }
    td.score { text-align:right; min-width:110px; }
  </style></head><body><table>
    <thead><tr>${header.map((text) => `<th>${text}</th>`).join("")}</tr></thead>
    <tbody>${rows
      .map(
        (row) =>
          `<tr><td class="id">${row[0]}</td>${row
            .slice(1)
            .map((value) => `<td class="score">${value === "" || value === null ? "&nbsp;" : value}</td>`)
            .join("")}</tr>`,
      )
      .join("")}</tbody>
  </table></body></html>`;
  const sheetPage = await context.newPage();
  await sheetPage.setContent(html);
  const png = await sheetPage.locator("table").screenshot();
  await sheetPage.close();
  return png;
}

/* ------------------------------------------- [3] 预览确认：未确认不入表单 */

console.log("\n[3] 预览确认：未确认不入表单");
await page.getByTestId("ocr-open").click();
// 从当前课程的录入表单里挑一个真实存在的学生作为断言语料（不写死学号）
const scored = await page.evaluate(() =>
  [...document.querySelectorAll(".grade-table input[aria-label]")]
    .map((input) => input.getAttribute("aria-label"))
    .filter((label) => / 平时$/.test(label))
    .map((label) => label.replace(" 平时", "")),
);
if (!scored.length) {
  record("当前课程存在可录入的学生", false, "成绩表里没有「平时」列");
  await browser.close();
  process.exit(1);
}
const sample = scored[0];
const sampleRegular = page.getByLabel(`${sample} 平时`, { exact: true });
const sampleFinal = page.getByLabel(`${sample} 期末`, { exact: true });
record("取到断言语料", true, `${sample}（共 ${scored.length} 名学生）`);

const beforeValue = await sampleRegular.inputValue().catch(() => null);

// 用**当前课程真实学生**生成一张成绩单并渲染成 PNG，再走真实交互路径。
// 好处：断言语料与页面名册天然一致，也不会因为演示数据变化而失效。
const rowsForSheet = scored.slice(0, 3).map((username, index) => [username, 85 - index * 5, 92 - index * 3, 87 - index]);
const sheetPng = await renderSheet(rowsForSheet);

await page.getByTestId("ocr-file").setInputFiles({
  name: "current-course.png",
  mimeType: "image/png",
  buffer: sheetPng,
});
await page.getByTestId("recognize-preview").waitFor({ timeout: 180000 });
await page.screenshot({ path: path.join(root, "test-results", "browser", "ocr-preview.png"), fullPage: false });

const afterRecognize = await sampleRegular.inputValue().catch(() => null);
record("识别后表单尚未变化（需人工确认）", beforeValue === afterRecognize, `${beforeValue} → ${afterRecognize}`);

const previewInfo = await page.evaluate(() => ({
  usable: Number(document.querySelector("[data-testid='usable-count']")?.textContent || "0"),
  filled: Number(document.querySelector("[data-testid='filled-count']")?.textContent || "0"),
  suspicious: Number(document.querySelector("[data-testid='suspicious-count']")?.textContent || "0"),
  rows: document.querySelectorAll("[data-testid^='preview-row-']").length,
}));
record("预览表渲染出识别行", previewInfo.rows > 0, JSON.stringify(previewInfo));
record("预览统计显示可填入行数", previewInfo.usable > 0, `usable=${previewInfo.usable}`);
record("预览统计显示分数个数", previewInfo.filled > 0, `filled=${previewInfo.filled}`);

/* --------------------------------------------- [4] 确认填入 + 坏行不阻塞 */

console.log("\n[4] 确认填入：好行写入、坏行跳过");
await page.getByTestId("confirm-fill").click();
await page.getByText(/已填入 \d+ 人成绩/).waitFor({ timeout: 15000 });
const filledNotice = await page.getByText(/已填入 \d+ 人成绩/).textContent();
record("确认后出现「已填入」提示", /已填入 \d+ 人成绩/.test(filledNotice), filledNotice.trim());

const filledValue = await sampleRegular.inputValue();
const filledNoticeText = (await page.getByText(/已填入 \d+ 人成绩/).textContent()).trim();
record(
  "确认后分数写入录入表单",
  filledValue.length > 0 && filledValue !== String(beforeValue ?? ""),
  `${sample} 平时 ${beforeValue} → ${filledValue}（${filledNoticeText}）`,
);
const finalValue = await sampleFinal.inputValue();
record("同一行其余列也写入", finalValue.length > 0, `${sample} 期末=${finalValue}`);

/* ------------------------------------------------ [5] 坏行数据的界面行为 */

console.log("\n[5] 坏行处理：脏数据图只填入可用行");
await page.getByTestId("ocr-open").click();
const dirtyRows = [
  [scored[0], 85, 92, 87],
  ["99999999", 66, 66, 66],
  [scored[1] || scored[0], 120, 62, 57],
];
const dirtyPng = await renderSheet(dirtyRows);
await page.getByTestId("ocr-file").setInputFiles({
  name: "dirty.png",
  mimeType: "image/png",
  buffer: dirtyPng,
});
await page.getByTestId("recognize-preview").waitFor({ timeout: 180000 });
const dirtyInfo = await page.evaluate(() => {
  const rows = [...document.querySelectorAll("[data-testid^='preview-row-']")];
  return {
    rows: rows.length,
    matchedUsernames: rows
      .map((row) => row.querySelector("[data-testid='preview-username']")?.textContent?.trim())
      .filter((text) => /^\d{8}$/.test(text)),
    skipped: rows.filter((row) => row.className.includes("row-skip")).length,
    issues: [...document.querySelectorAll(".recognize-issues .warn")].map((el) => el.textContent.trim()),
  };
});
record(
  "脏数据图里名册内学号被识别出来",
  dirtyInfo.matchedUsernames.length >= 1,
  `识别到 ${dirtyInfo.matchedUsernames.join(",")}`,
);
record("未匹配行默认不勾选（跳过）", dirtyInfo.skipped >= 1, `skipped=${dirtyInfo.skipped}/${dirtyInfo.rows}`);
record(
  "界面给出未匹配/异常原因",
  dirtyInfo.issues.some((text) => text.includes("未匹配") || text.includes("超出")),
  JSON.stringify(dirtyInfo.issues.slice(0, 4)),
);
await page.getByTestId("confirm-fill").click();
await page.getByText(/已填入 \d+ 人成绩/).waitFor({ timeout: 15000 });
record("坏行不影响其余行填入", true, await page.getByText(/已填入 \d+ 人成绩/).textContent());

/* ------------------------------------------------------- [6] 语音录入 */

console.log("\n[6] 语音录入：解析、填入与越界拒绝");
/** 直接从录入表单取某个学生某个成绩项的值（成绩项名从表头与 aria-label 推导）。 */
async function fillValueOf(username) {
  return page.evaluate((name) => {
    const inputs = [...document.querySelectorAll(".grade-table input[aria-label]")];
    const mine = inputs
      .filter((input) => input.getAttribute("aria-label").startsWith(name + " "))
      .map((input) => ({ label: input.getAttribute("aria-label").slice(name.length + 1), value: input.value }));
    const final = mine.find((cell) => cell.label === "期末") || mine[mine.length - 1];
    return final ? final.value : "";
  }, username);
}
async function labelOf(username) {
  return page.evaluate((name) => {
    const inputs = [...document.querySelectorAll(".grade-table input[aria-label]")];
    const mine = inputs
      .filter((input) => input.getAttribute("aria-label").startsWith(name + " "))
      .map((input) => input.getAttribute("aria-label").slice(name.length + 1));
    return mine.find((label) => label === "期末") || mine[mine.length - 1] || "";
  }, username);
}
// 选一个尚未录入成绩的学生，保证"填入"是可见的状态变化
const emptyStudent = await page.evaluate(() => {
  const inputs = [...document.querySelectorAll(".grade-table input[aria-label]")];
  const byStudent = new Map();
  for (const input of inputs) {
    const [username, label] = input.getAttribute("aria-label").split(" ");
    if (!byStudent.has(username)) byStudent.set(username, []);
    byStudent.get(username).push({ label, value: input.value });
  }
  for (const [username, cells] of byStudent)
    if (cells.every((cell) => cell.value === "")) return username;
  return byStudent.keys().next().value;
});
await page.getByTestId(`voice-row-${emptyStudent}`).click();
await page.getByTestId("voice-panel").waitFor({ timeout: 10000 });
const voiceTargetText = await page.getByTestId("voice-target").textContent();
record(
  "行内麦克风把语音面板切到该学生",
  voiceTargetText.includes(emptyStudent),
  voiceTargetText.trim(),
);

// 先用"裸数字"验证按列顺序补位（该学生本来就有成绩，所以裸数字会明确提示"没有空位"）
await page.getByTestId("voice-text").fill("90 88 76");
await page.waitForTimeout(200);
const bareDebug = await page.evaluate(() => ({
  textarea: document.querySelector("[data-testid='voice-text']")?.value,
  updates: window.__voiceState?.updates?.map((u) => `${u.key}=${u.value}`),
  unmatched: window.__voiceState?.parsed?.unmatched,
  hint: document.querySelector("[data-testid='voice-unmatched']")?.textContent?.trim() || "",
}));
record(
  "裸数字在无空位时给出明确提示而不是静默丢弃",
  bareDebug.unmatched?.length === 3 && /没有可用空位/.test(bareDebug.hint),
  JSON.stringify(bareDebug),
);

await page.getByTestId("voice-text").fill("平时八十五 实验九十二 期末八十七");
await page.waitForTimeout(200);
const parsedValues = await page.getByTestId("voice-value").allTextContents();
record(
  "中文口述解析为三项分数",
  parsedValues.join(",") === "85,92,87",
  JSON.stringify(parsedValues),
);

await page.getByTestId("voice-text").fill("平时一百二");
await page.waitForTimeout(200);
const rejectedText = await page.getByTestId("voice-rejected").textContent().catch(() => "");
record("超界分数被拒绝并提示", /超出 0–100/.test(rejectedText), rejectedText.trim());

await page.getByTestId("voice-text").fill("期末九十九");
await page.waitForTimeout(200);
const finalOnly = await page.getByTestId("voice-value").allTextContents();
record("只提到一项时其余列不动", finalOnly.join(",") === "99", JSON.stringify(finalOnly));

await page.getByTestId("voice-apply").click();
await page.getByTestId("voice-applied").waitFor({ timeout: 10000 });
const appliedNotice = await page.getByTestId("voice-applied").textContent();
record("语音结果写入当前行", /已填入/.test(appliedNotice), appliedNotice.trim());
const lastLabel = await page.evaluate(() => {
  const labels = [...document.querySelectorAll(".grade-table thead th")].map((th) => th.textContent.trim());
  return labels.includes("期末") ? "期末" : labels.filter(Boolean).pop();
});
const voiceFilled = await fillValueOf(emptyStudent);
const voiceLabel = await labelOf(emptyStudent);
record("填入后表单拿到语音解析值", voiceFilled === "99", `${emptyStudent} ${voiceLabel}=${voiceFilled}`);
await page.getByRole("button", { name: "关闭" }).first().click();

/* ------------------------------------ [7] SpeechRecognition 真实接入路径 */

console.log("\n[7] 语音识别接入：SpeechRecognition 桩驱动");
await page.getByTestId("voice-open").click();
await page.getByTestId("voice-panel").waitFor({ timeout: 10000 });
await page.getByTestId("voice-mic").click();
await page.waitForTimeout(300);
const fakeStarted = await page.evaluate(() => Boolean(window.__fakeSpeech?.started));
record("点击「开始识别」调用了 SpeechRecognition.start", fakeStarted);
await page.evaluate(() => window.__fakeSpeech?.emit("平时七十七"));
await page.waitForTimeout(300);
const micValues = await page.getByTestId("voice-value").allTextContents();
record("识别回调文本进入解析", micValues.join(",") === "77", JSON.stringify(micValues));

// 切换录入对象：下拉框、上一行/下一行按钮、以及"下一行"语音口令
const targetCount = await page.evaluate(() => window.__voiceState?.rowCount ?? 0);
const firstTarget = await page.evaluate(() => window.__voiceState?.target ?? "");
await page.getByTestId("voice-next").click();
await page.waitForTimeout(200);
const afterNext = await page.evaluate(() => window.__voiceState?.target ?? "");
record("「下一行」按钮切换录入对象", afterNext !== "" && afterNext !== firstTarget, `${firstTarget} → ${afterNext}`);
await page.getByTestId("voice-prev").click();
await page.waitForTimeout(200);
const afterPrev = await page.evaluate(() => window.__voiceState?.target ?? "");
record("「上一行」按钮切回原对象", afterPrev === firstTarget, `${afterNext} → ${afterPrev}`);

const selectOptions = await page.locator("[data-testid='voice-target-select'] option").allTextContents();
record(
  "下拉框列出全部可录入学生",
  selectOptions.length === targetCount && targetCount > 1,
  `options=${selectOptions.length} rows=${targetCount}`,
);
const thirdValue = await page.locator("[data-testid='voice-target-select'] option").nth(2).getAttribute("value");
await page.getByTestId("voice-target-select").selectOption(thirdValue);
await page.waitForTimeout(200);
const afterSelect = await page.evaluate(() => window.__voiceState?.target ?? "");
record("下拉框可直接选中某名学生", afterSelect === thirdValue, `${thirdValue} → ${afterSelect}`);

await page.getByTestId("voice-mic").click();
await page.getByRole("button", { name: "关闭" }).first().click();

/* ---------------------------------------------------------- [8] 隐私 */

console.log("\n[8] 隐私与页面健康");
record("整轮识别没有任何图片上行请求", imageUploads.length === 0, imageUploads.join(", "));
const fatalConsole = consoleErrors.filter(
  (text) => !/favicon|Failed to load resource/i.test(text),
);
record("无致命控制台错误", fatalConsole.length === 0, fatalConsole.slice(0, 3).join(" | "));

/* -------------------------------------------------------------- 输出 */

const logDir = path.join(root, ".runtime", "logs");
fs.mkdirSync(logDir, { recursive: true });
const payload = {
  generatedAt: new Date().toISOString(),
  base: BASE,
  teacher: TEACHER,
  course: targetOption.trim(),
  total: results.length,
  passed: results.length - failures,
  failed: failures,
  accuracy: accuracyReport,
  results,
};
fs.writeFileSync(path.join(logDir, "ocr-voice-check.json"), JSON.stringify(payload, null, 2) + "\n");

console.log(`\n合计 ${payload.total} 项：通过 ${payload.passed}，失败 ${payload.failed}`);
console.log("字段级准确率（清晰图）：" + (cleanAccuracy * 100).toFixed(1) + "%");
console.log("证据：.runtime/logs/ocr-voice-check.json");

await browser.close();
process.exit(failures ? 1 : 0);
