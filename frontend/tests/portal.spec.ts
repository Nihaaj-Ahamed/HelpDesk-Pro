import { test, expect } from "@playwright/test";
import {
  api,
  mail,
  password,
  headers,
  owner,
  emailLink,
  verifiedCustomer,
  portalLogin,
} from "./support-fixtures";
test("customer registers, verifies email, submits and tracks a ticket; support agent reads it", async ({
  page,
  browser,
  request,
}) => {
  test.setTimeout(180000);
  const unique = Date.now().toString(36),
    slug = `portal-${unique}`,
    customerEmail = `customer-${unique}@example.test`,
    agentEmail = `support-${unique}@example.test`;
  const company = await owner(request, slug);
  await page.goto(`/support/${slug}`);
  await expect(page.locator(".portal-brand")).toContainText(`Studio ${slug}`);
  await expect(
    page.getByRole("heading", { name: "How can we help?" }),
  ).toBeVisible();
  await page.screenshot({ path: "../docs/support-portal.png", fullPage: true });
  await page
    .getByRole("link", { name: "Create an account", exact: true })
    .click();
  await page.getByLabel("Full name", { exact: true }).fill("Taylor Customer");
  await page.getByLabel("Email address").fill(customerEmail);
  await page.getByLabel("Password", { exact: true }).fill(password);
  await page.getByLabel("Confirm password").fill(password);
  await page.getByRole("button", { name: "Create customer account" }).click();
  await expect(
    page.getByRole("heading", { name: "Verify your email to continue" }),
  ).toBeVisible();
  await expect(
    page.getByRole("button", { name: "Submit ticket" }),
  ).not.toBeVisible();
  const verification = await emailLink(
    request,
    customerEmail,
    `/support/${slug}/verify`,
  );
  await page.goto(verification);
  await expect(page).not.toHaveURL(/#token=/);
  await page.getByRole("button", { name: "Confirm email and join" }).click();
  await expect(
    page.getByRole("heading", { name: "You’re ready to get support" }),
  ).toBeVisible();
  await page.getByRole("link", { name: "Open support dashboard" }).click();
  await expect(
    page.getByRole("heading", { name: "Your support overview" }),
  ).toBeVisible();
  await expect(
    page
      .locator(".metric")
      .filter({ hasText: "Submitted tickets" })
      .locator("strong"),
  ).toHaveText("0");
  await page
    .getByRole("navigation", { name: "Support navigation" })
    .getByRole("link", { name: "Create Ticket" })
    .click();
  await expect(
    page.getByRole("heading", { name: "Create a support ticket" }),
  ).toBeVisible();
  await page.screenshot({ path: "../docs/create-ticket.png", fullPage: true });
  await page
    .getByLabel("Subject", { exact: true })
    .fill("Unable to download my invoice");
  const description =
    "The invoice download fails with error 502. This started today.\n\nI tried signing out and using another browser, but the same error appears. Please help me access this month’s invoice.";
  await page.getByLabel("Detailed description").fill(description);
  await page
    .getByLabel("Category", { exact: true })
    .selectOption({ label: "Billing" });
  await page.getByLabel("Priority", { exact: true }).selectOption("HIGH");
  await page
    .getByRole("button", { name: "Submit ticket", exact: true })
    .click();
  await expect(page).toHaveURL(
    new RegExp(`/support/${slug}/tickets/[a-f0-9-]+$`),
  );
  const ticketUrl = page.url();
  const ticketId = ticketUrl.split("/").at(-1)!;
  await expect(
    page.getByRole("heading", { name: "Unable to download my invoice" }),
  ).toBeVisible();
  await expect(
    page.getByRole("status").filter({ hasText: "submitted successfully" }),
  ).toBeVisible();
  await expect(page.locator(".ticket-metadata")).toContainText("Unassigned");
  await expect(page.locator(".ticket-metadata")).toContainText("High");
  await expect(page.locator(".ticket-description")).toHaveText(description);
  await page.screenshot({
    path: "../docs/customer-ticket-detail.png",
    fullPage: true,
  });
  await page
    .getByRole("navigation", { name: "Support navigation" })
    .getByRole("link", { name: "Overview", exact: true })
    .click();
  await expect(
    page
      .locator(".metric")
      .filter({ hasText: "Submitted tickets" })
      .locator("strong"),
  ).toHaveText("1");
  await expect(
    page
      .locator(".metric")
      .filter({ hasText: "Open tickets" })
      .locator("strong"),
  ).toHaveText("1");
  await expect(
    page
      .locator(".metric")
      .filter({ hasText: "Resolved tickets" })
      .locator("strong"),
  ).toHaveText("0");
  await page.screenshot({
    path: "../docs/customer-dashboard.png",
    fullPage: true,
  });
  await page.setViewportSize({ width: 390, height: 844 });
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= window.innerWidth,
    ),
  ).toBe(true);
  await page.screenshot({
    path: "../docs/customer-dashboard-mobile.png",
    fullPage: true,
  });
  await page.setViewportSize({ width: 1280, height: 800 });
  await page
    .getByRole("navigation", { name: "Support navigation" })
    .getByRole("link", { name: "My Tickets", exact: true })
    .click();
  await page.getByLabel("Search tickets").fill("invoice");
  await expect(
    page.getByRole("link", { name: "Unable to download my invoice" }),
  ).toBeVisible();
  await page.getByLabel("Filter ticket status").selectOption("CLOSED");
  await expect(
    page.getByRole("heading", { name: "No matching tickets" }),
  ).toBeVisible();
  await page.getByLabel("Filter ticket status").selectOption("");
  await page.reload();
  await expect(
    page.getByRole("heading", { name: "My tickets", exact: true, level: 1 }),
  ).toBeVisible();
  expect(
    (
      await request.post(`${api}/api/workspaces/${slug}/team/invitations`, {
        headers: { ...headers, Authorization: `Bearer ${company.token}` },
        data: { email: agentEmail, role: "SUPPORT_AGENT" },
      })
    ).status(),
  ).toBe(201);
  const invitation = await emailLink(
    request,
    agentEmail,
    "/invitations/accept",
  );
  const inviteToken = new URLSearchParams(
    new URL(invitation).hash.slice(1),
  ).get("token");
  expect(
    (
      await request.post(`${api}/api/auth/invitations/register`, {
        headers,
        data: {
          token: inviteToken,
          fullName: "Sam Support",
          password,
          confirmPassword: password,
        },
      })
    ).status(),
  ).toBe(201);
  const agentContext = await browser.newContext(),
    agent = await agentContext.newPage();
  await agent.goto("/login");
  await agent.getByLabel("Email address").fill(agentEmail);
  await agent.getByLabel("Password", { exact: true }).fill(password);
  await agent.getByRole("button", { name: "Sign in", exact: true }).click();
  await expect(agent).toHaveURL(`/workspace/${slug}`);
  await expect(
    agent
      .locator(".metric")
      .filter({ hasText: "Total tickets" })
      .locator("strong"),
  ).toHaveText("1");
  await agent.getByRole("button", { name: /^Tickets/ }).click();
  await expect(
    agent.getByRole("link", { name: "Unable to download my invoice" }),
  ).toBeVisible();
  await expect(agent.locator(".ticket-table")).toContainText(customerEmail);
  await expect(
    agent.getByRole("button", { name: "Team", exact: true }),
  ).not.toBeVisible();
  await agent.screenshot({
    path: "../docs/staff-ticket-list.png",
    fullPage: true,
  });
  await agent
    .getByRole("link", { name: "Unable to download my invoice" })
    .click();
  await expect(agent).toHaveURL(`/workspace/${slug}/tickets/${ticketId}`);
  await expect(
    agent.getByRole("heading", { name: "Unable to download my invoice" }),
  ).toBeVisible();
  await expect(agent.locator(".ticket-description")).toHaveText(description);
  await agent
    .getByRole("button", { name: "Assign to Me", exact: true })
    .click();
  await agent
    .getByRole("button", { name: "Confirm change", exact: true })
    .click();
  await expect(
    agent.locator(".ticket-detail-heading .ticket-status"),
  ).toHaveText("Assigned");
  await agent.getByRole("button", { name: "Start work", exact: true }).click();
  await agent
    .getByRole("button", { name: "Confirm change", exact: true })
    .click();
  await expect(
    agent.locator(".ticket-detail-heading .ticket-status"),
  ).toHaveText("In progress");
  await agent.getByLabel("Priority", { exact: true }).selectOption("CRITICAL");
  await agent
    .getByRole("button", { name: "Update priority", exact: true })
    .click();
  await agent
    .getByRole("button", { name: "Confirm change", exact: true })
    .click();
  await expect(agent.locator(".ticket-metadata")).toContainText("Critical");
  await agent
    .getByRole("button", { name: "Mark resolved", exact: true })
    .click();
  await agent
    .getByRole("button", { name: "Confirm change", exact: true })
    .click();
  await expect(
    agent.locator(".ticket-detail-heading .ticket-status"),
  ).toHaveText("Resolved");
  await expect(agent.locator(".activity-timeline")).toContainText(
    "Sam Support",
  );
  await expect(agent.locator(".activity-timeline")).toContainText(
    "In progress to Resolved",
  );
  await agent.screenshot({
    path: "../docs/ticket-workflow.png",
    fullPage: true,
  });
  await page.goto(`/support/${slug}/tickets/${ticketId}`);
  await page.getByRole("button", { name: "Close ticket", exact: true }).click();
  await page
    .getByRole("button", { name: "Confirm change", exact: true })
    .click();
  await expect(
    page.locator(".ticket-detail-heading .ticket-status"),
  ).toHaveText("Closed");
  await expect(page.locator(".activity-timeline")).not.toContainText(
    "Sam Support",
  );
  await page
    .getByRole("button", { name: "Reopen ticket", exact: true })
    .click();
  await page
    .getByRole("button", { name: "Confirm change", exact: true })
    .click();
  await expect(
    page.locator(".ticket-detail-heading .ticket-status"),
  ).toHaveText("Open");
  await agent.reload();

  await expect(
    agent.getByRole("button", { name: "Assign", exact: true }),
  ).not.toBeVisible();
  await agent
    .getByRole("link", { name: "Back to tickets", exact: true })
    .click();
  await expect(
    agent.getByRole("heading", { name: /Company tickets/ }),
  ).toBeVisible();
  await agentContext.close();
});

