import { test, expect, Page, APIRequestContext } from "@playwright/test";
import { execFileSync } from "node:child_process";
import { readFileSync } from "node:fs";
import {
  api,
  password,
  headers,
  owner,
  emailLink,
  verifiedCustomer,
} from "./support-fixtures";
async function login(page: Page, email: string, path = "/login") {
  await page.goto(path);
  await page.getByLabel("Email address").fill(email);
  await page.getByLabel("Password", { exact: true }).fill(password);
  await page.getByRole("button", { name: "Sign in", exact: true }).click();
}
async function invite(
  request: APIRequestContext,
  slug: string,
  token: string,
  email: string,
) {
  expect(
    (
      await request.post(`${api}/api/workspaces/${slug}/team/invitations`, {
        headers: { ...headers, Authorization: `Bearer ${token}` },
        data: { email, role: "SUPPORT_AGENT" },
      })
    ).status(),
  ).toBe(201);
  const link = await emailLink(request, email, "/invitations/accept"),
    proof = new URLSearchParams(new URL(link).hash.slice(1)).get("token");
  expect(
    (
      await request.post(`${api}/api/auth/invitations/register`, {
        headers,
        data: {
          token: proof,
          fullName: "Measured Agent",
          password,
          confirmPassword: password,
        },
      })
    ).status(),
  ).toBe(201);
}
test("company admin edits SLA policies, receives notifications, saves preferences, filters analytics and exports safely", async ({
  page,
  request,
}) => {
  test.setTimeout(180000);
  const unique = Date.now().toString(36),
    slug = `operations-${unique}`,
    company = await owner(request, slug);
  await login(page, company.email);
  await expect(
    page.getByRole("heading", { name: "Workspace overview" }),
  ).toBeVisible();
  await page
    .getByRole("button", { name: "SLA Management", exact: true })
    .click();
  await expect(
    page.getByRole("heading", { name: "Company SLA policies" }),
  ).toBeVisible();
  const medium = page.locator(".policy-row").filter({ hasText: "MEDIUM" });
  await medium.getByLabel("First response minutes").fill("2");
  await medium.getByLabel("Resolution minutes").fill("10");
  await medium
    .getByRole("button", { name: "Save MEDIUM", exact: true })
    .click();
  await expect(page.getByRole("status")).toContainText("Policy saved");
  const token = await verifiedCustomer(
    request,
    slug,
    `customer-${unique}@example.test`,
  );
  const created = await request.post(`${api}/api/support/${slug}/tickets`, {
    headers: { ...headers, Authorization: `Bearer ${token}` },
    data: {
      subject: "=Formula-safe report fixture",
      description: "Confidential detail excluded from CSV",
      priority: "MEDIUM",
    },
  });
  expect(created.status()).toBe(201);
  const ticket = await created.json();
  await page.getByRole("button", { name: /^Notifications,/ }).click();
  await expect(page.locator(".notification-dropdown")).toContainText(
    ticket.reference,
    { timeout: 20000 },
  );
  await page
    .getByRole("link", { name: "View all notifications and preferences" })
    .click();
  await expect(
    page.getByRole("heading", { name: "Notifications", exact: true }),
  ).toBeVisible();
  await page
    .getByRole("button", { name: "Mark all as read", exact: true })
    .click();
  await page.getByLabel("Unread only").check();
  await expect(page.getByText("No matching notifications.")).toBeVisible();
  await page.getByLabel("Enable optional emails").uncheck();
  await page
    .getByRole("button", { name: "Save preferences", exact: true })
    .click();
  await expect(page.getByRole("status")).toContainText("Saved successfully");
  await page.goto(`/workspace/${slug}?view=analytics`);
  await expect(
    page.getByRole("heading", { name: "Tickets by priority" }),
  ).toBeVisible();
  await page.getByRole("combobox", { name: "Status", exact: true }).selectOption("CLOSED");
  await expect(
    page
      .locator(".workflow-summary>div")
      .filter({ hasText: /^Total/ })
      .locator("strong"),
  ).toHaveText("0");
  await page.getByRole("button", { name: "Reports", exact: true }).click();
  await expect(
    page.getByRole("heading", { name: "Download report" }),
  ).toBeVisible();
  const downloadPromise = page.waitForEvent("download");
  await page.getByRole("button", { name: "Download CSV", exact: true }).click();
  const download = await downloadPromise;
  const file = await download.path();
  expect(file).toBeTruthy();
  const text = readFileSync(file!, "utf8");
  expect(text).toContain("'=Formula-safe report fixture");
  expect(text).not.toContain("Confidential detail");
});
test("support agent has personal performance without company analytics or platform authority", async ({
  page,
  request,
}) => {
  const unique = Date.now().toString(36),
    slug = `agent-metrics-${unique}`,
    company = await owner(request, slug),
    email = `agent-${unique}@example.test`;
  await invite(request, slug, company.token, email);
  await login(page, email);
  await expect(
    page.getByRole("heading", { name: "Workspace overview" }),
  ).toBeVisible();
  await expect(
    page.getByRole("button", { name: "Analytics", exact: true }),
  ).not.toBeVisible();
  await page
    .getByRole("button", { name: "My Performance", exact: true })
    .click();
  await expect(
    page.getByRole("heading", { name: "My measured performance" }),
  ).toBeVisible();
  await expect(page.locator(".ticket-table")).toContainText("Measured Agent");
  await page.goto("/platform");
  await expect(page.locator(".error[role=alert]")).toContainText(
    "Platform administrator authority",
  );
});
test("operator-provisioned platform admin suspends and reactivates a company with audited controls", async ({
  page,
  browser,
  request,
}) => {
  test.setTimeout(180000);
  const unique = Date.now().toString(36),
    operator = await owner(request, `platform-${unique}`),
    slug = `managed-${unique}`,
    tenant = await owner(request, slug);
  // This is a controlled database-operator fixture, never an application privilege-grant API.
  execFileSync(
    "docker",
    [
      "compose",
      "exec",
      "-T",
      "postgres",
      "psql",
      "-U",
      "helpdesk",
      "-d",
      "helpdesk",
      "-v",
      "ON_ERROR_STOP=1",
      "-v",
      `target_email=${operator.email}`,
    ],
    {
      cwd: "..",
      input:
        "INSERT INTO platform_administrators(account_id) SELECT id FROM accounts WHERE email=:'target_email' ON CONFLICT DO NOTHING;",
      encoding: "utf8",
      timeout: 30000,
    },
  );
  try {
    await login(page, operator.email, "/platform/login");
    await expect(page).toHaveURL("/platform");
    await expect(
      page.getByRole("heading", { name: "Overview", exact: true }),
    ).toBeVisible();
    await page.getByRole("button", { name: "Companies", exact: true }).click();
    await page.getByLabel("Search companies").fill(slug);
    await expect(page.locator(".ticket-table tbody tr")).toHaveCount(1);
    await page
      .getByRole("button", { name: "Company details", exact: true })
      .click();
    await page
      .getByLabel("Administrative reason")
      .fill("Automated suspension authorization check");
    page.once("dialog", (d) => d.accept());
    await page
      .getByRole("button", { name: "Suspend company", exact: true })
      .click();
    await expect(
      page.getByRole("cell", { name: "SUSPENDED", exact: true }),
    ).toBeVisible();
    const tenantContext = await browser.newContext(),
      tenantPage = await tenantContext.newPage();
    await login(tenantPage, tenant.email);
    await expect(tenantPage.getByRole("status")).toContainText("suspended");
    expect(
      (
        await request.get(`${api}/api/workspaces/${slug}/tickets`, {
          headers: { Authorization: `Bearer ${operator.token}` },
        })
      ).status(),
    ).toBe(404);
    await page
      .getByRole("button", { name: "Company details", exact: true })
      .click();
    await page
      .getByLabel("Administrative reason")
      .fill("Automated review finished reactivation approved");
    page.once("dialog", (d) => d.accept());
    await page
      .getByRole("button", { name: "Reactivate company", exact: true })
      .click();
    await expect(
      page.getByRole("cell", { name: "ACTIVE", exact: true }),
    ).toBeVisible();
    await tenantPage.reload();
    await expect(
      tenantPage.getByRole("heading", { name: "Workspace overview" }),
    ).toBeVisible();
    await page
      .getByRole("button", { name: "Administrative Activity", exact: true })
      .click();
    await expect(page.locator(".ticket-table")).toContainText(
      "Company suspended",
    );
    await expect(page.locator(".ticket-table")).toContainText(
      "Company reactivated",
    );
    await tenantContext.close();
  } finally {
    execFileSync(
      "docker",
      [
        "compose",
        "exec",
        "-T",
        "postgres",
        "psql",
        "-U",
        "helpdesk",
        "-d",
        "helpdesk",
        "-v",
        "ON_ERROR_STOP=1",
        "-v",
        `target_email=${operator.email}`,
      ],
      {
        cwd: "..",
        input:
          "DELETE FROM platform_administrators WHERE account_id IN (SELECT id FROM accounts WHERE email=:'target_email');",
        encoding: "utf8",
        timeout: 30000,
      },
    );
  }
});
