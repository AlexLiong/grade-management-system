#!/usr/bin/env node
/**
 * OCR / 语音模块的纯函数单元测试（不依赖浏览器与后端）。
 *
 * 用法：node scripts/ocr-voice-unit.mjs
 * 结果：控制台输出 + .runtime/logs/ocr-voice-unit.json
 *
 * 覆盖范围：
 *   - 中文数字与混合口语解析（voice.js）
 *   - 口述到成绩项的归属、越界拒绝、裸数字按序补位（voice.js）
 *   - 学号混淆纠正与编辑距离（ocr.js）
 *   - 行带切分、列锚点推导与列分配（ocr.js）
 *   - 结构化结果的可用性判定与统计（ocr.js）
 */
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import * as voice from "../frontend/src/voice.js";
import * as ocr from "../frontend/src/ocr.js";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const results = [];
let failures = 0;

function check(name, condition, detail = "") {
  const ok = Boolean(condition);
  results.push({ name, ok, detail: detail || "" });
  console.log(`  [${ok ? "PASS" : "FAIL"}] ${name}${ok || !detail ? "" : "  — " + detail}`);
  if (!ok) failures++;
}

function equal(name, actual, expected) {
  const ok = JSON.stringify(actual) === JSON.stringify(expected);
  check(name, ok, ok ? "" : `期望 ${JSON.stringify(expected)}，实际 ${JSON.stringify(actual)}`);
}

/* ------------------------------------------------------------ 中文数字 */

console.log("\n[1] 中文数字解析");
equal("八十五 → 85", voice.chineseToNumber("八十五"), 85);
equal("一百 → 100", voice.chineseToNumber("一百"), 100);
equal("十五 → 15", voice.chineseToNumber("十五"), 15);
equal("十 → 10", voice.chineseToNumber("十"), 10);
equal("三 → 3", voice.chineseToNumber("三"), 3);
equal("零 → 0", voice.chineseToNumber("零"), 0);
equal("八十七 → 87", voice.chineseToNumber("八十七"), 87);
equal("无法解析的文本 → null", voice.chineseToNumber("优秀"), null);
equal("toNumber 阿拉伯数字", voice.toNumber("87"), 87);
equal("toNumber 小数", voice.toNumber("85.5"), 85.5);
equal("toNumber 中文小数", voice.toNumber("八十五点五"), 85.5);
equal("toNumber 混合写法", voice.toNumber("85点5"), 85.5);

/* ----------------------------------------------------------- 口述解析 */

const COMPONENTS = voice.buildVoiceComponents([
  ["regular", "平时"],
  ["lab", "实验"],
  ["finalExam", "期末"],
]);

