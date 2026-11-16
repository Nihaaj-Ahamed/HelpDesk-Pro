"use client";
import { useEffect, useRef, useState } from "react";
import Link from "next/link";
import { Check, Layers3, Mail, ShieldCheck, ArrowUpRight } from "lucide-react";
import { api, refresh, setAccess, Me } from "../../../components/api";
import Field from "../../../components/Field";
type Preview = {
  companyName: string;
  slug: string;
  email: string;
  role: string;
  expiresAt: string;
  accountExists: boolean;
};
export default function AcceptInvitation() {
  const [token, setToken] = useState(""),
    [details, setDetails] = useState<Preview>(),
    [me, setMe] = useState<Me | null>(null),
    [loading, setLoading] = useState(true),
    [error, setError] = useState(""),
    [invalid, setInvalid] = useState(""),
    [busy, setBusy] = useState(false),
    [accepted, setAccepted] = useState(false);
  const tokenRef = useRef("");
  const redirectRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  useEffect(() => {
    let live = true;
    // Email links opened in an existing tab can be same-document fragment
    // navigations. Reload so each distinct link gets a fresh validation.
    const reopen = () => window.location.reload();
    window.addEventListener("hashchange", reopen);
    if (!tokenRef.current) {
      tokenRef.current =
        new URLSearchParams(window.location.hash.slice(1)).get("token") || "";
      window.history.replaceState(null, "", window.location.pathname);
    }
    const raw = tokenRef.current;
    setToken(raw);
    (async () => {
      if (!/^[A-Za-z0-9_-]{43}$/.test(raw)) {
        setInvalid(
          raw
            ? "This invitation link is invalid. Open the complete link from your email, or ask your administrator for a new invitation."
            : "Open the complete invitation link from your email to continue.",
        );
        setLoading(false);
        return;
      }
      try {
        const d = await api<Preview>("/invitations/preview", { token: raw });
        if (!live) return;
        setDetails(d);
        if (await refresh()) {
          const profile = await api<Me>("/me");
          if (live) setMe(profile);
        }
      } catch (e) {
        if (live)
          setInvalid(
            e instanceof Error ? e.message : "Unable to open invitation.",
          );
      } finally {
        if (live) setLoading(false);
      }
    })();
    return () => {
      live = false;
      window.removeEventListener("hashchange", reopen);
      if (redirectRef.current) clearTimeout(redirectRef.current);
    };
  }, []);
  function success() {
    setAccepted(true);
    setError("");
    redirectRef.current = setTimeout(
      () => window.location.assign(`/workspace/${details!.slug}`),
      1600,
    );
  }
  async function accept() {
    setBusy(true);
    setError("");
    try {
      await api("/invitations/accept", { token });
      success();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Could not accept invitation.");
    } finally {
      setBusy(false);
    }
  }
  async function submit(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault();
    if (!details) return;
    setBusy(true);
    setError("");
    const data = Object.fromEntries(new FormData(e.currentTarget));
    if (!details.accountExists && data.password !== data.confirmPassword) {
      setError("Passwords do not match.");
      setBusy(false);
      return;
    }
    try {
      const session = await api<{ accessToken: string }>(
        details.accountExists ? "/auth/login" : "/auth/invitations/register",
        details.accountExists
          ? { email: details.email, password: data.password }
          : {
              token,
              fullName: data.fullName,
              password: data.password,
              confirmPassword: data.confirmPassword,
            },
      );
      setAccess(session.accessToken);
      if (details.accountExists) {
        await api("/invitations/accept", { token });
      }
      success();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Could not accept invitation.");
    } finally {
      setBusy(false);
    }
  }
  async function signOut() {
    setBusy(true);
    try {
      await api("/auth/logout", {});
      setAccess(null);
      setMe(null);
      setError("");
    } catch {
      setError("Could not sign out. Please try again.");
    } finally {
      setBusy(false);
    }
  }
  return (
    <main className="invitation-shell">
      <Link className="brand" href="/login">
        <span className="brand-icon">
          <Layers3 size={20} />
        </span>
        HelpDesk <b>Pro</b>
      </Link>
      <section className="invitation-card panel">
        {loading ? (
          <div className="empty" role="status">
            Checking your invitation…
          </div>
        ) : invalid ? (
          <div className="empty">
            <span className="empty-icon">
              <Mail />
            </span>
            <h1>Invitation unavailable</h1>
            <p>{invalid}</p>
            <Link className="subtle invitation-return" href="/login">
              Return to sign in
            </Link>
          </div>
        ) : accepted ? (
          <div className="empty">
            <span className="success-icon">
              <Check size={25} />
            </span>
            <h1>You’re on the team</h1>
            <p>
              Your Support Agent membership in {details?.companyName} is active.
              Opening your workspace…
            </p>
            <Link
              className="primary compact invitation-return"
              href={`/workspace/${details?.slug}`}
            >
              Open workspace <ArrowUpRight size={15} />
            </Link>
          </div>
        ) : (
          details && (
            <>
              <span className="pill">
                <ShieldCheck size={13} /> PRIVATE TEAM INVITATION
              </span>
              <h1>Join {details.companyName}</h1>
              <p className="muted invitation-intro">
                You’ve been invited to help your team deliver great customer
                support.
              </p>
              <dl className="invitation-details">
                <div>
                  <dt>Invited email</dt>
                  <dd>{details.email}</dd>
                </div>
                <div>
                  <dt>Company role</dt>
                  <dd>Support agent</dd>
                </div>
                <div>
                  <dt>Accept before</dt>
                  <dd>
                    {new Intl.DateTimeFormat("en", {
                      dateStyle: "medium",
                      timeStyle: "short",
                    }).format(new Date(details.expiresAt))}
                  </dd>
                </div>
              </dl>
              {error && (
                <div className="error" role="alert">
                  {error}
                </div>
              )}
              {me ? (
                me.email === details.email ? (
                  <>
                    <p className="invitation-instruction">
                      Signed in as <strong>{me.email}</strong>. Accept this
                      invitation to confirm your email ownership and join this
                      company.
                    </p>
                    <button
                      className="primary"
                      disabled={busy}
                      onClick={() => void accept()}
                    >
                      {busy ? "Joining…" : "Accept invitation"}
                      <ArrowUpRight size={16} />
                    </button>
                  </>
                ) : (
                  <>
                    <div className="error" role="alert">
                      You’re signed in as {me.email}. Sign out and use{" "}
                      {details.email} to accept this invitation.
                    </div>
                    <button
                      className="primary"
                      disabled={busy}
                      onClick={() => void signOut()}
                    >
                      Sign out and continue
                    </button>
                  </>
                )
              ) : (
                <>
                  <h2>
                    {details.accountExists
                      ? "Sign in to accept"
                      : "Create your agent account"}
                  </h2>
                  <p className="invitation-instruction">
                    {details.accountExists
                      ? "Enter the password for your existing account. Your other company memberships will stay the same."
                      : "Your email is fixed to this invitation. No company workspace will be created."}
                  </p>
                  <form onSubmit={submit}>
                    {!details.accountExists && (
                      <Field
                        label="Full name"
                        name="fullName"
                        required
                        autoComplete="name"
                        maxLength={100}
                        placeholder="Your full name"
                      />
                    )}
                    <Field
                      label="Password"
                      name="password"
                      type="password"
                      required
                      minLength={details.accountExists ? 1 : 12}
                      maxLength={72}
                      autoComplete={
                        details.accountExists
                          ? "current-password"
                          : "new-password"
                      }
                      placeholder={
                        details.accountExists
                          ? "Your password"
                          : "At least 12 characters"
                      }
                    />
                    {!details.accountExists && (
                      <Field
                        label="Confirm password"
                        name="confirmPassword"
                        type="password"
                        required
                        maxLength={72}
                        autoComplete="new-password"
                        placeholder="Repeat password"
                      />
                    )}
                    <button className="primary" disabled={busy}>
                      {busy
                        ? "Joining…"
                        : details.accountExists
                          ? "Sign in and accept invitation"
                          : "Create account and join"}
                      <ArrowUpRight size={16} />
                    </button>
                  </form>
                </>
              )}
            </>
          )
        )}
      </section>
      <p className="invitation-footnote">
        Your invitation link proves access to the invited email address.
        <br />
        Keep it private and accept only invitations you trust.
      </p>
    </main>
  );
}
