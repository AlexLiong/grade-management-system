import { test, expect } from "@playwright/test";
import fs from "node:fs";
test("course filters load matching history and saved drafts survive reload", async ({
  page,
}) => {
  await login(page, "t1101");
  await page.getByLabel("学年学期", { exact: true }).selectOption("2025-1");
  await expect(page.getByLabel("选择课程", { exact: true })).toHaveValue(
    "net-2025",
  );
  await expect(page.getByRole("button", { name: "撤销提交" })).toBeVisible();
  await page.getByLabel("学年学期", { exact: true }).selectOption("2026-1");
  await page.getByLabel("选择课程", { exact: true }).selectOption("net-2026");
  await expect(page.getByLabel("20231530 平时", { exact: true })).toBeVisible();
  await page.getByLabel("20231530 平时", { exact: true }).fill("41");
  await expect(page.getByLabel("选择课程", { exact: true })).toBeDisabled();
  await page.getByRole("button", { name: "暂存", exact: true }).click();
  await expect(page.getByText("成绩已暂存", { exact: true })).toBeVisible();
  await page.reload();
  await page.getByLabel("选择课程", { exact: true }).selectOption("net-2026");
  await expect(page.getByLabel("20231530 平时", { exact: true })).toHaveValue(
    "41",
  );
  await page.getByLabel("20231530 平时", { exact: true }).fill("40");
  await page.getByRole("button", { name: "暂存", exact: true }).click();
  await expect(page.getByText("成绩已暂存", { exact: true })).toBeVisible();
});
const config = JSON.parse(fs.readFileSync("../.runtime/config.json"));
async function login(page, role) {
  await page.goto("/");
  await page.getByLabel("账号", { exact: true }).fill(role);
  await page
    .getByLabel("密码", { exact: true })
    .fill(
      config.DEMO_ALL_PASSWORD,
    );
  await page.getByRole("button", { name: "登录", exact: true }).click();
  await expect(page.getByRole("button", { name: "退出登录" })).toBeVisible();
  if (role !== "student") {
    await page.getByLabel("选择课程", { exact: true }).selectOption("net-2026");
    await expect(page.getByText("林知夏", { exact: true })).toBeVisible();
  }
}
test("teacher desktop grades, weights, analysis, prediction and print", async ({
  page,
}) => {
  const errors = [];
  page.on("pageerror", (e) => errors.push(e.message));
  await login(page, "t1101");
  await expect(page.getByRole("heading", { name: "课程成绩" })).toBeVisible();
  await expect(page.getByText("林知夏", { exact: true })).toBeVisible();
  await page.screenshot({
    path: "../test-results/teacher-desktop.png",
    fullPage: true,
  });
  await page.getByRole("button", { name: "下一页", exact: true }).click();
  await expect(page.locator(".grade-table tbody tr")).toHaveCount(2);
  await page.getByRole("button", { name: "设置成绩系数" }).click();
  await expect(page.getByRole("dialog")).toBeVisible();
  await expect(page.getByText("总计 100%")).toBeVisible();
  await page.getByRole("button", { name: "关闭对话框" }).click();
  await page.getByRole("button", { name: "统计分析", exact: true }).click();
  await page.getByRole("button", { name: "生成预警" }).click();
  await expect(page.getByText("36 条训练样本")).toBeVisible();
  await expect(page.locator("pre")).toBeAttached();
  await page.screenshot({
    path: "../test-results/teacher-analysis.png",
    fullPage: true,
  });
  await page.emulateMedia({ media: "print" });
  await expect(page.locator(".sidebar")).toBeHidden();
  await page.pdf({
    path: "../test-results/analysis-print.pdf",
    format: "A4",
    printBackground: true,
  });
  expect(errors).toEqual([]);
});
test("student mobile private transcript and prediction fit viewport", async ({
  page,
}) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await login(page, "20231530");
  await expect(page.getByRole("heading", { name: "我的成绩" })).toBeVisible();
  await expect(page.getByText("58.0 / 60.0")).toBeVisible();
  await page.screenshot({
    path: "../test-results/student-mobile.png",
    fullPage: true,
  });
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true);
  await page.getByRole("button", { name: "学业预警", exact: true }).click();
  await page.getByRole("button", { name: "生成预警" }).click();
  await expect(page.getByText("林知夏", { exact: true })).toBeVisible();
  await expect(page.getByText("周予安", { exact: true })).toHaveCount(0);
  await page.screenshot({
    path: "../test-results/prediction-mobile.png",
    fullPage: true,
  });
});
test("administrator audit verification and XSS escape", async ({ page }) => {
  await login(page, "admin");
  let dialogs = 0;
  page.on("dialog", async (d) => {
    dialogs++;
    await d.dismiss();
  });
  await page.getByRole("button", { name: "统计分析", exact: true }).click();
  await expect(page.getByLabel("教学分析")).toContainText("");
  expect(await page.locator('img[src="x"]').count()).toBe(0);
  await page.getByRole("button", { name: "人员与权限", exact: true }).click();
  await expect(page.getByRole("button", { name: "新增人员" })).toBeVisible();
  await page.screenshot({
    path: "../test-results/admin-users.png",
    fullPage: true,
  });
  await page.getByRole("button", { name: "安全审计", exact: true }).click();
  await page.getByRole("button", { name: "核查成绩完整性" }).click();
  await expect(page.getByText("成绩与独立账本一致")).toBeVisible();
  await page.getByRole("button", { name: "分析操作序列" }).click();
  await expect(
    page.getByRole("heading", { name: "LSTM 操作序列检测" }),
  ).toBeVisible();
  await page.screenshot({
    path: "../test-results/admin-audit.png",
    fullPage: true,
  });
  expect(dialogs).toBe(0);
});
test("local OCR recognizes numeric grade sheet without uploading image", async ({
  page,
}) => {
  await login(page, "t1101");
  await page.getByRole("button", { name: "识别成绩单" }).click();
  const image = await page.evaluate(() => {
    const c = document.createElement("canvas");
    c.width = 1000;
    c.height = 200;
    const ctx = c.getContext("2d");
    ctx.fillStyle = "white";
    ctx.fillRect(0, 0, 1000, 200);
    ctx.fillStyle = "black";
    ctx.font = "36px monospace";
    ctx.fillText("20231530 40 38 45", 40, 80);
    ctx.fillText("20231531 70 80 75", 40, 140);
    return c.toDataURL("image/png").split(",")[1];
  });
  const imageRequests = [];
  page.on("request", (r) => {
    if (
      r.method() === "POST" &&
      r.postDataBuffer()?.includes(Buffer.from("PNG"))
    )
      imageRequests.push(r.url());
  });
  await page
    .getByLabel("成绩单图片")
    .setInputFiles({
      name: "grades.png",
      mimeType: "image/png",
      buffer: Buffer.from(image, "base64"),
    });
  await expect(page.getByLabel("识别结果")).toHaveValue(/20231530/, {
    timeout: 60000,
  });
  await page.getByLabel("识别结果").fill("20231530 40 38 45\n20231531 70 80 75");
  await page.getByRole("button", { name: "确认填入" }).click();
  await expect(page.getByText("已填入 2 人成绩，待暂存")).toBeVisible();
  await expect(page.getByLabel("20231530 期末", { exact: true })).toHaveValue(
    "45",
  );
  expect(imageRequests).toEqual([]);
});
test("login page desktop and mobile image renders", async ({ page }) => {
  await page.goto("/");
  await expect(
    page.getByRole("heading", { name: "登录教务工作台" }),
  ).toBeVisible();
  const loaded = await page.evaluate(
    () =>
      new Promise((resolve) => {
        const i = new Image();
        i.onload = () => resolve(i.naturalWidth);
        i.onerror = () => resolve(0);
        i.src = "/campus.jpg";
      }),
  );
  expect(loaded).toBeGreaterThan(1000);
  await page.screenshot({
    path: "../test-results/login-desktop.png",
    fullPage: true,
  });
  await page.setViewportSize({ width: 390, height: 844 });
  await page.screenshot({
    path: "../test-results/login-mobile.png",
    fullPage: true,
  });
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true);
});
