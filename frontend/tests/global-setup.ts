import { expect, request } from "@playwright/test";

export default async function globalSetup() {
  const client = await request.newContext();
  try {
    await expect
      .poll(
        async () => {
          try {
            const response = await client.get(
              `${process.env.NEXT_PUBLIC_API_URL || "http://localhost:8080"}/v3/api-docs`,
              { timeout: 2000 },
            );
            if (!response.ok()) return false;
            const schema = await response.json();
            return (
              !!schema.paths?.["/api/platform/overview"] &&
              !!schema.paths?.["/api/workspaces/{slug}/team/members"]
            );
          } catch {
            return false;
          }
        },
        {
          timeout: 60000,
          intervals: [500, 1000, 2000],
          message: "M7 API must be running and ready before browser tests",
        },
      )
      .toBe(true);
    await expect
      .poll(
        async () => {
          try {
            return (
              await client.get(
                `${process.env.MAILPIT_URL || "http://localhost:8025"}/api/v1/messages`,
                { timeout: 2000 },
              )
            ).ok();
          } catch {
            return false;
          }
        },
        { timeout: 15000, message: "Local Mailpit inbox must be ready" },
      )
      .toBe(true);
  } finally {
    await client.dispose();
  }
}
