const base = process.env.NEXT_PUBLIC_API_URL || "http://localhost:8080";
let access: string | null = null;
let refreshPromise: Promise<boolean> | null = null;
export class ApiError extends Error {
  constructor(
    message: string,
    public status: number,
    public fields: Record<string, string> = {},
  ) {
    super(message);
  }
}
export function setAccess(token: string | null) {
  access = token;
}
export async function refresh() {
  if (!refreshPromise) {
    const rotate = () =>
      fetch(`${base}/api/auth/refresh`, {
        method: "POST",
        credentials: "include",
      })
        .then(async (r) => {
          access = r.ok ? (await r.json()).accessToken : null;
          return r.ok;
        })
        .catch(() => {
          access = null;
          return false;
        });
    // Serialize refreshes across tabs, including the browser's cookie update.
    refreshPromise = (async () => {
      if (typeof navigator !== "undefined" && navigator.locks) {
        return await navigator.locks.request("helpdesk-refresh", rotate);
      }
      return await rotate();
    })().finally(() => {
      refreshPromise = null;
    });
  }
  return refreshPromise;
}
export async function api<T>(
  path: string,
  body?: unknown,
  retry = true,
): Promise<T> {
  const r = await fetch(`${base}/api${path}`, {
    method: body === undefined ? "GET" : "POST",
    credentials: "include",
    headers: {
      ...(body === undefined ? {} : { "Content-Type": "application/json" }),
      ...(access && !path.startsWith("/auth/")
        ? { Authorization: `Bearer ${access}` }
        : {}),
    },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
  });
  if (
    r.status === 401 &&
    retry &&
    !path.startsWith("/auth/") &&
    (await refresh())
  )
    return api(path, body, false);
  if (!r.ok) {
    const data = await r.json().catch(() => ({}));
    if (r.status === 423 && typeof window !== "undefined") {
      const match = path.match(/^\/(?:workspaces|support|companies)\/([^/]+)/);
      if (match)
        window.dispatchEvent(
          new CustomEvent("helpdesk-tenant-blocked", {
            detail: {
              slug: decodeURIComponent(match[1]),
              message: data.detail || "Company is suspended.",
            },
          }),
        );
    }
    throw new ApiError(
      data.detail ||
        data.title ||
        "Could not complete your request. Please try again.",
      r.status,
      data.fields,
    );
  }
  const text = await r.text();
  return text ? (JSON.parse(text) as T) : (undefined as T);
}
export type Me = {
  platformAdmin: boolean;
  fullName: string;
  email: string;
  workspaces: { name: string; slug: string; role: string }[];
  portals: { name: string; slug: string }[];
};
export type Workspace = {
  name: string;
  slug: string;
  role: string;
  assignedTickets: number;
  inProgressTickets: number;
  resolvedTickets: number;
  closedTickets: number;
  unassignedTickets: number;
  myTickets: number;
  unreadMessages: number;
  totalTickets: number;
  openTickets: number;
  agents: number;
  admins: number;
};

export function conversationSocketUrl() {
  return base.replace(/^http/, "ws") + "/ws/conversations";
}

export async function downloadCsv(
  path: string,
  filename: string,
  retry = true,
): Promise<void> {
  const response = await fetch(`${base}/api${path}`, {
    credentials: "include",
    headers: access ? { Authorization: `Bearer ${access}` } : {},
  });
  if (response.status === 401 && retry && (await refresh()))
    return downloadCsv(path, filename, false);
  if (!response.ok) {
    const problem = await response.json().catch(() => ({}));
    throw new Error(problem.detail || "Export unavailable.");
  }
  const url = URL.createObjectURL(await response.blob());
  const a = document.createElement("a");
  a.href = url;
  a.download = filename;
  a.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
