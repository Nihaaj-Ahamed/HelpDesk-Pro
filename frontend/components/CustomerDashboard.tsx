"use client";
import { useEffect, useState } from "react";
import Link from "next/link";
import { ArrowUpRight, CheckCheck, Inbox, Ticket, Clock3 } from "lucide-react";
import { api } from "./api";
import TicketList from "./TicketList";
import { TicketDashboard, ticketDate } from "./tickets";
export default function CustomerDashboard({
  slug,
  name,
}: {
  slug: string;
  name: string;
}) {
  const [data, setData] = useState<TicketDashboard>(),
    [error, setError] = useState("");
  useEffect(() => {
    let live = true;
    const update = () =>
      api<TicketDashboard>(`/support/${encodeURIComponent(slug)}/dashboard`)
        .then((r) => {
          if (live) {
            setData(r);
            setError("");
          }
        })
        .catch((e) => {
          if (live)
            setError(
              e instanceof Error ? e.message : "Could not load your dashboard.",
            );
        });
    void update();
    const timer = setInterval(() => {
      if (document.visibilityState === "visible") void update();
    }, 20000);
    return () => {
      clearInterval(timer);
      live = false;
    };
  }, [slug]);
  return (
    <>
      <div className="portal-page-heading">
        <div>
          <span className="eyebrow">YOUR REQUESTS, IN FOCUS</span>
          <h1>Your support overview</h1>
          <p>
            Welcome back, {name.split(" ")[0]}. Keep track of your support
            requests.
          </p>
        </div>
        <Link className="primary compact" href={`/support/${slug}/new`}>
          Create Ticket <ArrowUpRight size={15} />
        </Link>
      </div>
      {error ? (
        <div className="error" role="alert">
          {error}
        </div>
      ) : !data ? (
        <div className="team-state" role="status">
          Loading your overview…
        </div>
      ) : (
        <>
          <div className="metrics customer-metrics">
            {[
              {
                title: "Submitted tickets",
                value: data.counts.total,
                icon: Ticket,
                note: "All your support requests",
              },
              {
                title: "Open tickets",
                value: data.counts.open,
                icon: Inbox,
                note: "Requests awaiting resolution",
              },
              {
                title: "Resolved tickets",
                value: data.counts.resolved,
                icon: CheckCheck,
                note: "Requests marked as resolved",
              },
              {
                title: "Closed tickets",
                value: data.counts.closed,
                icon: CheckCheck,
                note: "Completed support requests",
              },
            ].map(({ title, value, icon: Icon, note }) => (
              <article className="metric" key={title}>
                <div className="metric-label">
                  {title}
                  <Icon size={15} />
                </div>
                <strong>{value}</strong>
                <small>{note}</small>
              </article>
            ))}
          </div>
          <p className="unread-summary" aria-live="polite">
            {data.counts.unread} unread conversation messages
          </p>
          <article className="panel portal-activity">
            <div className="panel-heading">
              <div>
                <h2>Recent activity</h2>
                <p>Your latest ticket submissions</p>
              </div>
              <Clock3 size={16} />
            </div>
            {data.recent.length ? (
              <ul className="activity-list">
                {data.recent.map((t) => (
                  <li key={t.id}>
                    <span className="activity-dot" />
                    <div>
                      <Link href={`/support/${slug}/tickets/${t.id}`}>
                        Submitted {t.reference} · {t.subject}
                      </Link>
                      <small>{ticketDate(t.createdAt, true)}</small>
                    </div>
                  </li>
                ))}
              </ul>
            ) : (
              <div className="activity-empty">
                No ticket activity yet. Your submissions will appear here.
              </div>
            )}
          </article>
        </>
      )}
      <TicketList slug={slug} recent />
    </>
  );
}