test("another customer cannot read private tickets, and another portal requires its own email proof", async ({
  page,
  request,
}) => {
  const unique = Date.now().toString(36),
    slug = `private-${unique}`,
    other = `other-${unique}`,
    emailA = `one-${unique}@example.test`,
    emailB = `two-${unique}@example.test`;
  await owner(request, slug);
  const foreignOwner = await owner(request, other);
  const customerA = await verifiedCustomer(request, slug, emailA);
  const customerB = await verifiedCustomer(request, slug, emailB);
  const privateDescription =
    'Private information only this customer can see.\n<img src=x onerror="window.ticketXss=true">';
  const response = await request.post(`${api}/api/support/${slug}/tickets`, {
    headers: { ...headers, Authorization: `Bearer ${customerA}` },
    data: {
      subject: "Private billing request",
      description: privateDescription,
      priority: "MEDIUM",
    },
  });
  expect(response.status()).toBe(201);
  const ticket = await response.json();
  expect(
    (
      await request.get(
        `${api}/api/workspaces/${slug}/tickets/${ticket.id}/activity`,
        { headers: { Authorization: `Bearer ${foreignOwner.token}` } },
      )
    ).status(),
  ).toBe(404);
  expect(
    (
      await request.post(
        `${api}/api/workspaces/${slug}/tickets/${ticket.id}/actions`,
        {
          headers: {
            ...headers,
            Authorization: `Bearer ${foreignOwner.token}`,
          },
          data: { version: ticket.version, action: "CLAIM" },
        },
      )
    ).status(),
  ).toBe(404);
  expect(
    (
      await request.get(
        `${api}/api/support/${slug}/tickets/${ticket.id}/activity`,
        { headers: { Authorization: `Bearer ${customerB}` } },
      )
    ).status(),
  ).toBe(404);
  expect(
    (
      await request.post(
        `${api}/api/support/${slug}/tickets/${ticket.id}/actions`,
        {
          headers: { ...headers, Authorization: `Bearer ${customerA}` },
          data: {
            version: ticket.version,
            action: "PRIORITY",
            value: "CRITICAL",
          },
        },
      )
    ).status(),
  ).toBe(403);
  await portalLogin(page, slug, emailA);
  await page.goto(`/support/${slug}/tickets/${ticket.id}`);
  await expect(page.locator(".ticket-description")).toHaveText(
    privateDescription,
  );
  await expect(page.locator(".ticket-description img")).toHaveCount(0);
  await page.getByRole("button", { name: "Sign out", exact: true }).click();
  await portalLogin(page, slug, emailB);
  await expect(
    page
      .locator(".metric")
      .filter({ hasText: "Submitted tickets" })
      .locator("strong"),
  ).toHaveText("0");
  await page.goto(`/support/${slug}/tickets/${ticket.id}`);
  await expect(
    page.getByRole("heading", { name: "Ticket unavailable" }),
  ).toBeVisible();
  await expect(
    page.getByText("Private information only this customer can see."),
  ).not.toBeVisible();
  await page.goto(`/support/${other}/tickets/${ticket.id}`);
  await expect(
    page.getByRole("heading", { name: "Verify your email to continue" }),
  ).toBeVisible();
  await page.goto(`/workspace/${slug}`);
  await expect(page.getByRole("status")).toContainText(
    "Staff access is required",
  );
});

