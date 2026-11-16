"use client";
import { useEffect, useState } from "react";
import Link from "next/link";
import { Layers3, ArrowUpRight } from "lucide-react";
import { api, refresh, setAccess, Me } from "../../components/api";
export default function Workspaces() {
  const [me, setMe] = useState<Me>(),
    [error, setError] = useState("");
  useEffect(() => {
    (async () => {
      if (!(await refresh())) {
        window.location.replace("/login");
        return;
      }
      try {
        setMe(await api<Me>("/me"));
      } catch (e) {
        setError(e instanceof Error ? e.message : "Unable to load workspaces.");
      }
    })();
  }, []);
  async function logout() {
    try {
      await api("/auth/logout", {});
      setAccess(null);
      window.location.replace("/login");
    } catch {
      setError("Could not sign out. Please try again.");
    }
  }
  return (
    <main className="invitation-shell">
      <div className="brand">
        <span className="brand-icon">
          <Layers3 size={20} />
        </span>
        HelpDesk <b>Pro</b>
      </div>
      <section className="invitation-card panel">
        <h1>Your workspaces</h1>
        {error && (
          <p className="error" role="alert">
            {error}
          </p>
        )}
        {!me ? (
          <p role="status">Loading your memberships…</p>
        ) : me.workspaces.length || me.portals?.length ? (
          <div className="workspace-list">
            {me.workspaces.map((w) => (
              <Link
                key={w.slug}
                className="workspace-link"
                href={`/workspace/${w.slug}`}
              >
                <span>
                  {w.name}
                  <small>
                    {w.role === "COMPANY_ADMIN"
                      ? "Company admin"
                      : "Support agent"}
                  </small>
                </span>
                <ArrowUpRight size={16} />
              </Link>
            ))}
            {me.portals?.map((p) => (
              <Link
                key={`portal-${p.slug}`}
                className="workspace-link"
                href={`/support/${p.slug}`}
              >
                <span>
                  {p.name}
                  <small>Customer support portal</small>
                </span>
                <ArrowUpRight size={16} />
              </Link>
            ))}
          </div>
        ) : (
          <div className="empty">
            <h2>No active workspace memberships</h2>
            <p>
              Your account is still available. Contact your company
              administrator to reactivate access, or open a new invitation from
              your email.
            </p>
          </div>
        )}
        <button
          className="subtle invitation-return"
          onClick={() => void logout()}
        >
          Sign out
        </button>
      </section>
    </main>
  );
}