console.log("\n[2] 口述解析");
{
  const parsed = voice.parseUtterance("平时八十五 实验九十二 期末八十七", COMPONENTS);
  equal("带名称口述 → 三项", [parsed.values.regular, parsed.values.lab, parsed.values.finalExam], [85, 92, 87]);
  equal("mentioned 顺序", parsed.mentioned, ["regular", "lab", "finalExam"]);
  equal("无 rejected", parsed.rejected.length, 0);
}
{
  const parsed = voice.parseUtterance("平时 85 分，实验 92，期末 87 分", COMPONENTS);
  equal("口语夹杂“分”", [parsed.values.regular, parsed.values.lab, parsed.values.finalExam], [85, 92, 87]);
}
{
  const parsed = voice.parseUtterance("八十五 九十二 八十七", COMPONENTS);
  equal("裸数字按列顺序补位", [parsed.values.regular, parsed.values.lab, parsed.values.finalExam], [85, 92, 87]);
  equal("裸数字全部被消化", parsed.unmatched.length, 0);
}
{
  const parsed = voice.parseUtterance("90 88 76", COMPONENTS, { defaults: { regular: 70, lab: 80, finalExam: 60 } });
  equal("已有分数时裸数字不覆盖", [parsed.values.regular, parsed.values.lab, parsed.values.finalExam], [70, 80, 60]);
  equal("未使用的数字被登记", parsed.unmatched, [90, 88, 76]);
  check("未使用的数字给出提示", parsed.unknown.some((t) => t.includes("未使用")), JSON.stringify(parsed.unknown));
}
{
  const parsed = voice.parseUtterance("90 88 76", COMPONENTS, { defaults: { regular: 70, lab: null, finalExam: null } });
  equal("裸数字只补空位", [parsed.values.regular, parsed.values.lab, parsed.values.finalExam], [70, 90, 88]);
  equal("剩下的数字登记为未使用", parsed.unmatched, [76]);
}
{
  const parsed = voice.parseUtterance("期末九十九", COMPONENTS, { defaults: { regular: 70, lab: 80, finalExam: 60 } });
  equal("具名口述可覆盖已有分数", parsed.values.finalExam, 99);
  equal("未提到的列保持原值", [parsed.values.regular, parsed.values.lab], [70, 80]);
}
{
  const parsed = voice.parseUtterance("实验九十二", COMPONENTS);
  equal("只提一项时其余保持为空", [parsed.values.regular, parsed.values.lab, parsed.values.finalExam], [null, 92, null]);
  equal("只提到 lab", parsed.mentioned, ["lab"]);
}
{
  const parsed = voice.parseUtterance("平时一百二", COMPONENTS);
  equal("超界值被拒绝", parsed.values.regular, null);
  check("rejected 有记录", parsed.rejected.length === 1, JSON.stringify(parsed.rejected));
}
{
  const parsed = voice.parseUtterance("平时八十五", COMPONENTS, { defaults: { lab: 90, finalExam: null, regular: null } });
  equal("未提到的项保留原值", [parsed.values.regular, parsed.values.lab, parsed.values.finalExam], [85, 90, null]);
}
{
  const parsed = voice.parseUtterance("出勤九十分", voice.buildVoiceComponents([["attendance", "考勤"]]));
  equal("口语别名“出勤”→“考勤”", parsed.values.attendance, 90);
}
{
  const parsed = voice.parseUtterance("平时优", COMPONENTS);
  equal("无法解析的值不写入", parsed.values.regular, null);
  check("unknown 记录了无法解析的词", parsed.unknown.includes("优"), JSON.stringify(parsed.unknown));
}
{
  const cells = voice.toCellUpdates(
    voice.parseUtterance("平时八十五 期末八十七", COMPONENTS),
    COMPONENTS,
  );
  equal("toCellUpdates 只回传被提到的项", cells.map((c) => c.key), ["regular", "finalExam"]);
}

/* ------------------------------------------------------- OCR 纯函数 */

