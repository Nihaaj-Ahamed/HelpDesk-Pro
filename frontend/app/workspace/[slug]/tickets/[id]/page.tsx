"use client";
import { use, useEffect, useState } from "react";
import Link from "next/link";
import { Layers3, ArrowLeft } from "lucide-react";
import { api, refresh, Workspace, Me } from "../../../../../components/api";
import { NotificationBell } from "../../../../../components/Notifications";
import TicketDetail from "../../../../../components/TicketDetail";
export default function StaffTicketPage({
  params,
}: {
  params: Promise<{ slug: string; id: string }>;
}) {
  const { slug, id } = use(params);
  const [workspace, setWorkspace] = useState<Workspace>(),
    [me, setMe] = useState<Me>(),
    [error, setError] = useState("");
  useEffect(() => {
    let live = true;
    (async () => {
      try {
        if (!(await refresh())) {
          window.location.replace("/login");
          return;
        }
        const [w, m] = await Promise.all([
          api<Workspace>(`/workspaces/${encodeURIComponent(slug)}`),
          api<Me>("/me"),
        ]);
        if (live) {
          setWorkspace(w);
          setMe(m);
        }
      } catch (e) {
        if (live)
          setError(e instanceof Error ? e.message : "Workspace unavailable.");
      }
    })();
    return () => {
      live = false;
    };
  }, [slug]);
  return (
    <div className="staff-ticket-page">
      <header className="portal-header">
        <Link className="portal-brand" href={`/workspace/${slug}?view=tickets`}>
          <span className="brand-icon">
            <Layers3 size={20} />
          </span>
          <span>
            <strong>{workspace?.name || "HelpDesk Pro"}</strong>
            <small>Company workspace</small>
          </span>
        </Link>
        {workspace && <NotificationBell slug={slug} />}
        <span className="muted">{me?.fullName}</span>
      </header>
      <main className="staff-ticket-content">
        {error ? (
          <article className="panel">
            <div className="empty">
              <h1>Workspace unavailable</h1>
              <p role="alert">{error}</p>
              <Link className="subtle invitation-return" href="/workspaces">
                <ArrowLeft size={14} /> Your workspaces
              </Link>
            </div>
          </article>
        ) : workspace ? (
          <TicketDetail slug={slug} id={id} staff />
        ) : (
          <div className="team-state" role="status">
            Checking workspace access…
          </div>
        )}
      </main>
    </div>
  );
}
