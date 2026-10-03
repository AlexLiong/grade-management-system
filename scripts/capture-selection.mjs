#!/usr/bin/env node
/**
 * 生成一张「学生选课台有可选批次」的界面截图，用于人工确认前端在真实数据下的表现。
 *
 * 前置：四个 Java 服务 + Vite 开发服务器已启动（与 browser-check.mjs 相同）。
 * 用法：node scripts/capture-selection.mjs
 *
 * 步骤：教务发布一个当前生效的选课批次 → 学生打开「网上选课」→ 截图 → 清理批次。
 */
import fs from "node:fs";
import path from "node:path";
import https from "node:https";
import { fileURLToPath, pathToFileURL } from "node:url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const BASE = process.env.FRONTEND_URL || "https://127.0.0.1:5173";
const EXECUTABLE =
  process.env.BROWSER_EXECUTABLE ||
  "C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe";
const playwrightEntry = path.join(root, "frontend", "node_modules", "playwright", "index.mjs");
const { chromium } = await import(pathToFileURL(playwrightEntry).href);

const agent = new https.Agent({ rejectUnauthorized: false, keepAlive: false });

/** 极简网关客户端：登录并保留 Cookie + CSRF。 */
function client() {
  const jar = new Map();
  const cookieHeader = () =>
    [...jar.entries()].map(([k, v]) => `${k}=${v}`).join("; ");
  const call = (apiPath, { body, query } = {}) => {
    const search = new URLSearchParams();
    for (const [k, v] of Object.entries(query || {}))
      if (v !== undefined && v !== null && v !== "") search.append(k, v);
    const suffix = search.toString();
    const payload = body === undefined ? "" : JSON.stringify(body);
    const target = new URL(`/api${apiPath}${suffix ? "?" + suffix : ""}`, "https://localhost:8443");
    const headers = {
      "Content-Type": "application/json",
      Cookie: cookieHeader(),
      "X-CSRF-Token": jar.get("CAMPUS_CSRF") || "",
    };
    if (body !== undefined) headers["Content-Length"] = Buffer.byteLength(payload);
    return new Promise((resolve, reject) => {
      const req = https.request(
        {
          hostname: target.hostname,
          port: target.port || 443,
          path: target.pathname + target.search,
          method: body === undefined ? "GET" : "POST",
          headers,
          agent,
        },
        (res) => {
          for (const line of [].concat(res.headers["set-cookie"] || [])) {
            const [pair] = String(line).split(";");
            const i = pair.indexOf("=");
            if (i > 0) jar.set(pair.slice(0, i).trim(), pair.slice(i + 1).trim());
          }
          let text = "";
          res.setEncoding("utf8");
          res.on("data", (c) => (text += c));
          res.on("end", () => {
            let data;
            try {
              data = text ? JSON.parse(text) : null;
            } catch {
              data = { raw: text.slice(0, 200) };
            }
            if (res.statusCode < 200 || res.statusCode >= 300) {
              const e = new Error((data && (data.message || data.error)) || `HTTP ${res.statusCode}`);
              e.status = res.statusCode;
              reject(e);
            } else resolve(data);
          });
        },
      );
      req.on("error", (e) => reject(new Error(e.message)));
      if (body !== undefined) req.write(payload);
      req.end();
    });
  };
  return { call };
}

const admin = client();
const student = client();
await admin.call("/login", { body: { username: "admin", password: "passwd" } });
await student.call("/login", { body: { username: "20241530", password: "passwd" } });

// 找一门尚未发布、当前学期（或任意未结束学期）的课程用于演示。
const catalog = await admin.call("/courses/catalog", { query: { size: 300 } });
const course = (catalog.items || []).find((c) => c.term === "2026-1") || (catalog.items || [])[0];
console.log(`用于演示的课程：${course.code} ${course.name}（${course.term}）`);

const now = Date.now();
const iso = (offsetMs) => new Date(now + offsetMs).toISOString().slice(0, 16);
const publish = await admin.call("/selections/save", {
  body: {
    name: `2026年春季网上选课（演示）`,
    term: course.term,
    courseIds: [course.id],
    scopeCollegeIds: ["信息工程学院"],
    startTime: iso(-3600_000),
    endTime: iso(7 * 86400_000),
    minEnroll: 2,
    maxCredits: 20,
    allowAdd: true,
    allowDrop: true,
    allowRetake: false,
    note: "用于界面确认的演示批次",
  },
});
console.log("已发布演示批次：", publish.id);

try {
  const browser = await chromium.launch({ executablePath: EXECUTABLE, headless: true });
  const context = await browser.newContext({
    ignoreHTTPSErrors: true,
    viewport: { width: 1440, height: 1000 },
  });
  const page = await context.newPage();
  await page.goto(`${BASE}/`, { waitUntil: "domcontentloaded" });
  await page.getByLabel("账号", { exact: true }).fill("20241530");
  await page.getByLabel("密码", { exact: true }).fill("passwd");
  await page.getByRole("button", { name: "登录", exact: true }).click();
  await page.getByRole("button", { name: "退出登录" }).waitFor({ timeout: 20000 });
  await page.getByRole("button", { name: "网上选课" }).first().click();
  await page.waitForTimeout(2200);
  const body = await page.locator("body").innerText();
  const shots = path.join(root, "test-results", "browser");
  fs.mkdirSync(shots, { recursive: true });
  await page.screenshot({ path: path.join(shots, "selection-student-live.png"), fullPage: true });
  console.log("截图已保存：test-results/browser/selection-student-live.png");
  console.log("页面可见文本片段：", body.replace(/\s+/g, " ").slice(0, 420));
  await browser.close();
} finally {
  await admin.call("/selections/cancel", { body: { id: publish.id, reason: "演示截图清理" } });
  console.log("已清理演示批次");
}