console.log("\n[3] 学号纠错");
equal("editDistance 相同 → 0", ocr.editDistance("20231530", "20231530"), 0);
equal("editDistance 一位差 → 1", ocr.editDistance("2023153O", "20231530"), 1);
equal("editDistance 差距过大 → 提前退出", ocr.editDistance("12345678", "20231530", 2), 3);
equal("fixConfusion O→0", ocr.fixConfusion("2O23153O"), "20231530");
equal("fixConfusion l→1/B→8/S→5", ocr.fixConfusion("lB S"), "185");
{
  const roster = ["20231530", "20231531", "20231532", "20241530", "20241531"];
  const exact = ocr.correctId("20231530", roster);
  equal("完全一致：corrected=false", exact.corrected, false);
  equal("完全一致：命中", exact.username, "20231530");
  const fuzzy = ocr.correctId("2O23153O", roster);
  equal("混淆学号被纠正", fuzzy.username, "20231530");
  equal("混淆学号 corrected=true", fuzzy.corrected, true);
  const miss = ocr.correctId("99999999", roster, { maxDistance: 1 });
  equal("名册外学号返回 null", miss, null);
  // 关键边界：编辑距离同样为 1 的"同前缀另一个学号"不能抢走精确匹配
  equal(
    "精确匹配优先于编辑距离为 1 的其它学号",
    ocr.correctId("20231530", roster, { maxDistance: 1 }).username,
    "20231530",
  );
  equal(
    "一位数字不同且名册里都有时，命名到距离 1 的那个",
    ocr.correctId("2023153O", ["20231530", "20241530"], { maxDistance: 1 }).username,
    "20231530",
  );
  const tie = ocr.correctId("2O23153O", ["20231530", "20241530"], { maxDistance: 2 });
  equal("多候选同距离时优先混淆能解释的", tie.username, "20231530");
  const digitTie = ocr.correctId("2023153O", ["20231530", "20241530"], { maxDistance: 1 });
  equal("同距离且解释力相同时取先出现的候选", digitTie.username, "20231530");
  // 数字位被识别成字母：'5' → 'S' 再被读成 '9'，只能靠一位模糊匹配救回来
  const digitDrift = ocr.correctId("2023159O", ["20231560", "20241560"], { maxDistance: 2 });
  equal("数字位漂移 + 字母混淆可被纠正", digitDrift.username, "20231560");
  // 安全边界：只接受"能被已知混淆解释"的差异；纯数字位差异不自动填人
  const unverified = ocr.correctId("20231539", ["20231530", "20241530"], { maxDistance: 1 });
  equal("数字位差异不被自动认人", unverified.username, null);
  equal("数字位差异标记为待人工确认", unverified.unverified, true);
  const structure = ocr.structureRows({
    rows: [
      {
        text: "20231539 85 92 87",
        confidence: 90,
        words: [
          { text: "20231539", bbox: { x0: 40, x1: 200, y0: 0, y1: 30 }, confidence: 90 },
          { text: "85", bbox: { x0: 430, x1: 480, y0: 0, y1: 30 }, confidence: 95 },
          { text: "92", bbox: { x0: 640, x1: 690, y0: 0, y1: 30 }, confidence: 95 },
          { text: "87", bbox: { x0: 850, x1: 900, y0: 0, y1: 30 }, confidence: 95 },
        ],
      },
    ],
    components: [
      { key: "regular", label: "平时" },
      { key: "lab", label: "实验" },
      { key: "finalExam", label: "期末" },
    ],
    roster: [
      { id: "s1", username: "20231530", name: "甲" },
      { id: "s2", username: "20241530", name: "乙" },
    ],
  });
  equal("无法确认的学号不写入任何学生", structure[0].matched, false);
  check(
    "无法确认的学号给出人工核对提示",
    structure[0].issues.some((i) => i.includes("人工核对")),
    JSON.stringify(structure[0].issues),
  );
}