test("existing account joins a customer portal without losing its company-admin role", async ({
  page,
  request,
}) => {
  const unique = Date.now().toString(36),
    home = `home-${unique}`,
    support = `help-${unique}`;
  const existing = await owner(request, home);
  await owner(request, support);
  await portalLogin(page, support, existing.email);
  await expect(
    page.getByRole("heading", { name: "Verify your email to continue" }),
  ).toBeVisible();
  await page.getByRole("button", { name: "Send verification email" }).click();
  await expect(
    page.getByRole("status").filter({ hasText: "Verification email sent" }),
  ).toBeVisible();
  const link = await emailLink(
    request,
    existing.email,
    `/support/${support}/verify`,
  );
  await page.goto(link);
  await page.getByRole("button", { name: "Confirm email and join" }).click();
  await page.getByRole("link", { name: "Open support dashboard" }).click();
  await expect(
    page.getByRole("heading", { name: "Your support overview" }),
  ).toBeVisible();
  await page.goto(`/workspace/${home}`);
  await expect(
    page.getByRole("button", { name: "Team", exact: true }),
  ).toBeVisible();
  await page.goto(`/workspace/${support}`);
  await expect(page.getByRole("status")).toContainText(
    "Staff access is required",
  );
});

test("admin assigns and reassigns active agents, sees workloads and releases tickets", async ({
  page,
  request,
}) => {
  test.setTimeout(120000);
  const unique = Date.now().toString(36),
    slug = `ops-${unique}`;
  const company = await owner(request, slug);
  for (const name of ["Morgan Agent", "Riley Agent"]) {
    const email = `${name.split(" ")[0].toLowerCase()}-${unique}@example.test`;
    expect(
      (
        await request.post(`${api}/api/workspaces/${slug}/team/invitations`, {
          headers: { ...headers, Authorization: `Bearer ${company.token}` },
          data: { email, role: "SUPPORT_AGENT" },
        })
      ).status(),
    ).toBe(201);
    const link = await emailLink(request, email, "/invitations/accept");
    const token = new URLSearchParams(new URL(link).hash.slice(1)).get("token");
    expect(
      (
        await request.post(`${api}/api/auth/invitations/register`, {
          headers,
          data: { token, fullName: name, password, confirmPassword: password },
        })
      ).status(),
    ).toBe(201);
  }
  const customer = await verifiedCustomer(
    request,
    slug,
    `ops-customer-${unique}@example.test`,
  );
  const created = await request.post(`${api}/api/support/${slug}/tickets`, {
    headers: { ...headers, Authorization: `Bearer ${customer}` },
    data: {
      subject: "Router intermittently offline",
      description: "Network drops several times throughout the day.",
      priority: "HIGH",
    },
  });
  expect(created.status()).toBe(201);
  const ticket = await created.json();
  await page.goto("/login");
  await page.getByLabel("Email address").fill(company.email);
  await page.getByLabel("Password", { exact: true }).fill(password);
  await page.getByRole("button", { name: "Sign in", exact: true }).click();
  await expect(page).toHaveURL(`/workspace/${slug}`);
  await expect(
    page.getByRole("heading", { name: "Workspace overview" }),
  ).toBeVisible();
  await page.goto(`/workspace/${slug}/tickets/${ticket.id}`);
  await page
    .getByLabel("Assign agent", { exact: true })
    .selectOption({ label: "Morgan Agent - 0 active tickets" });
  await page.getByRole("button", { name: "Assign agent", exact: true }).click();
  await page
    .getByRole("button", { name: "Confirm change", exact: true })
    .click();
  await expect(
    page.locator(".ticket-detail-heading .ticket-status"),
  ).toHaveText("Assigned");
  await expect(
    page
      .getByLabel("Assign agent", { exact: true })
      .locator("option")
      .filter({ hasText: "Morgan Agent" }),
  ).toHaveText("Morgan Agent - 1 active tickets");
  await page.getByRole("button", { name: "Start work", exact: true }).click();
  await page
    .getByRole("button", { name: "Confirm change", exact: true })
    .click();
  await expect(
    page.locator(".ticket-detail-heading .ticket-status"),
  ).toHaveText("In progress");
  await page
    .getByLabel("Assign agent", { exact: true })
    .selectOption({ label: "Riley Agent - 0 active tickets" });
  await page.getByRole("button", { name: "Assign agent", exact: true }).click();
  await page
    .getByRole("button", { name: "Confirm change", exact: true })
    .click();
  await expect(page.locator(".ticket-metadata")).toContainText("Riley Agent");
  await expect(page.locator(".activity-timeline")).toContainText(
    "Morgan Agent",
  );
  await expect(page.locator(".activity-timeline")).toContainText("Riley Agent");
  await page
    .getByRole("button", { name: "Release ticket", exact: true })
    .click();
  await page
    .getByRole("button", { name: "Confirm change", exact: true })
    .click();
  await expect(
    page.locator(".ticket-detail-heading .ticket-status"),
  ).toHaveText("Open");
  await expect(page.locator(".ticket-metadata")).toContainText("Unassigned");
  await page
    .getByRole("link", { name: "Back to tickets", exact: true })
    .click();
  await page
    .getByRole("button", { name: "Unassigned Tickets", exact: true })
    .click();
  await expect(
    page.getByRole("link", { name: "Router intermittently offline" }),
  ).toBeVisible();
  await page.getByRole("button", { name: "In Progress", exact: true }).click();
  await expect(
    page.getByRole("link", { name: "Router intermittently offline" }),
  ).not.toBeVisible();
});
