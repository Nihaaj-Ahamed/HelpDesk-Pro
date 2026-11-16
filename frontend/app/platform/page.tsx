"use client";
import { useEffect, useState } from "react";
import Link from "next/link";
import { api, refresh, setAccess } from "../../components/api";
import { Bars } from "../../components/Reporting";
import { ticketDate, ticketLabel } from "../../components/tickets";
type Company = {
  id: string;
  name: string;
  slug: string;
  created_at: string | null;
  status: string;
  version: number;
  members: number;
  tickets: number;
};
type Overview = {
  counts: Record<string, number>;
  registrations: { day: string; value: number }[];
  ticketVolume: { day: string; value: number }[];
  recentCompanies: Company[];
};
type Audit = {
  id: string;
  actor_name: string;
  action: string;
  reason: string;
  created_at: string;
  company: string | null;
};
export default function Platform() {
  const [tab, setTab] = useState("Overview"),
    [overview, setOverview] = useState<Overview>(),
    [companies, setCompanies] = useState<{ items: Company[]; total: number }>(),
    [audit, setAudit] = useState<{ items: Audit[]; total: number }>(),
    [selected, setSelected] = useState<Company>(),
    [search, setSearch] = useState(""),
    [status, setStatus] = useState(""),
    [page, setPage] = useState(0),
    [reason, setReason] = useState(""),
    [error, setError] = useState(""),
    [notice, setNotice] = useState(""),
    [ready, setReady] = useState(false),
    [version, setVersion] = useState(0),
    [busy, setBusy] = useState(false);
  const [range, setRange] = useState({
    from: new Date(Date.now() - 29 * 86400000).toISOString().slice(0, 10),
    to: new Date().toISOString().slice(0, 10),
  });
  useEffect(() => {
    let live = true;
    (async () => {
      try {
        if (!(await refresh())) {
          window.location.replace("/platform/login");
          return;
        }
        const data = await api<Overview>(
          `/platform/overview?from=${range.from}&to=${range.to}`,
        );
        if (live) {
          setOverview(data);
          setReady(true);
        }
      } catch (e) {
        if (live)
          setError(
            e instanceof Error ? e.message : "Platform access unavailable.",
          );
      }
    })();
    return () => {
      live = false;
    };
  }, []);
  useEffect(() => {
    if (!ready) return;
    let live = true;
    setCompanies(undefined);
    setAudit(undefined);
    const timer = setTimeout(() => {
      Promise.all([
        api<Overview>(`/platform/overview?from=${range.from}&to=${range.to}`),
        api<{ items: Company[]; total: number }>(
          `/platform/companies?search=${encodeURIComponent(search)}&status=${status}&page=${page}`,
        ),
        api<{ items: Audit[]; total: number }>(
          `/platform/activity?page=${page}`,
        ),
      ])
        .then(([o, c, a]) => {
          if (live) {
            setOverview(o);
            setCompanies(c);
            setAudit(a);
            setError("");
          }
        })
        .catch((e) => {
          if (live) setError(e.message);
        });
    }, 200);
    return () => {
      live = false;
      clearTimeout(timer);
    };
  }, [ready, search, status, page, version, range.from, range.to]);
  async function change(e: React.FormEvent) {
    e.preventDefault();
    if (
      !selected ||
      !window.confirm(
        `${selected.status === "ACTIVE" ? "Suspend" : "Reactivate"} ${selected.name}?`,
      )
    )
      return;
    setBusy(true);
    try {
      await api(`/platform/companies/${selected.id}/status`, {
        status: selected.status === "ACTIVE" ? "SUSPENDED" : "ACTIVE",
        version: selected.version,
        reason,
      });
      setSelected(undefined);
      setReason("");
      setNotice("Company status saved and audited.");
      setVersion((v) => v + 1);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Update failed.");
    } finally {
      setBusy(false);
    }
  }
  async function logout() {
    await api("/auth/logout", {});
    setAccess(null);
    window.location.replace("/platform/login");
  }
  const table = (rows: Company[]) => (
    <div className="table-scroll">
      <table className="ticket-table">
        <thead>
          <tr>
            {[
              "Company",
              "Workspace",
              "Registered",
              "Status",
              "Members",
              "Tickets",
              "Action",
            ].map((x) => (
              <th key={x}>{x}</th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((c) => (
            <tr key={c.id}>
              <td>{c.name}</td>
              <td>
                {c.slug}
                <small>{c.id}</small>
              </td>
              <td>{ticketDate(c.created_at, true)}</td>
              <td>{c.status}</td>
              <td>{c.members}</td>
              <td>{c.tickets}</td>
              <td>
                <button
                  className="subtle"
                  onClick={() => {
                    void api<Company>(`/platform/companies/${c.id}`)
                      .then(setSelected)
                      .catch((e) => setError(e.message));
                  }}
                >
                  Company details
                </button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      {!rows.length && <p className="empty">No matching companies.</p>}
    </div>
  );
  return (
    <div className="staff-ticket-page">
      <header className="portal-header">
        <strong>HelpDesk Pro | Platform administration</strong>
        <div className="workflow-buttons">
          <Link href="/workspaces">My workspaces</Link>
          {ready && (
            <button className="subtle" onClick={() => void logout()}>
              Sign out
            </button>
          )}
        </div>
      </header>
      <main className="platform-main">
        {error && (
          <p role="alert" className="error">
            {error}
          </p>
        )}
        {notice && (
          <p role="status" className="notice">
            {notice}
          </p>
        )}
        {!ready ? (
          <>
            <h1>Platform access</h1>
            <p>
              {error
                ? "This area requires operator-provisioned platform authority."
                : "Checking platform authority..."}
            </p>
            <Link href="/platform/login">Platform sign in</Link>
          </>
        ) : (
          <>
            <nav className="platform-nav" aria-label="Platform navigation">
              {[
                "Overview",
                "Companies",
                "Platform Analytics",
                "Administrative Activity",
              ].map((t) => (
                <button
                  key={t}
                  aria-pressed={tab === t}
                  className={tab === t ? "queue-active" : "subtle"}
                  onClick={() => {
                    setTab(t);
                    setPage(0);
                  }}
                >
                  {t}
                </button>
              ))}
            </nav>
            <h1>{tab}</h1>
            <p className="metric-explanation">
              Totals are all-time; charts and recent registrations use the
              selected UTC range.
            </p>
            <div className="report-filters">
              <label>
                From
                <input
                  type="date"
                  value={range.from}
                  onChange={(e) => setRange({ ...range, from: e.target.value })}
                />
              </label>
              <label>
                Through
                <input
                  type="date"
                  value={range.to}
                  onChange={(e) => setRange({ ...range, to: e.target.value })}
                />
              </label>
            </div>
            <p className="metric-explanation">
              Platform metadata and aggregate usage. This authority does not
              grant access to private company tickets or conversations.
            </p>
            {(tab === "Overview" || tab === "Platform Analytics") &&
              overview && (
                <>
                  <div className="workflow-summary">
                    {Object.entries(overview.counts).map(([key, value]) => (
                      <div key={key}>
                        <span>{ticketLabel(key.toUpperCase())}</span>
                        <strong>{value}</strong>
                      </div>
                    ))}
                  </div>
                  <div className="panel-grid">
                    <Bars
                      title="New companies - selected UTC range"
                      rows={overview.registrations.map((r) => ({
                        label: r.day,
                        value: r.value,
                      }))}
                    />
                    <Bars
                      title="Ticket volume - selected UTC range"
                      rows={overview.ticketVolume.map((r) => ({
                        label: r.day,
                        value: r.value,
                      }))}
                    />
                  </div>
                  {tab === "Overview" && (
                    <article className="panel preferences-panel">
                      <h2>Recent registrations</h2>
                      {table(overview.recentCompanies)}
                    </article>
                  )}
                </>
              )}
            {tab === "Companies" && (
              <article className="panel preferences-panel">
                <div className="report-filters">
                  <label>
                    Search companies
                    <input
                      aria-label="Search companies"
                      maxLength={100}
                      value={search}
                      onChange={(e) => {
                        setSearch(e.target.value);
                        setPage(0);
                      }}
                    />
                  </label>
                  <label>
                    Company status
                    <select
                      value={status}
                      onChange={(e) => {
                        setStatus(e.target.value);
                        setPage(0);
                      }}
                    >
                      <option value="">All</option>
                      <option>ACTIVE</option>
                      <option>SUSPENDED</option>
                    </select>
                  </label>
                </div>
                {companies ? (
                  table(companies.items)
                ) : (
                  <p>Loading companies...</p>
                )}
                <div className="ticket-pagination">
                  <span>{companies?.total || 0} companies</span>
                  <button
                    className="subtle"
                    disabled={!page}
                    onClick={() => setPage((p) => p - 1)}
                  >
                    Previous
                  </button>
                  <button
                    className="subtle"
                    disabled={!companies || (page + 1) * 20 >= companies.total}
                    onClick={() => setPage((p) => p + 1)}
                  >
                    Next
                  </button>
                </div>
              </article>
            )}
            {tab === "Administrative Activity" && (
              <article className="panel preferences-panel">
                <h2>Immutable administrative history</h2>
                {!audit ? (
                  <p>Loading history...</p>
                ) : (
                  <div className="table-scroll">
                    <table className="ticket-table">
                      <thead>
                        <tr>
                          <th>When</th>
                          <th>Actor</th>
                          <th>Action</th>
                          <th>Company</th>
                          <th>Reason</th>
                        </tr>
                      </thead>
                      <tbody>
                        {audit.items.map((a) => (
                          <tr key={a.id}>
                            <td>{ticketDate(a.created_at, true)}</td>
                            <td>{a.actor_name}</td>
                            <td>{ticketLabel(a.action)}</td>
                            <td>{a.company || "Platform"}</td>
                            <td>{a.reason}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                    {!audit.items.length && <p>No administrative events.</p>}
                  </div>
                )}
                <div className="ticket-pagination">
                  <button
                    className="subtle"
                    disabled={!page}
                    onClick={() => setPage((p) => p - 1)}
                  >
                    Previous
                  </button>
                  <button
                    className="subtle"
                    disabled={!audit || (page + 1) * 20 >= audit.total}
                    onClick={() => setPage((p) => p + 1)}
                  >
                    Next
                  </button>
                </div>
              </article>
            )}
            {selected && (
              <article
                className="panel preferences-panel"
                role="dialog"
                aria-modal="false"
                aria-label="Company management"
              >
                <h2>{selected.name}</h2>
                <p>
                  {selected.slug} | {selected.status} | {selected.members}{" "}
                  members | {selected.tickets} tickets
                </p>
                <p className="metric-explanation">
                  Suspension preserves data, blocks protected tenant operations
                  and stops new live delivery. SLA clocks continue; queued
                  emails and jobs resume on activation.
                </p>
                <form onSubmit={change}>
                  <label>
                    Administrative reason
                    <textarea
                      required
                      minLength={5}
                      maxLength={500}
                      value={reason}
                      onChange={(e) => setReason(e.target.value)}
                    />
                  </label>
                  <button className="primary" disabled={busy}>
                    {selected.status === "ACTIVE"
                      ? "Suspend company"
                      : "Reactivate company"}
                  </button>
                  <button
                    type="button"
                    className="subtle"
                    disabled={busy}
                    onClick={() => setSelected(undefined)}
                  >
                    Close details
                  </button>
                </form>
              </article>
            )}
          </>
        )}
      </main>
    </div>
  );
}