console.log("\n[4] 版面切分与列分配");
{
  // 构造 3 行、每行高 20px 的二值图（0=文字）
  const width = 100;
  const height = 100;
  const binary = new Uint8ClampedArray(width * height).fill(255);
  for (const top of [10, 40, 70]) {
    for (let y = top; y < top + 10; y++)
      for (let x = 5; x < 60; x++) binary[y * width + x] = 0;
  }
  const bands = ocr.segmentBands(binary, width, height, { minHeight: 5 });
  equal("切出 3 个行带", bands.length, 3);
  check("行带顺序自上而下", bands[0].top < bands[1].top && bands[1].top < bands[2].top, JSON.stringify(bands));
}
{
  const tokens = [
    { text: "20231530", numeric: null, confidence: 96, x0: 40, x1: 200 },
    { text: "85", numeric: 85, confidence: 95, x0: 430, x1: 480 },
    { text: "92", numeric: 92, confidence: 94, x0: 640, x1: 690 },
    { text: "87", numeric: 87, confidence: 93, x0: 850, x1: 900 },
  ];
  const cells = ocr.assignColumns(tokens, [480, 690, 900]);
  equal("列分配命中三列", cells.map((c) => (c ? c.numeric : null)), [85, 92, 87]);
}
{
  const tokens = [
    { text: "20231531", numeric: null, confidence: 90, x0: 40, x1: 200 },
    { text: "70", numeric: 70, confidence: 95, x0: 430, x1: 480 },
    { text: "74", numeric: 74, confidence: 95, x0: 850, x1: 900 },
  ];
  const cells = ocr.assignColumns(tokens, [480, 690, 900]);
  equal("缺中间列时留空而不是前移", cells.map((c) => (c ? c.numeric : null)), [70, null, 74]);
}
{
  // 个位残片：OCR 把一个两位数拆开、只剩个位落在前一列右缘附近时，不能吸到前一列造成整行错位
  const tokens = [
    { text: "0", numeric: 0, confidence: 40, x0: 530, x1: 555 },
    { text: "78", numeric: 78, confidence: 90, x0: 645, x1: 690 },
    { text: "74", numeric: 74, confidence: 90, x0: 850, x1: 900 },
  ];
  const cells = ocr.assignColumns(tokens, [522, 690, 900]);
  equal("个位残片不占用前一列", cells.map((c) => (c ? c.numeric : null)), [null, 78, 74]);
}
{
  // 右对齐：一位数「7」的右缘与两位数「70」的右缘接近，应落到同一列
  const tokens = [
    { text: "7", numeric: 7, confidence: 80, x0: 460, x1: 480 },
    { text: "70", numeric: 70, confidence: 92, x0: 640, x1: 690 },
  ];
  const cells = ocr.assignColumns(tokens, [480, 690, 900]);
  equal("右对齐的一位分数落在正确列", cells.map((c) => (c ? c.numeric : null)), [7, 70, null]);
}
{
  const rows = [
    {
      words: [
        { text: "20231530", bbox: { x0: 40, x1: 200, y0: 10, y1: 40 }, confidence: 96 },
        { text: "85", bbox: { x0: 430, x1: 480, y0: 10, y1: 40 }, confidence: 95 },
        { text: "92", bbox: { x0: 640, x1: 690, y0: 10, y1: 40 }, confidence: 94 },
        { text: "87", bbox: { x0: 850, x1: 900, y0: 10, y1: 40 }, confidence: 93 },
      ],
    },
    {
      words: [
        { text: "20231531", bbox: { x0: 40, x1: 200, y0: 60, y1: 90 }, confidence: 95 },
        { text: "70", bbox: { x0: 430, x1: 480, y0: 60, y1: 90 }, confidence: 95 },
        { text: "78", bbox: { x0: 640, x1: 690, y0: 60, y1: 90 }, confidence: 95 },
        { text: "74", bbox: { x0: 850, x1: 900, y0: 60, y1: 90 }, confidence: 94 },
      ],
    },
  ];
  const anchors = ocr.inferAnchors(
    rows.map((row) => ({ words: row.words })),
    3,
  );
  check(
    "列锚点推导接近真实右缘",
    anchors && Math.abs(anchors[0] - 480) < 20 && Math.abs(anchors[2] - 900) < 20,
    JSON.stringify(anchors),
  );
}

