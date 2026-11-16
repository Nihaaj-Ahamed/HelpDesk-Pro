import { defineConfig } from "@playwright/test";
export default defineConfig({
  testDir: "./tests",
  globalSetup: "./tests/global-setup.ts",
  workers: 1,
  timeout: 120000,
  expect: { timeout: 15000 },
  use: {
    actionTimeout: 15000,
    baseURL: "http://localhost:3000",
    channel: process.env.PLAYWRIGHT_CHANNEL || "chrome",
    trace: "retain-on-failure",
  },
  webServer: {
    command: "node node_modules/next/dist/bin/next start",
    url: "http://localhost:3000",
    reuseExistingServer: process.env.PLAYWRIGHT_REUSE_SERVER === "true",
    timeout: 60000,
  },
});
