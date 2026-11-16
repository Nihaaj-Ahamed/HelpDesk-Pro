"use client";
import { useEffect, useState } from "react";
import Link from "next/link";
import {
  Inbox,
  Search,
  ArrowUpRight,
  ChevronLeft,
  ChevronRight,
} from "lucide-react";
import { api, Me } from "./api";
import {
  Ticket,
  TicketPage,
  PortalInfo,
  statuses,
  ticketLabel,
  ticketDate,
} from "./tickets";
export function TicketTable({
  tickets,
  detailBase,
  staff = false,
  onClaim,
}: {
  tickets: Ticket[];
  detailBase: string;
  staff?: boolean;
  onClaim?: (ticket: Ticket) => void;
}) {
  return (
    <div className="table-scroll">
      <table className="ticket-table">
        <caption className="sr-only">
          {staff ? "Company tickets" : "Your support tickets"}
        </caption>
        <thead>
          <tr>
            {[
              "Reference",
              "Subject",
              ...(staff ? ["Customer"] : []),
              "Status",
              "Priority",
              "SLA",
              "Assigned to",
              "Created",
              "Updated",
              ...(onClaim ? ["Actions"] : []),
            ].map((x) => (
              <th key={x} scope="col">
                {x}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {tickets.map((t) => (
            <tr key={t.id}>
              <td>
                <Link className="reference-link" href={`${detailBase}/${t.id}`}>
                  {t.reference}
                  {t.unreadCount > 0 && (
                    <span
                      className="unread-badge"
                      aria-label={`${t.unreadCount} unread messages`}
                    >
                      {t.unreadCount} unread
                    </span>
                  )}
                </Link>
              </td>
              <td className="ticket-subject">
                <Link href={`${detailBase}/${t.id}`}>{t.subject}</Link>
                <small>{t.category || "Not categorized"}</small>
              </td>
              {staff && (
                <td>
                  <strong>{t.customer?.name || "Not recorded"}</strong>
                  <small>{t.customer?.email || "Legacy record"}</small>
                </td>
              )}
              <td>
                <span className={`ticket-status ${t.status.toLowerCase()}`}>
                  {ticketLabel(t.status)}
                </span>
              </td>
              <td>
                <span className={`ticket-priority ${t.priority.toLowerCase()}`}>
                  <i />
                  {ticketLabel(t.priority)}
                </span>
              </td>
              <td>
                <span
                  className={`sla-chip ${(t.slaState || "UNTRACKED").toLowerCase()}`}
                >
                  {ticketLabel(t.slaState || "UNTRACKED")}
                </span>
              </td>
              <td>
                {t.assignedAgent?.name || (
                  <span className="unassigned">Unassigned</span>
                )}
              </td>
              <td>
                <time dateTime={t.createdAt || undefined}>
                  {ticketDate(t.createdAt)}
                </time>
              </td>
              <td>{ticketDate(t.updatedAt)}</td>
              {onClaim && (
                <td>
                  {t.status === "OPEN" && !t.assignedAgent && (
                    <button className="subtle" onClick={() => onClaim(t)}>
                      Assign to Me
                    </button>
                  )}
                </td>
              )}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
export default function TicketList({
  slug,
  staff = false,
  recent = false,
  initialQueue = "ALL",
}: {
  slug: string;
  staff?: boolean;
  recent?: boolean;
  initialQueue?: string;
}) {
  const [data, setData] = useState<TicketPage>(),
    [info, setInfo] = useState<PortalInfo>(),
    [queue, setQueue] = useState(initialQueue),
    [search, setSearch] = useState(""),
    [status, setStatus] = useState(""),
    [priority, setPriority] = useState(""),
    [category, setCategory] = useState(""),
    [page, setPage] = useState(0),
    [loading, setLoading] = useState(true),
    [error, setError] = useState(""),
    [version, setVersion] = useState(0);
  const [agentRole, setAgentRole] = useState(false);
  const [claimBusy, setClaimBusy] = useState(false);
  useEffect(() => {
    if (staff)
      void api<Me>("/me")
        .then((m) =>
          setAgentRole(
            m.workspaces.some(
              (w) => w.slug === slug && w.role === "SUPPORT_AGENT",
            ),
          ),
        )
        .catch(() => {});
  }, [staff, slug]);
  const endpoint = `/${staff ? "workspaces" : "support"}/${encodeURIComponent(slug)}/tickets`,
    detailBase = `/${staff ? "workspace" : "support"}/${slug}/tickets`;
  useEffect(() => {
    let live = true;
    void api<PortalInfo>(`/support/${encodeURIComponent(slug)}`)
      .then((r) => {
        if (live) setInfo(r);
      })
      .catch(() => {});
    return () => {
      live = false;
    };
  }, [slug]);
  useEffect(() => {
    let live = true;
    setLoading(true);
    const timer = setTimeout(
      () => {
        const q = new URLSearchParams({
          search,
          ...(staff ? { queue } : {}),
          status,
          priority,
          category,
          page: String(page),
          size: recent ? "5" : "20",
        });
        if (!category) q.delete("category");
        api<TicketPage>(`${endpoint}?${q}`)
          .then((r) => {
            if (live) {
              setData(r);
              setError("");
            }
          })
          .catch((e) => {
            if (live) {
              setData(undefined);
              setError(
                e instanceof Error ? e.message : "Could not load tickets.",
              );
            }
          })
          .finally(() => {
            if (live) setLoading(false);
          });
      },
      recent ? 0 : 200,
    );
    return () => {
      live = false;
      clearTimeout(timer);
    };
  }, [
    endpoint,
    search,
    status,
    priority,
    category,
    page,
    recent,
    version,
    queue,
    staff,
  ]);
  async function claim(t: Ticket) {
    if (claimBusy || !window.confirm(`Assign ${t.reference} to yourself?`))
      return;
    setClaimBusy(true);
    try {
      await api(endpoint + `/${t.id}/actions`, {
        version: t.version,
        action: "CLAIM",
      });
      setVersion((v) => v + 1);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Unable to claim ticket.");
    } finally {
      setClaimBusy(false);
    }
  }
  useEffect(() => {
    const update = () => setVersion((v) => v + 1);
    const timer = setInterval(() => {
      if (document.visibilityState === "visible") update();
    }, 20000);
    window.addEventListener("helpdesk-conversation-read", update);
    return () => {
      clearInterval(timer);
      window.removeEventListener("helpdesk-conversation-read", update);
    };
  }, []);
  const filtered = !!(search || status || priority || category);
  return (
    <article className="panel tickets">
      <div className="panel-heading">
        <div>
          <h2>
            {recent
              ? "Recent tickets"
              : staff
                ? "Company tickets"
                : "My tickets"}
            {data && <span className="count-badge">{data.total}</span>}
          </h2>
          <p>
            {staff
              ? "Support requests from your company’s customers"
              : "Your requests, status and details in one place"}
          </p>
        </div>
        {recent ? (
          <Link
            className="subtle"
            href={
              staff
                ? `/workspace/${slug}?view=tickets`
                : `/support/${slug}/tickets`
            }
          >
            View all <ArrowUpRight size={13} />
          </Link>
        ) : (
          <button className="subtle" onClick={() => setVersion((v) => v + 1)}>
            Refresh
          </button>
        )}
      </div>
      {staff && !recent && (
        <div className="queue-tabs" role="group" aria-label="Ticket queues">
          {[
            ["ALL", "All Tickets"],
            ["MINE", "My Assigned Tickets"],
            ["UNASSIGNED", "Unassigned Tickets"],
            ["IN_PROGRESS", "In Progress"],
            ["DONE", "Resolved and Closed"],
          ].map(([value, label]) => (
            <button
              key={value}
              className={queue === value ? "queue-active" : "subtle"}
              aria-pressed={queue === value}
              onClick={() => {
                setQueue(value);
                setPage(0);
              }}
            >
              {label}
            </button>
          ))}
        </div>
      )}
      {!recent && (
        <div className="ticket-filters">
          <div className="search-field">
            <Search size={15} />
            <input
              aria-label="Search tickets"
              placeholder={
                staff
                  ? "Search reference, subject or customer"
                  : "Search reference or subject"
              }
              value={search}
              maxLength={100}
              onChange={(e) => {
                setSearch(e.target.value);
                setPage(0);
              }}
            />
          </div>
          <select
            aria-label="Filter ticket status"
            value={status}
            onChange={(e) => {
              setStatus(e.target.value);
              setPage(0);
            }}
          >
            <option value="">All statuses</option>
            {statuses.map((s) => (
              <option value={s} key={s}>
                {ticketLabel(s)}
              </option>
            ))}
          </select>
          <select
            aria-label="Filter ticket priority"
            value={priority}
            onChange={(e) => {
              setPriority(e.target.value);
              setPage(0);
            }}
          >
            <option value="">All priorities</option>
            {["LOW", "MEDIUM", "HIGH", "CRITICAL"].map((p) => (
              <option key={p} value={p}>
                {ticketLabel(p)}
              </option>
            ))}
          </select>
          <select
            aria-label="Filter ticket category"
            value={category}
            onChange={(e) => {
              setCategory(e.target.value);
              setPage(0);
            }}
          >
            <option value="">All categories</option>
            {info?.categories.map((c) => (
              <option key={c.id} value={c.id}>
                {c.name}
              </option>
            ))}
          </select>
        </div>
      )}
      {loading ? (
        <div className="team-state" role="status">
          Loading tickets…
        </div>
      ) : error ? (
        <div className="empty">
          <h3>Tickets unavailable</h3>
          <p role="alert">{error}</p>
          <button
            className="subtle invitation-return"
            onClick={() => setVersion((v) => v + 1)}
          >
            Try again
          </button>
        </div>
      ) : data?.items.length ? (
        <>
          <p className="table-scroll-hint">
            Scroll the table to see all ticket details.
          </p>
          <TicketTable
            tickets={data.items}
            detailBase={detailBase}
            staff={staff}
            onClaim={agentRole && !claimBusy ? (t) => void claim(t) : undefined}
          />
        </>
      ) : (
        <div className="empty">
          <span className="empty-icon">
            <Inbox size={23} />
          </span>
          <h3>{filtered ? "No matching tickets" : "No tickets yet"}</h3>
          <p>
            {filtered
              ? "Try a different search or filter."
              : staff
                ? "Customer requests will appear here when they are submitted."
                : "Need a hand? Create a ticket and your support team can take a look."}
          </p>
          {!staff && !filtered && (
            <Link
              className="subtle invitation-return"
              href={`/support/${slug}/new`}
            >
              Create a ticket <ArrowUpRight size={13} />
            </Link>
          )}
        </div>
      )}
      {!recent && data && data.total > data.size && (
        <div className="ticket-pagination">
          <span>
            {page * data.size + 1}–
            {Math.min((page + 1) * data.size, data.total)} of {data.total}
          </span>
          <div>
            <button
              className="subtle"
              aria-label="Previous ticket page"
              disabled={page === 0 || loading}
              onClick={() => setPage((p) => p - 1)}
            >
              <ChevronLeft size={14} />
            </button>
            <button
              className="subtle"
              aria-label="Next ticket page"
              disabled={(page + 1) * data.size >= data.total || loading}
              onClick={() => setPage((p) => p + 1)}
            >
              <ChevronRight size={14} />
            </button>
          </div>
        </div>
      )}
    </article>
  );
}
