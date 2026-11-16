import { test, expect, Page, APIRequestContext } from "@playwright/test";
import {
  api,
  password,
  headers,
  owner,
  emailLink,
  verifiedCustomer,
  portalLogin,
} from "./support-fixtures";
async function staffLogin(page: Page, email: string, slug: string) {
  await page.goto("/login");
  await page.getByLabel("Email address").fill(email);
  await page.getByLabel("Password", { exact: true }).fill(password);
  await page.getByRole("button", { name: "Sign in", exact: true }).click();
  await expect(page).toHaveURL(`/workspace/${slug}`);
  await expect(
    page.getByRole("heading", { name: "Workspace overview" }),
  ).toBeVisible();
}
async function agentAccount(
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
  const link = await emailLink(request, email, "/invitations/accept");
  const invite = new URLSearchParams(new URL(link).hash.slice(1)).get("token");
  expect(
    (
      await request.post(`${api}/api/auth/invitations/register`, {
        headers,
        data: {
          token: invite,
          fullName: "Support Specialist",
          password,
          confirmPassword: password,
        },
      })
    ).status(),
  ).toBe(201);
}
async function create(request: APIRequestContext, slug: string, token: string) {
  const r = await request.post(`${api}/api/support/${slug}/tickets`, {
    headers: { ...headers, Authorization: `Bearer ${token}` },
    data: {
      subject: "Conversation request",
      description: "Please help with my account.",
    },
  });
  expect(r.status()).toBe(201);
  return r.json();
}
test("public replies arrive live, internal notes stay private, and reconnect does not duplicate history", async ({
  page,
  browser,
  request,
}) => {
  test.setTimeout(180000);
  const unique = Date.now().toString(36),
    slug = `chat-${unique}`,
    email = `customer-${unique}@example.test`;
  const company = await owner(request, slug),
    token = await verifiedCustomer(request, slug, email),
    ticket = await create(request, slug, token);
  const adminContext = await browser.newContext(),
    admin = await adminContext.newPage();
  const frames: string[] = [];
  admin.on("websocket", (ws) =>
    ws.on("framereceived", (e) => frames.push(String(e.payload))),
  );
  await staffLogin(admin, company.email, slug);
  await admin.goto(`/workspace/${slug}/tickets/${ticket.id}`);
  await admin
    .getByRole("heading", { name: "Conversation", exact: true })
    .scrollIntoViewIfNeeded();
  await expect(admin.getByText("Live updates", { exact: true })).toBeVisible();
  await portalLogin(page, slug, email);
  await page.goto(`/support/${slug}/tickets/${ticket.id}`);
  await page
    .getByRole("heading", { name: "Conversation", exact: true })
    .scrollIntoViewIfNeeded();
  await expect(page.getByText("Live updates", { exact: true })).toBeVisible();
  await expect(page.getByLabel("Message visibility")).not.toBeVisible();
  await admin.getByLabel("Message visibility").selectOption("INTERNAL");
  await admin
    .getByLabel("Internal note", { exact: true })
    .fill("Private billing check: staff only.");
  await admin
    .getByRole("button", { name: "Save internal note", exact: true })
    .click();
  await expect(admin.locator(".internal-note")).toContainText(
    "Private billing check",
  );
  await expect(page.locator(".conversation-thread")).not.toContainText(
    "Private billing check",
  );
  frames.length = 0;
  await page
    .getByLabel("Public reply", { exact: true })
    .fill("I need help with the invoice.");
  await page.getByRole("button", { name: "Send reply", exact: true }).click();
  await expect(admin.locator(".conversation-thread")).toContainText(
    "I need help with the invoice.",
    { timeout: 5000 },
  );
  await expect
    .poll(() => frames.some((f) => f.includes("conversation.changed")))
    .toBe(true);
  await admin.getByLabel("Message visibility").selectOption("PUBLIC");
  await admin
    .getByLabel("Public reply", { exact: true })
    .fill("We are checking your invoice.");
  await admin.getByRole("button", { name: "Send reply", exact: true }).click();
  await expect(page.locator(".conversation-thread")).toContainText(
    "We are checking your invoice.",
    { timeout: 5000 },
  );
  await admin.screenshot({
    path: "../docs/ticket-conversation.png",
    fullPage: true,
  });
  await page.reload();
  await page
    .getByRole("heading", { name: "Conversation", exact: true })
    .scrollIntoViewIfNeeded();
  await expect(page.locator(".conversation-message")).toHaveCount(2);
  await expect(page.locator(".internal-note")).toHaveCount(0);
  await expect(page.getByText("Live updates", { exact: true })).toBeVisible();
  await admin
    .getByLabel("Public reply", { exact: true })
    .fill("A second update after reconnect.");
  await admin.getByRole("button", { name: "Send reply", exact: true }).click();
  await expect(page.locator(".conversation-message")).toHaveCount(3, {
    timeout: 5000,
  });
  await expect(page.locator(".conversation-thread")).toContainText(
    "A second update after reconnect.",
  );
  await adminContext.close();
});
test("assigned agent notes, accurate unread state, and REST fallback when WebSocket is blocked", async ({
  page,
  browser,
  request,
}) => {
  test.setTimeout(180000);
  const unique = Date.now().toString(36),
    slug = `unread-${unique}`,
    email = `reader-${unique}@example.test`,
    agentEmail = `staff-${unique}@example.test`;
  const company = await owner(request, slug);
  await agentAccount(request, slug, company.token, agentEmail);
  const token = await verifiedCustomer(request, slug, email),
    ticket = await create(request, slug, token);
  await portalLogin(page, slug, email);
  const agentContext = await browser.newContext(),
    agent = await agentContext.newPage();
  await staffLogin(agent, agentEmail, slug);
  await agent.goto(`/workspace/${slug}/tickets/${ticket.id}`);
  await expect(
    agent.getByLabel("Public reply", { exact: true }),
  ).not.toBeVisible();
  await agent
    .getByRole("button", { name: "Assign to Me", exact: true })
    .click();
  await agent
    .getByRole("button", { name: "Confirm change", exact: true })
    .click();
  await expect(agent.getByLabel("Public reply", { exact: true })).toBeVisible();
  await agent.getByLabel("Message visibility").selectOption("INTERNAL");
  await agent
    .getByLabel("Internal note", { exact: true })
    .fill("Private agent context.");
  await agent
    .getByRole("button", { name: "Save internal note", exact: true })
    .click();
  await expect(agent.locator(".internal-note")).toHaveCount(1);
  const read = async () => {
    const r = await request.get(
      `${api}/api/support/${slug}/tickets/${ticket.id}`,
      { headers: { Authorization: `Bearer ${token}` } },
    );
    return (await r.json()).unreadCount;
  };
  expect(await read()).toBe(0);
  await agent.getByLabel("Message visibility").selectOption("PUBLIC");
  await agent
    .getByLabel("Public reply", { exact: true })
    .fill("Your assigned agent is here.");
  await agent.getByRole("button", { name: "Send reply", exact: true }).click();
  await expect.poll(read).toBe(1);
  await expect(page.locator(".unread-summary")).toContainText("1 unread", {
    timeout: 25000,
  });
  await page
    .getByRole("navigation", { name: "Support navigation" })
    .getByRole("link", { name: "My Tickets", exact: true })
    .click();
  await page.getByRole("button", { name: "Refresh", exact: true }).click();
  await expect(page.getByLabel("1 unread messages")).toBeVisible();
  await page.routeWebSocket("**/ws/conversations", (ws) => ws.close());
  await page
    .getByRole("link", { name: "Conversation request", exact: true })
    .click();
  await page
    .getByRole("heading", { name: "Conversation", exact: true })
    .scrollIntoViewIfNeeded();
  await expect(page.locator(".conversation-thread")).toContainText(
    "Your assigned agent is here.",
  );
  await page.bringToFront();
  await page.locator(".conversation-end").scrollIntoViewIfNeeded();
  await expect.poll(read).toBe(0);
  await expect(page.locator(".internal-note")).toHaveCount(0);
  await agent
    .getByLabel("Public reply", { exact: true })
    .fill("Fallback refresh delivers this message.");
  await agent.getByRole("button", { name: "Send reply", exact: true }).click();
  await expect(page.locator(".conversation-thread")).toContainText(
    "Fallback refresh delivers this message.",
    { timeout: 25000 },
  );
  await expect(page.locator(".conversation-message")).toHaveCount(2);
  await page.bringToFront();
  await page.locator(".conversation-end").scrollIntoViewIfNeeded();
  await expect.poll(read).toBe(0);
  await page.setViewportSize({ width: 390, height: 844 });
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= window.innerWidth,
    ),
  ).toBe(true);
  await page.screenshot({
    path: "../docs/conversation-mobile.png",
    fullPage: true,
  });
  await agentContext.close();
});
