#!/usr/bin/env node
/**
 * 生成 OCR 固定测试图集（PNG）。
 *
 * 做法：用本机 Chromium 内核浏览器（Edge / Chrome）以**系统真实字体**渲染成绩单，
 * 再截图保存。这样图集的字形与真实打印件一致（等宽/无衬线、抗锯齿），
 * Tesseract 的识别表现才有参考价值——像素点阵字体的人为误差不会混进测试结论。
 *
 * 用法：
 *   node scripts/generate-ocr-fixtures.mjs            # 已存在则跳过
 *   node scripts/generate-ocr-fixtures.mjs --force    # 强制重新生成
 *
 * 输出：test-results/ocr/*.png 与 manifest.json
 * 依赖：本机 Edge（默认路径）或 BROWSER_EXECUTABLE 指定的 Chromium 内核浏览器
 */
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const outDir = path.join(root, "test-results", "ocr");
const force = process.argv.includes("--force");

const playwrightEntry = path.join(root, "frontend", "node_modules", "playwright", "index.mjs");
if (!fs.existsSync(playwrightEntry)) {
  console.error(`找不到 playwright：${playwrightEntry}（先执行 npm --prefix frontend install）`);
  process.exit(1);
}
const { chromium } = await import(pathToFileURL(playwrightEntry).href);

const EXECUTABLE =
  process.env.BROWSER_EXECUTABLE ||
  "C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe";

/* ------------------------------------------------------------ 图集定义 */

/**
 * 每个 fixture 描述一张成绩单。
 * header 用英文缩写，避免依赖中文字体；真实场景的中文表头由系统自动表头文字识别兜底，
 * 但成绩单的“数字定位”不依赖表头文字，这也是本方案能不用中文语言包的原因。
 */
const fixtures = [
  {
    name: "clean-3col",
    note: "正常打印体：学号 + 平时/实验/期末",
    header: ["ID", "REG", "LAB", "FIN"],
    grid: true,
    rows: [
      ["20231530", 85, 92, 87],
      ["20231531", 70, 78, 74],
      ["20231532", 55, 62, 57],
      ["20231533", 95, 94, 97],
    ],
  },
  {
    name: "clean-2col",
    note: "正常打印体：学号 + 平时/期末（两项权重的课程）",
    header: ["ID", "REG", "FIN"],
    rows: [
      ["20231530", 85, 87],
      ["20231531", 70, 74],
      ["20231532", 55, 57],
    ],
  },
  {
    name: "clean-6col",
    note: "正常打印体：学号 + 六项系数全开",
    header: ["ID", "REG", "ATT", "HW", "LAB", "MID", "FIN"],
    grid: true,
    rows: [
      ["20231530", 85, 90, 88, 92, 80, 87],
      ["20231531", 70, 75, 72, 78, 68, 74],
    ],
  },
  {
    name: "skew-2deg",
    note: "倾斜 2°：验证按行锚定（不依赖严格水平）",
    header: ["ID", "REG", "LAB", "FIN"],
    grid: true,
    css: "transform: rotate(2deg); transform-origin: top left;",
    rows: [
      ["20231530", 85, 92, 87],
      ["20231531", 70, 78, 74],
      ["20231532", 55, 62, 57],
      ["20231533", 95, 94, 97],
    ],
  },
  {
    name: "skew-4deg",
    note: "倾斜 4°：验证倾斜估计与校正能覆盖较大角度",
    header: ["ID", "REG", "LAB", "FIN"],
    grid: true,
    css: "transform: rotate(4deg); transform-origin: top left;",
    rows: [
      ["20231530", 85, 92, 87],
      ["20231531", 70, 78, 74],
      ["20231532", 55, 62, 57],
      ["20231533", 95, 94, 97],
    ],
  },
  {
    name: "shadow-noise",
    note: "阴影 + 噪点：验证多策略预处理择优",
    header: ["ID", "REG", "LAB", "FIN"],
    grid: true,
    css: "background: linear-gradient(100deg, #6b6b6b 0%, #ffffff 55%);",
    noise: 900,
    rows: [
      ["20231530", 85, 92, 87],
      ["20231531", 70, 78, 74],
      ["20231532", 55, 62, 57],
      ["20231533", 95, 94, 97],
    ],
  },
  {
    name: "confusable-id",
    note: "学号 OCR 混淆：第 3 行学号末位印成字母 O，验证模糊纠正",
    header: ["ID", "REG", "LAB", "FIN"],
    grid: true,
    rows: [
      ["20231530", 85, 92, 87],
      ["20231531", 70, 78, 74],
      ["2023153O", 55, 62, 57],
    ],
  },
  {
    name: "dirty-missing-and-range",
    note: "脏数据：第 2 行缺实验分、第 3 行平时分超界 120",
    header: ["ID", "REG", "LAB", "FIN"],
    grid: true,
    rows: [
      ["20231530", 85, 92, 87],
      ["20231531", 70, "", 74],
      ["20231532", 120, 62, 57],
    ],
  },
  {
    name: "dirty-unknown-student",
    note: "名册外学号：整行标记“未匹配”，不抛错终止",
    header: ["ID", "REG", "LAB", "FIN"],
    grid: true,
    rows: [
      ["20231530", 85, 92, 87],
      ["99999999", 66, 66, 66],
      ["20231531", 70, 78, 74],
    ],
  },
  {
    name: "dirty-blank-row",
    note: "名册内学号但整行无分数：标记“未识别到分数”",
    header: ["ID", "REG", "LAB", "FIN"],
    grid: true,
    rows: [
      ["20231530", 85, 92, 87],
      ["20231531", "", "", ""],
      ["20231532", 55, 62, 57],
    ],
  },
];