console.log("\n[5] 结构化与统计");
{
  const roster = [
    { id: "s1", username: "20231530", name: "林知夏" },
    { id: "s2", username: "20231531", name: "周予安" },
  ];
  const components = [
    { key: "regular", label: "平时" },
    { key: "lab", label: "实验" },
    { key: "finalExam", label: "期末" },
  ];
  const rows = [
    {
      text: "20231530 85 92 87",
      confidence: 95,
      words: [
        { text: "20231530", bbox: { x0: 40, x1: 200, y0: 0, y1: 30 }, confidence: 96 },
        { text: "85", bbox: { x0: 430, x1: 480, y0: 0, y1: 30 }, confidence: 95 },
        { text: "92", bbox: { x0: 640, x1: 690, y0: 0, y1: 30 }, confidence: 94 },
        { text: "87", bbox: { x0: 850, x1: 900, y0: 0, y1: 30 }, confidence: 93 },
      ],
    },
    {
      text: "2O231531 70 78 74",
      confidence: 88,
      words: [
        { text: "2O231531", bbox: { x0: 40, x1: 200, y0: 40, y1: 70 }, confidence: 60 },
        { text: "70", bbox: { x0: 430, x1: 480, y0: 40, y1: 70 }, confidence: 90 },
        { text: "78", bbox: { x0: 640, x1: 690, y0: 40, y1: 70 }, confidence: 90 },
        { text: "74", bbox: { x0: 850, x1: 900, y0: 40, y1: 70 }, confidence: 90 },
      ],
    },
    {
      text: "99999999 66 66 66",
      confidence: 90,
      words: [
        { text: "99999999", bbox: { x0: 40, x1: 200, y0: 80, y1: 110 }, confidence: 92 },
        { text: "66", bbox: { x0: 430, x1: 480, y0: 80, y1: 110 }, confidence: 92 },
      ],
    },
  ];
  const structure = ocr.structureRows({ rows, components, roster });
  equal("结构化行数", structure.length, 3);
  equal("第 1 行匹配学生", structure[0].username, "20231530");
  equal("第 1 行姓名", structure[0].name, "林知夏");
  equal("第 1 行三项分数", structure[0].cells.map((c) => c.value), [85, 92, 87]);
  equal("第 2 行学号被纠正", structure[1].username, "20231531");
  equal("第 2 行 corrected 标记", structure[1].corrected, true);
  check("第 2 行给出核对提示", structure[1].issues.some((i) => i.includes("自动纠正")), JSON.stringify(structure[1].issues));
  equal("第 3 行未匹配", structure[2].matched, false);
  check("第 3 行给出未匹配提示", structure[2].issues.some((i) => i.includes("未匹配")), JSON.stringify(structure[2].issues));
  equal("第 3 行不可填入", ocr.isRowUsable(structure[2]), false);
  equal("第 1 行可填入", ocr.isRowUsable(structure[0]), true);

  const summary = ocr.summarize({ rows: structure }, components);
  equal("summary 行数", summary.rows, 3);
  equal("summary 可填入", summary.usable, 2);
  equal("summary 跳过", summary.skipped, 1);

  // 越界分数应被拒绝并留空
  const outOfRange = ocr.structureRows({
    rows: [
      {
        text: "20231530 120 92 87",
        confidence: 90,
        words: [
          { text: "20231530", bbox: { x0: 40, x1: 200, y0: 0, y1: 30 }, confidence: 95 },
          { text: "120", bbox: { x0: 430, x1: 490, y0: 0, y1: 30 }, confidence: 95 },
          { text: "92", bbox: { x0: 640, x1: 690, y0: 0, y1: 30 }, confidence: 95 },
          { text: "87", bbox: { x0: 850, x1: 900, y0: 0, y1: 30 }, confidence: 95 },
        ],
      },
    ],
    components,
    roster,
  });
  equal("超界分数留空", outOfRange[0].cells[0].value, null);
  check("超界分数有提示", outOfRange[0].issues.some((i) => i.includes("超出")), JSON.stringify(outOfRange[0].issues));
}

/* --------------------------------------------------------------- 输出 */

const logDir = path.join(root, ".runtime", "logs");
fs.mkdirSync(logDir, { recursive: true });
const payload = {
  generatedAt: new Date().toISOString(),
  total: results.length,
  passed: results.length - failures,
  failed: failures,
  results,
};
fs.writeFileSync(path.join(logDir, "ocr-voice-unit.json"), JSON.stringify(payload, null, 2) + "\n");

console.log(
  `\n合计 ${payload.total} 项：通过 ${payload.passed}，失败 ${payload.failed}` +
    `\n证据：.runtime/logs/ocr-voice-unit.json`,
);
process.exit(failures ? 1 : 0);
