import { test, expect, APIRequestContext, Page } from "@playwright/test";
const password = "a-secure-password-123";
const mailBase = process.env.MAILPIT_URL || "http://localhost:8025";
const apiBase = process.env.NEXT_PUBLIC_API_URL || "http://localhost:8080";
async function owner(request: APIRequestContext, email: string, slug: string) {
  const response = await request.post(`${apiBase}/api/auth/register`, {
    timeout: 60000,
    headers: { Origin: "http://localhost:3000" },
    data: {
      fullName: "Alex Morgan",
      email,
      password,
      confirmPassword: password,
      companyName: `Studio ${slug}`,
      slug,
    },
  });
  expect(response.status()).toBe(201);
}
async function login(page: Page, email: string, slug: string) {
  await page.goto("/login");
  await page.getByLabel("Email address").fill(email);
  await page.getByLabel("Password", { exact: true }).fill(password);
  await page.getByRole("button", { name: "Sign in", exact: true }).click();
  await expect(page).toHaveURL(`/workspace/${slug}`);
  await expect(
    page.getByRole("heading", { name: "Workspace overview" }),
  ).toBeVisible();
}
async function invite(page: Page, email: string) {
  await page.getByRole("button", { name: "Invite Agent", exact: true }).click();
  await page.getByLabel("Agent email address").fill(email);
  await page
    .getByRole("button", { name: "Send invitation", exact: true })
    .click();
  await expect(page.getByRole("dialog")).not.toBeVisible();
  await expect(
    page.getByRole("status").filter({ hasText: "Invitation sent" }),
  ).toBeVisible();
}
async function emailedLink(
  request: APIRequestContext,
  email: string,
  previous = "",
) {
  let link = "";
  await expect
    .poll(
      async () => {
        const response = await request.get(`${mailBase}/api/v1/messages`);
        const list = await response.json();
        for (const message of list.messages) {
          if (
            !message.To?.some(
              (to: { Address: string }) =>
                to.Address.toLowerCase() === email.toLowerCase(),
            )
          )
            continue;
          const detail = await (
            await request.get(`${mailBase}/api/v1/message/${message.ID}`)
          ).json();
          const match = detail.Text.match(
            /http:\/\/localhost:3000\/invitations\/accept#token=[A-Za-z0-9_-]{43}/,
          );
          if (match && match[0] !== previous) {
            link = match[0];
            return true;
          }
        }
        return false;
      },
      { timeout: 15000, message: "Invitation arrives in local Mailpit" },
    )
    .toBe(true);
  return link;
}
test("admin invites, resends, manages agent membership and revokes invitations", async ({
  page,
  browser,
  request,
}) => {
  const unique = Date.now().toString(36),
    slug = `team-${unique}`,
    email = `owner-${unique}@example.test`,
    agentEmail = `agent-${unique}@example.test`;
  await owner(request, email, slug);
  await login(page, email, slug);
  await page.getByRole("button", { name: "Team", exact: true }).click();
  const members = page.locator(".team-management > article").first();
  await expect(
    members.getByRole("row").filter({ hasText: email }),
  ).toContainText("Company admin");
  await expect(
    members.getByRole("button", { name: "Deactivate", exact: true }),
  ).not.toBeVisible();
  await invite(page, agentEmail);
  const old = await emailedLink(request, agentEmail);
  await page
    .getByRole("button", {
      name: `Resend invitation to ${agentEmail}`,
      exact: true,
    })
    .click();
  await expect(page.getByRole("dialog")).toContainText("previous link");
  await page.getByRole("button", { name: "Confirm", exact: true }).click();
  await expect(
    page.getByRole("status").filter({ hasText: "Invitation resent" }),
  ).toBeVisible();
  const link = await emailedLink(request, agentEmail, old);
  const agentContext = await browser.newContext(),
    agent = await agentContext.newPage();
  await agent.goto(old);
  await expect(
    agent.getByRole("heading", { name: "Invitation unavailable" }),
  ).toBeVisible();
  await agent.goto(link);
  await expect(
    agent.getByRole("heading", { name: `Join Studio ${slug}` }),
  ).toBeVisible();
  await expect(agent).not.toHaveURL(/#token=/);
  await expect(agent.locator(".invitation-details")).toContainText(agentEmail);
  await agent.screenshot({
    path: "../docs/invitation-acceptance.png",
    fullPage: true,
  });
  await agent.getByLabel("Full name", { exact: true }).fill("Taylor Support");
  await agent.getByLabel("Password", { exact: true }).fill(password);
  await agent.getByLabel("Confirm password").fill(password);
  await agent.getByRole("button", { name: "Create account and join" }).click();
  await expect(
    agent.getByRole("heading", { name: "You’re on the team" }),
  ).toBeVisible();
  await expect(agent).toHaveURL(`/workspace/${slug}`);
  await expect(
    agent.getByRole("heading", { name: "Workspace overview" }),
  ).toBeVisible();
  await expect(
    agent.getByRole("button", { name: "Team", exact: true }),
  ).not.toBeVisible();
  await expect(agent.locator(".sidebar-note")).toContainText("SUPPORT AGENT");
  await page.getByRole("button", { name: "Overview", exact: true }).click();
  await page.getByRole("button", { name: "Team", exact: true }).click();
  await expect(
    members.getByRole("row").filter({ hasText: agentEmail }),
  ).toContainText("Taylor Support");
  await page.getByLabel("Search team members").fill("Taylor");
  await expect(
    members.getByRole("row").filter({ hasText: email }),
  ).not.toBeVisible();
  await expect(
    members.getByRole("row").filter({ hasText: agentEmail }),
  ).toBeVisible();
  await page.getByLabel("Search team members").fill("");
  await members
    .getByRole("row")
    .filter({ hasText: agentEmail })
    .getByRole("button", { name: "Deactivate", exact: true })
    .click();
  await page.getByRole("button", { name: "Confirm", exact: true }).click();
  await expect(
    page.getByRole("status").filter({ hasText: "Member deactivated" }),
  ).toBeVisible();
  await agent.reload();
  await expect(agent.getByRole("status")).toContainText(
    "membership is inactive",
  );
  await expect(
    agent.getByRole("heading", { name: "Workspace overview" }),
  ).not.toBeVisible();
  await page.getByLabel("Filter by membership status").selectOption("INACTIVE");
  await expect(
    members.getByRole("row").filter({ hasText: agentEmail }),
  ).toBeVisible();
  await expect(
    members.getByRole("row").filter({ hasText: email }),
  ).not.toBeVisible();
  await members
    .getByRole("row")
    .filter({ hasText: agentEmail })
    .getByRole("button", { name: "Reactivate", exact: true })
    .click();
  await page.getByRole("button", { name: "Confirm", exact: true }).click();
  await expect(
    page.getByRole("status").filter({ hasText: "Member reactivated" }),
  ).toBeVisible();
  await page.getByLabel("Filter by membership status").selectOption("");
  await agent.goto(`/workspace/${slug}`);
  await expect(
    agent.getByRole("heading", { name: "Workspace overview" }),
  ).toBeVisible();
  await agent.goto(link);
  await expect(
    agent.getByRole("heading", { name: "Invitation unavailable" }),
  ).toBeVisible();
  await expect(agent.locator(".invitation-card")).toContainText(
    "already been accepted",
  );
  const revokedEmail = `revoked-${unique}@example.test`;
  await invite(page, revokedEmail);
  const revoked = await emailedLink(request, revokedEmail);
  await page
    .getByRole("button", { name: `Revoke invitation to ${revokedEmail}` })
    .click();
  await page.getByRole("button", { name: "Confirm", exact: true }).click();
  await expect(
    page.getByRole("status").filter({ hasText: "Invitation revoked" }),
  ).toBeVisible();
  await agent.goto(revoked);
  await expect(agent.locator(".invitation-card")).toContainText("was revoked");
  await page.getByRole("button", { name: "All", exact: true }).click();
  await page.evaluate(() => window.scrollTo(0, 0));
  await page.screenshot({ path: "../docs/team-desktop.png", fullPage: true });
  await page.setViewportSize({ width: 390, height: 844 });
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= window.innerWidth,
    ),
  ).toBe(true);
  await page.screenshot({ path: "../docs/team-mobile.png", fullPage: true });
  await agentContext.close();
});
test("existing user accepts into a second company without losing their original role", async ({
  page,
  browser,
  request,
}) => {
  const unique = Date.now().toString(36),
    alpha = `alpha-${unique}`,
    bravo = `bravo-${unique}`,
    adminEmail = `admin-${unique}@example.test`,
    existingEmail = `existing-${unique}@example.test`;
  await owner(request, adminEmail, alpha);
  await owner(request, existingEmail, bravo);
  await login(page, adminEmail, alpha);
  await page.getByRole("button", { name: "Team", exact: true }).click();
  await invite(page, existingEmail);
  const link = await emailedLink(request, existingEmail);
  const context = await browser.newContext(),
    existing = await context.newPage();
  await existing.goto(link);
  await expect(
    existing.getByRole("heading", { name: "Sign in to accept" }),
  ).toBeVisible();
  await existing.getByLabel("Password", { exact: true }).fill(password);
  await existing
    .getByRole("button", { name: "Sign in and accept invitation" })
    .click();
  await expect(existing).toHaveURL(`/workspace/${alpha}`);
  await expect(
    existing.getByRole("heading", { name: "Workspace overview" }),
  ).toBeVisible();
  await expect(
    existing.getByRole("button", { name: "Team", exact: true }),
  ).not.toBeVisible();
  await existing.getByLabel("Switch workspace").selectOption(bravo);
  await expect(existing).toHaveURL(`/workspace/${bravo}`);
  await expect(
    existing.getByRole("button", { name: "Team", exact: true }),
  ).toBeVisible();
  await context.close();
});
test("malformed invitation link shows a friendly state", async ({ page }) => {
  await page.goto("/invitations/accept#token=invalid");
  await expect(
    page.getByRole("heading", { name: "Invitation unavailable" }),
  ).toBeVisible();
  await expect(
    page.getByRole("link", { name: "Return to sign in" }),
  ).toBeVisible();
  await expect(page).not.toHaveURL(/#token=/);
});
