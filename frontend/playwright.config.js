import { defineConfig } from "@playwright/test";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
const cached = path.join(
  os.homedir(),
  "Library/Caches/ms-playwright/chromium-1223/chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing",
);
export default defineConfig({
  testDir: "./tests",
  timeout: 90000,
  workers: 1,
  fullyParallel: false,
  use: {
    baseURL: "https://localhost:8443",
    ignoreHTTPSErrors: true,
    launchOptions: process.env.BROWSER_EXECUTABLE
      ? { executablePath: process.env.BROWSER_EXECUTABLE }
      : fs.existsSync(cached)
        ? { executablePath: cached }
        : {},
    screenshot: "only-on-failure",
    trace: "retain-on-failure",
  },
  reporter: [
    ["list"],
    ["json", { outputFile: "../test-results/playwright.json" }],
  ],
  outputDir: "../test-results/browser",
});
