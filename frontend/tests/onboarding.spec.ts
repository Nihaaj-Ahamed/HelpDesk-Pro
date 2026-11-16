import { test, expect } from "@playwright/test";
test("owner registers, enters workspace, refreshes, signs out and signs back in on mobile", async ({
  page,
  browser,
}) => {
  const unique = Date.now().toString(36),
    email = `owner-${unique}@example.test`,
    slug = `studio-${unique}`;
  await page.goto("/register");
  await page.screenshot({
    path: "../docs/registration-desktop.png",
    fullPage: true,
  });
  await page.getByLabel("Owner full name").fill("Alex Morgan");
  await page.getByLabel("Email address").fill(email);
  await page.getByLabel("Company name").fill("Acme Studio");
  await page.getByLabel("Workspace slug").fill(slug);
  await page
    .getByLabel("Password", { exact: true })
    .fill("a-secure-password-123");
  await page.getByLabel("Confirm password").fill("a-secure-password-123");
  await page.getByRole("button", { name: "Create workspace" }).click();
  await expect(page).toHaveURL(`/workspace/${slug}`);
  await expect(
    page.getByRole("heading", { name: "Workspace overview" }),
  ).toBeVisible();
  await expect(
    page.getByRole("heading", { name: "No tickets yet" }),
  ).toBeVisible();
  await expect(
    page
      .locator(".metric")
      .filter({ hasText: "Company admins" })
      .locator("strong"),
  ).toHaveText("1");
  await expect(
    page
      .locator(".metric")
      .filter({ hasText: "Total tickets" })
      .locator("strong"),
  ).toHaveText("0");
  await expect(page.locator(".profile")).toContainText(email);
  await page.screenshot({
    path: "../docs/dashboard-desktop.png",
    fullPage: true,
  });
  await page.reload();
  await expect(
    page.getByRole("heading", { name: "Workspace overview" }),
  ).toBeVisible();
  await page.getByRole("button", { name: "Sign out", exact: true }).click();
  await expect(page).toHaveURL("/login");
  await page.goto(`/workspace/${slug}`);
  await expect(page).toHaveURL("/login");
  const mobile = await browser.newContext({
    viewport: { width: 390, height: 844 },
  });
  const phone = await mobile.newPage();
  await phone.goto("/login");
  await phone.getByLabel("Email address").fill(email);
  await phone
    .getByLabel("Password", { exact: true })
    .fill("incorrect-password");
  await phone.getByRole("button", { name: "Sign in", exact: true }).click();
  await expect(phone.locator('.error[role="alert"]')).toContainText(
    "Invalid credentials",
  );
  await phone
    .getByLabel("Password", { exact: true })
    .fill("a-secure-password-123");
  await phone.getByRole("button", { name: "Sign in", exact: true }).click();
  await expect(phone).toHaveURL(`/workspace/${slug}`);
  await expect(
    phone.getByRole("heading", { name: "Workspace overview" }),
  ).toBeVisible();
  expect(
    await phone.evaluate(
      () => document.documentElement.scrollWidth <= window.innerWidth,
    ),
  ).toBe(true);
  await phone.screenshot({
    path: "../docs/dashboard-mobile.png",
    fullPage: true,
  });
  await phone.getByRole("button", { name: "Toggle navigation" }).click();
  await phone.getByRole("button", { name: "Sign out", exact: true }).click();
  await expect(phone).toHaveURL("/login");
  await mobile.close();
});
test("anonymous workspace access redirects to sign in", async ({ page }) => {
  await page.goto("/workspace/private-company");
  await expect(page).toHaveURL("/login");
});
