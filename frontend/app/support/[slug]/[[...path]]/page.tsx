"use client";
import { use, useEffect, useRef, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import {
  Layers3,
  ArrowUpRight,
  Inbox,
  Mail,
  ShieldCheck,
  LogOut,
  Check,
} from "lucide-react";
import { api, refresh, setAccess, ApiError } from "../../../../components/api";
import {
  PortalInfo,
  Ticket,
  rememberTicketSubmission,
} from "../../../../components/tickets";
import { NotificationBell } from "../../../../components/Notifications";
import Field from "../../../../components/Field";
import TicketList from "../../../../components/TicketList";
import TicketDetail from "../../../../components/TicketDetail";
import CreateTicket from "../../../../components/CreateTicket";
import CustomerDashboard from "../../../../components/CustomerDashboard";
type Session = {
  fullName: string;
  email: string;
  status: "ACTIVE" | "VERIFY_REQUIRED" | "INACTIVE" | "STAFF";
};
export default function SupportPortal({
  params,
}: {
  params: Promise<{ slug: string; path?: string[] }>;
}) {
  const { slug, path = [] } = use(params),
    router = useRouter(),
    view = path[0] || "overview",
    base = `/support/${encodeURIComponent(slug)}`;
  const [info, setInfo] = useState<PortalInfo>(),
    [session, setSession] = useState<Session | null>(null),
    [loading, setLoading] = useState(true),
    [fatal, setFatal] = useState(""),
    [error, setError] = useState(""),
    [notice, setNotice] = useState(""),
    [busy, setBusy] = useState(false),
    [proof, setProof] = useState(""),
    [verified, setVerified] = useState(false);
  const proofRef = useRef({ slug: "", token: "" });
  useEffect(() => {
    let live = true;
    setLoading(true);
    setInfo(undefined);
    setSession(null);
    setFatal("");
    (async () => {
      try {
        const portal = await api<PortalInfo>(
          `/support/${encodeURIComponent(slug)}`,
        );
        if (!live) return;
        setInfo(portal);
        if (await refresh()) {
          const s = await api<Session>(
            `/support/${encodeURIComponent(slug)}/session`,
          );
          if (live) setSession(s);
        }
      } catch (e) {
        if (live)
          setFatal(
            e instanceof Error
              ? e.message
              : "Unable to open this support portal.",
          );
      } finally {
        if (live) setLoading(false);
      }
    })();
    return () => {
      live = false;
    };
  }, [slug]);
  useEffect(() => {
    if (view !== "verify") return;
    setVerified(false);
    const raw =
      new URLSearchParams(window.location.hash.slice(1)).get("token") || "";
    if (raw) proofRef.current = { slug, token: raw };
    setProof(proofRef.current.slug === slug ? proofRef.current.token : "");
    window.history.replaceState(null, "", window.location.pathname);
    const reopen = () => window.location.reload();
    window.addEventListener("hashchange", reopen);
    return () => window.removeEventListener("hashchange", reopen);
  }, [view, slug]);
  useEffect(() => {
    const blocked = (e: Event) => {
      const detail = (e as CustomEvent).detail;
      if (detail?.slug === slug) {
        setFatal(detail.message);
        setSession(null);
      }
    };
    window.addEventListener("helpdesk-tenant-blocked", blocked);
    return () => window.removeEventListener("helpdesk-tenant-blocked", blocked);
  }, [slug]);
  async function signOut() {
    setBusy(true);
    try {
      await api("/auth/logout", {});
      setAccess(null);
      setSession(null);
      setNotice("");
      setError("");
      router.replace(base);
    } catch {
      setError("Could not sign out. Please try again.");
    } finally {
      setBusy(false);
    }
  }
  async function sendProof() {
    setBusy(true);
    setError("");
    try {
      await api(`/support/${encodeURIComponent(slug)}/verification`, {});
      setNotice(
        "Verification email sent. Open the private link within 30 minutes. Any previous link has been replaced.",
      );
    } catch (e) {
      setError(
        e instanceof Error ? e.message : "Could not send verification email.",
      );
    } finally {
      setBusy(false);
    }
  }
  async function verify() {
    setBusy(true);
    setError("");
    try {
      await api(`/support/${encodeURIComponent(slug)}/verify`, {
        token: proof,
      });
      setSession(
        await api<Session>(`/support/${encodeURIComponent(slug)}/session`),
      );
      setVerified(true);
      setNotice("");
    } catch (e) {
      setError(e instanceof Error ? e.message : "Could not verify this email.");
    } finally {
      setBusy(false);
    }
  }
  async function authenticated(newAccount: boolean) {
    setSession(
      await api<Session>(`/support/${encodeURIComponent(slug)}/session`),
    );
    setError("");
    if (newAccount)
      setNotice(
        "Your account is ready. Check your email for a private verification link before opening tickets.",
      );
    if (view === "login" || view === "register") router.replace(base);
  }
  function ticketCreated(ticket: Ticket) {
    rememberTicketSubmission(ticket.id);
    router.push(`${base}/tickets/${ticket.id}`);
  }
  if (loading)
    return (
      <main className="loading">
        <span className="brand-icon">
          <Layers3 />
        </span>
        <p role="status">Opening support portal…</p>
      </main>
    );
  if (fatal || !info)
    return (
      <main className="portal-unavailable">
        <span className="brand-icon">
          <Layers3 />
        </span>
        <h1>Support portal unavailable</h1>
        <p role="alert">{fatal || "Portal not found."}</p>
        <Link className="subtle" href="/login">
          HelpDesk Pro sign in
        </Link>
      </main>
    );
  const active = session?.status === "ACTIVE";
  let content: React.ReactNode;
  if (view === "verify")
    content = (
      <article className="panel portal-auth-card">
        <span className="pill">
          <Mail size={13} /> EMAIL CONFIRMATION
        </span>
        <h1>
          {verified
            ? "You’re ready to get support"
            : "Confirm your customer access"}
        </h1>
        {verified ? (
          <>
            <div className="notice" role="status">
              <Check size={17} /> Your email is verified for {info.name}.
              Customer access is active.
            </div>
            <Link className="primary" href={base}>
              Open support dashboard <ArrowUpRight size={15} />
            </Link>
          </>
        ) : !proof || !/^[A-Za-z0-9_-]{43}$/.test(proof) ? (
          <>
            <div className="error" role="alert">
              This verification link is incomplete or invalid. Open the full
              link from your email, or request a new one from this portal.
            </div>
            <Link href={base} className="subtle invitation-return">
              Return to portal
            </Link>
          </>
        ) : !session ? (
          <>
            <p className="portal-auth-intro">
              Sign in to the account that received this email. Your private link
              confirms access to {info.name} support.
            </p>
            <PortalAuth
              slug={slug}
              register={false}
              onAuthenticated={authenticated}
            />
          </>
        ) : (
          <>
            <p className="portal-auth-intro">
              Signed in as <strong>{session.email}</strong>. Confirm this link
              to join {info.name} as a Customer. It will not grant staff access.
            </p>
            <button
              className="primary"
              disabled={busy}
              onClick={() => void verify()}
            >
              {busy ? "Confirming…" : "Confirm email and join"}
              <ArrowUpRight size={15} />
            </button>
          </>
        )}
      </article>
    );
  else if (
    view === "register" ||
    view === "login" ||
    (!session && view !== "overview")
  )
    content = (
      <article className="panel portal-auth-card">
        <span className="pill">{info.name} SUPPORT</span>
        <h1>
          {view === "register"
            ? "Create your customer account"
            : "Sign in for support"}
        </h1>
        <p className="portal-auth-intro">
          {view === "register"
            ? "Already use HelpDesk Pro? Sign in with your existing account instead."
            : "Use your HelpDesk Pro account to access this company’s support portal."}
        </p>
        {session ? (
          <>
            <p>You’re already signed in as {session.email}.</p>
            <Link className="primary" href={base}>
              Continue to portal
            </Link>
          </>
        ) : (
          <PortalAuth
            key={view}
            slug={slug}
            register={view === "register"}
            onAuthenticated={authenticated}
          />
        )}
        <p className="auth-switch">
          {view === "register"
            ? "Already have an account?"
            : "New to HelpDesk Pro?"}{" "}
          <Link href={`${base}/${view === "register" ? "login" : "register"}`}>
            {view === "register" ? "Sign in" : "Create an account"}
          </Link>
        </p>
      </article>
    );
  else if (!session)
    content = (
      <section className="portal-welcome">
        <span className="pill">
          <ShieldCheck size={13} /> CUSTOMER SUPPORT
        </span>
        <h1>How can we help?</h1>
        <p>
          Welcome to {info.name} support. Create a request and keep track of it
          in your own private support dashboard.
        </p>
        <div className="welcome-actions">
          <Link className="primary compact" href={`${base}/login`}>
            Sign in for support <ArrowUpRight size={15} />
          </Link>
          <Link className="subtle" href={`${base}/register`}>
            Create an account
          </Link>
        </div>
        <div className="portal-benefits">
          <article className="panel">
            <Inbox size={20} />
            <h2>A clear place for every request</h2>
            <p>
              Share a detailed description and find your ticket again using its
              reference.
            </p>
          </article>
          <article className="panel">
            <ShieldCheck size={20} />
            <h2>Your requests stay yours</h2>
            <p>
              Sign in and verify your email before accessing your tickets with{" "}
              {info.name}.
            </p>
          </article>
        </div>
      </section>
    );
  else if (session.status === "STAFF")
    content = (
      <article className="panel portal-auth-card">
        <span className="pill">STAFF ACCOUNT</span>
        <h1>Your company workspace is ready</h1>
        <p className="portal-auth-intro">
          You’re a staff member of {info.name}. View company tickets in your
          authorized workspace.
        </p>
        <Link className="primary" href={`/workspace/${slug}`}>
          Open workspace <ArrowUpRight size={15} />
        </Link>
      </article>
    );
  else if (session.status === "INACTIVE")
    content = (
      <article className="panel portal-auth-card">
        <h1>Support access is inactive</h1>
        <p className="portal-auth-intro">
          Contact {info.name} to restore your access. Your account and
          memberships in other companies are unchanged.
        </p>
      </article>
    );
  else if (!active)
    content = (
      <article className="panel portal-auth-card">
        <span className="empty-icon">
          <Mail size={24} />
        </span>
        <h1>Verify your email to continue</h1>
        <p className="portal-auth-intro">
          You’re signed in as <strong>{session.email}</strong>. Use the private
          email link to confirm customer access to {info.name} before submitting
          or viewing tickets.
        </p>
        <button
          className="primary"
          disabled={busy}
          onClick={() => void sendProof()}
        >
          {busy ? "Sending…" : "Send verification email"}
          <ArrowUpRight size={15} />
        </button>
        <small className="verification-note">
          Links expire after 30 minutes. Requesting a new email replaces the old
          link.
        </small>
      </article>
    );
  else if (view === "new")
    content = <CreateTicket info={info} onCreated={ticketCreated} />;
  else if (view === "tickets" && path[1])
    content = <TicketDetail slug={slug} id={path[1]} />;
  else if (view === "tickets")
    content = (
      <>
        <div className="portal-page-heading">
          <div>
            <span className="eyebrow">SUPPORT, AT A GLANCE</span>
            <h1>My tickets</h1>
            <p>Track the requests you have submitted to {info.name}.</p>
          </div>
          <Link className="primary compact" href={`${base}/new`}>
            Create Ticket <ArrowUpRight size={15} />
          </Link>
        </div>
        <TicketList slug={slug} />
      </>
    );
  else if (view === "overview")
    content = <CustomerDashboard slug={slug} name={session.fullName} />;
  else
    content = (
      <article className="panel">
        <div className="empty">
          <h1>Page not found</h1>
          <Link className="subtle invitation-return" href={base}>
            Return to support
          </Link>
        </div>
      </article>
    );
  return (
    <div className="support-portal">
      <header className="portal-header">
        <Link href={base} className="portal-brand">
          <span className="brand-icon">
            <Layers3 size={20} />
          </span>
          <span>
            <strong>{info.name}</strong>
            <small>Customer support</small>
          </span>
        </Link>
        <div className="portal-header-right">
          {session ? (
            <>
              {active && <NotificationBell slug={slug} />}
              <span className="portal-user">{session.fullName}</span>
              <span className="avatar">{session.fullName.charAt(0)}</span>
              <button
                className="icon-button"
                aria-label="Sign out"
                onClick={() => void signOut()}
                disabled={busy}
              >
                <LogOut size={16} />
              </button>
            </>
          ) : (
            <Link className="subtle" href={`${base}/login`}>
              Sign in
            </Link>
          )}
        </div>
      </header>
      {active && (
        <nav className="portal-nav" aria-label="Support navigation">
          <Link className={view === "overview" ? "selected" : ""} href={base}>
            Overview
          </Link>
          <Link
            className={view === "tickets" ? "selected" : ""}
            href={`${base}/tickets`}
          >
            My Tickets
          </Link>
          <Link
            className={view === "new" ? "selected" : ""}
            href={`${base}/new`}
          >
            Create Ticket
          </Link>
        </nav>
      )}
      <main className="portal-content">
        {notice && (
          <div className="notice" role="status">
            {notice}
            <button
              className="icon-button"
              aria-label="Dismiss notification"
              onClick={() => setNotice("")}
            >
              ×
            </button>
          </div>
        )}
        {error && (
          <div className="error" role="alert">
            {error}
          </div>
        )}
        {content}
      </main>
      <footer className="portal-footer">
        <span>HelpDesk Pro</span>
        <span>{info.name} · Customer support</span>
      </footer>
    </div>
  );
}
function PortalAuth({
  slug,
  register,
  onAuthenticated,
}: {
  slug: string;
  register: boolean;
  onAuthenticated: (registered: boolean) => Promise<void>;
}) {
  const [busy, setBusy] = useState(false),
    [error, setError] = useState(""),
    [fields, setFields] = useState<Record<string, string>>({});
  async function submit(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault();
    if (busy) return;
    setBusy(true);
    setError("");
    setFields({});
    const data = Object.fromEntries(new FormData(e.currentTarget));
    if (register && data.password !== data.confirmPassword) {
      setError("Passwords do not match.");
      setBusy(false);
      return;
    }
    try {
      const response = await api<{ accessToken: string }>(
        register
          ? `/auth/support/${encodeURIComponent(slug)}/register`
          : "/auth/login",
        data,
      );
      setAccess(response.accessToken);
      await onAuthenticated(register);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Could not sign in.");
      if (e instanceof ApiError) setFields(e.fields);
    } finally {
      setBusy(false);
    }
  }
  return (
    <form onSubmit={submit}>
      {register && (
        <Field
          label="Full name"
          name="fullName"
          required
          autoComplete="name"
          maxLength={100}
          placeholder="Your full name"
          error={fields.fullName}
        />
      )}
      <Field
        label="Email address"
        name="email"
        type="email"
        required
        autoComplete="email"
        maxLength={254}
        placeholder="you@example.com"
        error={fields.email}
      />
      <Field
        label="Password"
        name="password"
        type="password"
        required
        autoComplete={register ? "new-password" : "current-password"}
        minLength={register ? 12 : 1}
        maxLength={72}
        placeholder={register ? "At least 12 characters" : "Your password"}
        error={fields.password}
      />
      {register && (
        <Field
          label="Confirm password"
          name="confirmPassword"
          type="password"
          required
          autoComplete="new-password"
          maxLength={72}
          placeholder="Repeat password"
          error={fields.confirmPassword}
        />
      )}{" "}
      {error && (
        <div className="error" role="alert">
          {error}
        </div>
      )}
      <button className="primary" disabled={busy}>
        {busy
          ? "Please wait…"
          : register
            ? "Create customer account"
            : "Sign in"}
        <ArrowUpRight size={15} />
      </button>
    </form>
  );
}