/* --------------------------------------------------------------- 渲染 */

function sheetHtml(fixture) {
  const cells = (row, isHeader) =>
    row
      .map((value, index) => {
        const tag = isHeader ? "th" : "td";
        const text = value === "" || value === null ? "&nbsp;" : String(value);
        return `<${tag} class="${index === 0 ? "id" : "score"}">${text}</${tag}>`;
      })
      .join("");

  const body = fixture.rows.map((row) => `<tr>${cells(row, false)}</tr>`).join("");
  const head = `<tr>${cells(fixture.header, true)}</tr>`;

  return `<!doctype html><html><head><meta charset="utf-8"><style>
    * { margin: 0; padding: 0; box-sizing: border-box; }
    body { background: #fff; font-family: "Times New Roman", "Consolas", monospace;
           padding: 28px 34px; ${fixture.css || ""} }
    table { border-collapse: collapse; font-size: 30px; letter-spacing: 1.5px; }
    th, td { padding: 14px 26px; ${fixture.grid ? "border: 1px solid #9a9a9a;" : ""} }
    th { font-weight: 700; font-size: 26px; color: #333; }
    td.id { font-weight: 700; letter-spacing: 2.5px; }
    td.score { text-align: right; min-width: 110px; }
  </style></head><body><table><thead>${head}</thead><tbody>${body}</tbody></table>
  ${fixture.noise ? `<script>
    const c = document.createElement('canvas');
    c.width = innerWidth; c.height = innerHeight;
    const ctx = c.getContext('2d');
    let s = 20261005;
    const rand = () => ((s = (s * 1664525 + 1013904223) >>> 0) / 4294967296);
    for (let i = 0; i < ${fixture.noise}; i++) {
      const v = Math.floor(rand() * 120);
      ctx.fillStyle = 'rgba(' + v + ',' + v + ',' + v + ',0.55)';
      ctx.fillRect(rand() * c.width, rand() * c.height, 1 + rand() * 2, 1 + rand() * 2);
    }
    document.body.appendChild(c);
    c.style.position = 'absolute';
    c.style.left = '0'; c.style.top = '0'; c.style.pointerEvents = 'none';
  </script>` : ""}
  </body></html>`;
}

/* --------------------------------------------------------------- 主流程 */

fs.mkdirSync(outDir, { recursive: true });
const manifestPath = path.join(outDir, "manifest.json");
const existing = fixtures.map((f) => path.join(outDir, `${f.name}.png`));
if (!force && existing.every((file) => fs.existsSync(file)) && fs.existsSync(manifestPath)) {
  console.log("测试图集已存在，跳过（需要重建请加 --force）");
  process.exit(0);
}

const browser = await chromium.launch({ executablePath: EXECUTABLE });
const page = await browser.newPage({ viewport: { width: 1400, height: 900 }, deviceScaleFactor: 2 });

const manifest = [];
for (const fixture of fixtures) {
  await page.setContent(sheetHtml(fixture));
  const table = page.locator("table");
  const file = path.join(outDir, `${fixture.name}.png`);
  await table.screenshot({ path: file });
  const box = await table.boundingBox();
  manifest.push({
    name: fixture.name,
    note: fixture.note,
    header: fixture.header,
    rows: fixture.rows,
    width: Math.round(box.width * 2),
    height: Math.round(box.height * 2),
  });
  console.log(`  ${fixture.name}.png  ${Math.round(box.width * 2)}x${Math.round(box.height * 2)}  ${fixture.note}`);
}
await browser.close();

fs.writeFileSync(
  manifestPath,
  JSON.stringify({ generatedAt: new Date().toISOString(), browser: EXECUTABLE, fixtures: manifest }, null, 2) + "\n",
);
console.log(`已生成 ${manifest.length} 张测试图到 ${path.relative(root, outDir)}`);
