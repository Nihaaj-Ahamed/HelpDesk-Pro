"use client";
import { useState } from "react";
import Link from "next/link";
import { ArrowUpRight, Check, Layers3, ShieldCheck } from "lucide-react";
import { api, setAccess, Me, ApiError } from "./api";
import Field from "./Field";
export default function AuthForm({ register = false }: { register?: boolean }) {
  const [error, setError] = useState(""),
    [busy, setBusy] = useState(false),
    [slug, setSlug] = useState(""),
    [fields, setFields] = useState<Record<string, string>>({});
  async function submit(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault();
    setError("");
    setFields({});
    setBusy(true);
    const data = Object.fromEntries(new FormData(e.currentTarget));
    if (register && data.password !== data.confirmPassword) {
      setError("Passwords do not match.");
      setFields({ confirmPassword: "Passwords do not match." });
      setBusy(false);
      return;
    }
    if (
      register &&
      new TextEncoder().encode(String(data.password)).length > 72
    ) {
      setError("Password must be at most 72 UTF-8 bytes.");
      setBusy(false);
      return;
    }
    try {
      const r = await api<{ accessToken: string }>(
        `/auth/${register ? "register" : "login"}`,
        data,
      );
      setAccess(r.accessToken);
      const me = await api<Me>("/me");
      if (me.platformAdmin) {
        window.location.assign("/platform");
        return;
      }
      if (!me.workspaces.length) {
        window.location.assign(
          me.portals?.length ? `/support/${me.portals[0].slug}` : "/workspaces",
        );
        return;
      }
      window.location.assign(`/workspace/${me.workspaces[0].slug}`);
    } catch (e) {
      if (e instanceof ApiError) setFields(e.fields);
      setError(e instanceof Error ? e.message : "Unable to connect.");
      setBusy(false);
    }
  }
  return (
    <main className="auth-shell">
      <section className="auth-story">
        <Link className="brand" href="/">
          <span className="brand-icon">
            <Layers3 size={20} />
          </span>
          HelpDesk <b>Pro</b>
        </Link>
        <div>
          <span className="eyebrow">GOOD SUPPORT STARTS HERE</span>
          <h1>
            A little more focus.
            <br />A lot better support.
          </h1>
          <p>
            One workspace for your team.
            <br />A better experience for every customer.
          </p>
          <div className="story-features">
            {[
              "Your company, your workspace",
              "A secure foundation for your team",
              "Clarity from the very first ticket",
            ].map((x) => (
              <p key={x}>
                <Check size={16} />
                {x}
              </p>
            ))}
          </div>
        </div>
        <small>Built for teams who care.</small>
      </section>
      <section className="auth-main">
        <div className="auth-card">
          <span className="pill">
            <ShieldCheck size={13} /> YOUR WORKSPACE, PROTECTED
          </span>
          <h2>{register ? "Create your workspace" : "Welcome back"}</h2>
          <p className="muted">
            {register
              ? "Get your company set up in a few simple steps."
              : "Sign in to your company workspace."}
          </p>
          <form onSubmit={submit}>
            {register && (
              <Field
                label="Owner full name"
                error={fields.fullName}
                name="fullName"
                autoComplete="name"
                required
                maxLength={100}
                placeholder="Alex Morgan"
              />
            )}
            <Field
              label="Email address"
              error={fields.email}
              name="email"
              type="email"
              autoComplete="email"
              required
              maxLength={254}
              placeholder="you@company.com"
            />
            {register && (
              <>
                <Field
                  label="Company name"
                  error={fields.companyName}
                  name="companyName"
                  required
                  maxLength={120}
                  placeholder="Acme Studio"
                />
                <Field
                  label="Workspace slug"
                  error={fields.slug}
                  hint="3–48 lowercase letters, numbers or hyphens. This is your workspace address."
                  name="slug"
                  required
                  minLength={3}
                  maxLength={48}
                  pattern="[a-z0-9]+(-[a-z0-9]+)*"
                  placeholder="acme-studio"
                  value={slug}
                  onChange={(e) => setSlug(e.target.value.toLowerCase())}
                />
              </>
            )}
            <div className={register ? "form-row" : ""}>
              <Field
                label="Password"
                error={fields.password}
                name="password"
                type="password"
                autoComplete={register ? "new-password" : "current-password"}
                required
                minLength={register ? 12 : 1}
                maxLength={72}
                placeholder={
                  register ? "At least 12 characters" : "Enter your password"
                }
              />
              {register && (
                <Field
                  label="Confirm password"
                  error={fields.confirmPassword}
                  name="confirmPassword"
                  type="password"
                  autoComplete="new-password"
                  required
                  maxLength={72}
                  placeholder="Repeat password"
                />
              )}
            </div>
            {error && (
              <div role="alert" className="error">
                {error}
              </div>
            )}
            <button className="primary" disabled={busy}>
              {busy
                ? "Please wait…"
                : register
                  ? "Create workspace"
                  : "Sign in"}
              <ArrowUpRight size={16} />
            </button>
          </form>
          <p className="auth-switch">
            {register ? "Already have a workspace?" : "New to HelpDesk Pro?"}{" "}
            <Link href={register ? "/login" : "/register"}>
              {register ? "Sign in" : "Create a workspace"}
            </Link>
          </p>
        </div>
        <small className="auth-footer">
          HelpDesk Pro · A calmer way to support
        </small>
      </section>
    </main>
  );
}
