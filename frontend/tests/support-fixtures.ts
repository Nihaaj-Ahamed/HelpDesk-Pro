import { expect, APIRequestContext, Page } from "@playwright/test";
const api = process.env.NEXT_PUBLIC_API_URL || "http://localhost:8080",
  mail = process.env.MAILPIT_URL || "http://localhost:8025",
  password = "a-secure-password-123";
const headers = { Origin: "http://localhost:3000" };
async function owner(request: APIRequestContext, slug: string) {
  const email = `owner-${slug}@example.test`;
  const response = await request.post(`${api}/api/auth/register`, {
    timeout: 60000,
    headers,
    data: {
      fullName: "Alex Company",
      email,
      password,
      confirmPassword: password,
      companyName: `Studio ${slug}`,
      slug,
    },
  });
  expect(response.status()).toBe(201);
  return { email, token: (await response.json()).accessToken };
}
async function emailLink(
  request: APIRequestContext,
  email: string,
  path: string,
) {
  let link = "";
  await expect
    .poll(
      async () => {
        const list = await (
          await request.get(`${mail}/api/v1/messages`)
        ).json();
        for (const item of list.messages) {
          if (!item.To?.some((p: { Address: string }) => p.Address === email))
            continue;
          const message = await (
            await request.get(`${mail}/api/v1/message/${item.ID}`)
          ).json();
          const candidate = message.Text.split(/\s+/).find((value: string) =>
            value.startsWith(`http://localhost:3000${path}#token=`),
          );
          if (candidate) {
            link = candidate;
            return true;
          }
        }
        return false;
      },
      { timeout: 15000, message: "Private email link is delivered to Mailpit" },
    )
    .toBe(true);
  return link;
}
async function verifiedCustomer(
  request: APIRequestContext,
  slug: string,
  email: string,
) {
  const registered = await request.post(
    `${api}/api/auth/support/${slug}/register`,
    {
      timeout: 60000,
      headers,
      data: {
        fullName: "Fixture Customer",
        email,
        password,
        confirmPassword: password,
      },
    },
  );
  expect(registered.status()).toBe(201);
  const token = (await registered.json()).accessToken;
  const link = await emailLink(request, email, `/support/${slug}/verify`);
  const proof = new URLSearchParams(new URL(link).hash.slice(1)).get("token");
  expect(
    (
      await request.post(`${api}/api/support/${slug}/verify`, {
        headers: { ...headers, Authorization: `Bearer ${token}` },
        data: { token: proof },
      })
    ).status(),
  ).toBe(204);
  return token;
}
async function portalLogin(page: Page, slug: string, email: string) {
  await page.goto(`/support/${slug}/login`);
  await page.getByLabel("Email address").fill(email);
  await page.getByLabel("Password", { exact: true }).fill(password);
  await page.getByRole("button", { name: "Sign in", exact: true }).click();
  await expect(page).toHaveURL(`/support/${slug}`);
  // Wait for the new route's session bootstrap before a full document navigation.
  await expect(
    page.getByRole("heading", {
      name: /^(Your support overview|Verify your email to continue)$/,
    }),
  ).toBeVisible();
}

export {
  api,
  mail,
  password,
  headers,
  owner,
  emailLink,
  verifiedCustomer,
  portalLogin,
};
