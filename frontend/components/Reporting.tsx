"use client";
import { useEffect, useState } from "react";
import { api, downloadCsv } from "./api";
import { ticketLabel } from "./tickets";
type Counts = Record<string, number | null>;
type Group = { label: string; value: number };
type Trend = {
  day: string;
  created: number;
  resolved: number;
  backlog: number;
};
type Agent = {
  id: string;
  name: string;
  status: string;
  workload: number;
  resolved: number;
  average_response_minutes: number | null;
  average_resolution_minutes: number | null;
  met_targets: number;
  measured_targets: number;
  overdue: number;
  assigned: number;
};
type Data = {
  counts: Counts;
  byStatus: Group[];
  byPriority: Group[];
  byCategory: Group[];
  trend: Trend[];
  agents: Agent[];
  categories: { id: string; name: string }[];
  agentResolutionTrend: { agent_id: string; day: string; value: number }[];
  missingCreatedAt: number;
};
const today = () => new Date().toISOString().slice(0, 10);
const ago = () =>
  new Date(Date.now() - 29 * 86400000).toISOString().slice(0, 10);
const number = (n: unknown) =>
  n === null || n === undefined ? "Not measured" : Number(n).toFixed(1);
const compliance = (met: unknown, measured: unknown) =>
  Number(measured) > 0
    ? `${Math.round((100 * Number(met)) / Number(measured))}%`
    : "Not measured";
export function Bars({ title, rows }: { title: string; rows: Group[] }) {
  const max = Math.max(1, ...rows.map((r) => Number(r.value)));
  return (
    <article className="panel operations-chart">
      <h2>{title}</h2>
      {!rows.length ? (
        <p className="metric-explanation">No observations in this range.</p>
      ) : (
        rows.map((r, i) => (
          <div className="chart-row" key={r.label + i}>
            <span>{r.label}</span>
            <div className="chart-track">
              <i style={{ width: `${(100 * Number(r.value)) / max}%` }} />
            </div>
            <strong>{r.value}</strong>
          </div>
        ))
      )}
    </article>
  );
}
export function TrendChart({
  title,
  rows,
  field,
}: {
  title: string;
  rows: Trend[];
  field: "created" | "resolved" | "backlog";
}) {
  const max = Math.max(1, ...rows.map((r) => Number(r[field]))),
    points = rows
      .map(
        (r, i) =>
          `${20 + (i * 560) / Math.max(1, rows.length - 1)},${160 - (Number(r[field]) * 135) / max}`,
      )
      .join(" ");
  return (
    <article className="panel operations-chart">
      <h2>{title}</h2>
      {!rows.some((r) => Number(r[field]) > 0) ? (
        <p className="metric-explanation">
          No recorded {field} activity in this cohort.
        </p>
      ) : (
        <>
          <svg viewBox="0 0 600 185" role="img" aria-label={title}>
            <line x1="20" y1="160" x2="580" y2="160" stroke="#e8e8e8" />
            <polyline
              fill="none"
              stroke="#749b15"
              strokeWidth="3"
              points={points}
            />
            {rows.map((r, i) => (
              <circle
                key={r.day}
                cx={20 + (i * 560) / Math.max(1, rows.length - 1)}
                cy={160 - (Number(r[field]) * 135) / max}
                r="2"
                fill="#9dc622"
              >
                <title>
                  {String(r.day)}: {r[field]}
                </title>
              </circle>
            ))}
          </svg>
          <p className="metric-explanation">
            {String(rows[0]?.day)} to {String(rows.at(-1)?.day)} (UTC)
          </p>
        </>
      )}
    </article>
  );
}
export function AnalyticsPreview({
  slug,
  personal = false,
}: {
  slug: string;
  personal?: boolean;
}) {
  const [data, setData] = useState<Data>(),
    [error, setError] = useState("");
  useEffect(() => {
    let live = true;
    api<Data>(
      `/workspaces/${slug}/${personal ? "my-performance" : "analytics"}`,
    )
      .then((d) => {
        if (live) setData(d);
      })
      .catch((e) => {
        if (live) setError(e.message);
      });
    return () => {
      live = false;
    };
  }, [slug, personal]);
  return (
    <article className="panel operations-chart">
      <h2>{personal ? "My performance" : "Company insights"}</h2>
      {error ? (
        <p role="alert">{error}</p>
      ) : !data ? (
        <p>Loading insights...</p>
      ) : (
        <>
          <p className="metric-explanation">
            Last 30 days by ticket creation. {data.counts.total} tickets.
          </p>
          <div className="workflow-summary">
            <div>
              <span>First response (min)</span>
              <strong>
                {number(
                  personal
                    ? data.agents[0]?.average_response_minutes
                    : data.counts.average_response_minutes,
                )}
              </strong>
            </div>
            <div>
              <span>Resolution (min)</span>
              <strong>
                {number(
                  personal
                    ? data.agents[0]?.average_resolution_minutes
                    : data.counts.average_resolution_minutes,
                )}
              </strong>
            </div>
            <div>
              <span>SLA compliance</span>
              <strong>
                {personal
                  ? compliance(
                      data.agents[0]?.met_targets,
                      data.agents[0]?.measured_targets,
                    )
                  : compliance(
                      data.counts.met_targets,
                      data.counts.measured_targets,
                    )}
              </strong>
            </div>
          </div>
          <a
            className="subtle"
            href={`/workspace/${slug}?view=${personal ? "performance" : "analytics"}`}
          >
            Open detailed analytics
          </a>
        </>
      )}
    </article>
  );
}
export default function Reporting({
  slug,
  personal = false,
  mode = "analytics",
}: {
  slug: string;
  personal?: boolean;
  mode?: "analytics" | "performance" | "reports";
}) {
  const [filters, setFilters] = useState({
      from: ago(),
      to: today(),
      status: "",
      priority: "",
      category: "",
      agent: "",
    }),
    [data, setData] = useState<Data>(),
    [error, setError] = useState(""),
    [report, setReport] = useState("TICKETS"),
    [busy, setBusy] = useState(false),
    [notice, setNotice] = useState("");
  const q = new URLSearchParams(filters);
  for (const [k, v] of [...q]) if (!v) q.delete(k);
  const query = q.toString();
  useEffect(() => {
    let live = true;
    setData(undefined);
    api<Data>(
      `/workspaces/${slug}/${personal ? "my-performance" : "analytics"}?${query}`,
    )
      .then((d) => {
        if (live) {
          setData(d);
          setError("");
        }
      })
      .catch((e) => {
        if (live) setError(e.message);
      });
    return () => {
      live = false;
    };
  }, [slug, personal, query]);
  async function download() {
    setBusy(true);
    try {
      await downloadCsv(
        `/workspaces/${slug}/${personal ? "my-performance/export" : "reports/export"}?${query}&report=${report}`,
        `helpdesk-${personal ? "my-performance" : report.toLowerCase()}.csv`,
      );
      setNotice("CSV downloaded. Exports are limited to 10000 records.");
      setError("");
    } catch (e) {
      setError(e instanceof Error ? e.message : "Export failed.");
    } finally {
      setBusy(false);
    }
  }
  return (
    <>
      <p className="metric-explanation">
        Ticket creation cohort, inclusive UTC dates (maximum 366 days). Filters
        use current status, priority, category and assignee. Reopened tickets
        count in current backlog; averages use the first recorded staff response
        and first resolution. Missing observations are excluded. SLA compliance
        measures fulfilled or overdue targets; pending targets before their
        deadline are excluded. Volume alone does not measure agent quality.
      </p>
      <div className="report-filters">
        <label>
          From
          <input
            type="date"
            value={filters.from}
            onChange={(e) => setFilters({ ...filters, from: e.target.value })}
          />
        </label>
        <label>
          Through
          <input
            type="date"
            value={filters.to}
            onChange={(e) => setFilters({ ...filters, to: e.target.value })}
          />
        </label>
        {[
          [
            "Status",
            "status",
            ["OPEN", "ASSIGNED", "IN_PROGRESS", "RESOLVED", "CLOSED"],
          ],
          ["Priority", "priority", ["LOW", "MEDIUM", "HIGH", "CRITICAL"]],
        ].map(([label, key, values]) => (
          <label key={String(key)}>
            {String(label)}
            <select
              value={filters[key as "status" | "priority"]}
              onChange={(e) =>
                setFilters({ ...filters, [String(key)]: e.target.value })
              }
            >
              <option value="">All</option>
              {(values as string[]).map((v) => (
                <option key={v}>{v}</option>
              ))}
            </select>
          </label>
        ))}
        <label>
          Category
          <select
            value={filters.category}
            onChange={(e) =>
              setFilters({ ...filters, category: e.target.value })
            }
          >
            <option value="">All</option>
            {data?.categories.map((c) => (
              <option key={c.id} value={c.id}>
                {c.name}
              </option>
            ))}
          </select>
        </label>
        {!personal && (
          <label>
            Assigned agent
            <select
              value={filters.agent}
              onChange={(e) =>
                setFilters({ ...filters, agent: e.target.value })
              }
            >
              <option value="">All</option>
              {data?.agents.map((a) => (
                <option key={a.id} value={a.id}>
                  {a.name}
                </option>
              ))}
            </select>
          </label>
        )}
      </div>
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
      {!data ? (
        <p className="team-state">
          {error ? "Adjust the filters to retry." : "Loading report data..."}
        </p>
      ) : (
        <>
          {!!data.missingCreatedAt && (
            <p className="metric-explanation">
              {data.missingCreatedAt} legacy tickets have no creation timestamp
              and are excluded from date cohorts.
            </p>
          )}
          <div className="workflow-summary">
            {Object.entries(data.counts)
              .filter(
                ([k]) =>
                  ![
                    "average_response_minutes",
                    "average_resolution_minutes",
                    "met_targets",
                    "measured_targets",
                  ].includes(k),
              )
              .map(([key, value]) => (
                <div key={key}>
                  <span>{ticketLabel(key.toUpperCase())}</span>
                  <strong>{value}</strong>
                </div>
              ))}
          </div>
          <div className="workflow-summary">
            <div>
              <span>Average first response (min)</span>
              <strong>
                {number(
                  personal
                    ? data.agents[0]?.average_response_minutes
                    : data.counts.average_response_minutes,
                )}
              </strong>
            </div>
            <div>
              <span>Average first resolution (min)</span>
              <strong>
                {number(
                  personal
                    ? data.agents[0]?.average_resolution_minutes
                    : data.counts.average_resolution_minutes,
                )}
              </strong>
            </div>
            <div>
              <span>SLA compliance</span>
              <strong>
                {personal
                  ? compliance(
                      data.agents[0]?.met_targets,
                      data.agents[0]?.measured_targets,
                    )
                  : compliance(
                      data.counts.met_targets,
                      data.counts.measured_targets,
                    )}
              </strong>
            </div>
          </div>
          {mode !== "performance" && (
            <div className="panel-grid">
              <TrendChart
                title="Tickets created over time"
                rows={data.trend}
                field="created"
              />
              <TrendChart
                title="Recorded backlog at day end"
                rows={data.trend}
                field="backlog"
              />
              <Bars title="Tickets by status" rows={data.byStatus} />
              <Bars title="Tickets by priority" rows={data.byPriority} />
              <Bars title="Tickets by category" rows={data.byCategory} />
            </div>
          )}
          {(mode === "performance" || personal) && (
            <>
              <article className="panel operations-chart">
                <h2>
                  {personal ? "My measured performance" : "Agent performance"}
                </h2>
                <p className="metric-explanation">
                  Current workload is separate from historical contribution.
                  First responses are credited to their actual author; first
                  resolutions to their recorded resolver. Legacy or unmeasured
                  work is excluded. Historical SLA targets remain unchanged
                  after reassignment.
                </p>
                <div className="table-scroll">
                  <table className="ticket-table">
                    <thead>
                      <tr>
                        {[
                          "Agent",
                          "Membership",
                          "Workload",
                          "First resolutions",
                          "Avg response min",
                          "Avg resolution min",
                          "SLA",
                          "Overdue",
                        ].map((x) => (
                          <th key={x}>{x}</th>
                        ))}
                      </tr>
                    </thead>
                    <tbody>
                      {data.agents.map((a) => (
                        <tr key={a.id}>
                          <td>{a.name}</td>
                          <td>{a.status}</td>
                          <td>{a.workload}</td>
                          <td>{a.resolved}</td>
                          <td>{number(a.average_response_minutes)}</td>
                          <td>{number(a.average_resolution_minutes)}</td>
                          <td>
                            {compliance(a.met_targets, a.measured_targets)}
                          </td>
                          <td>{a.overdue}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                  {!data.agents.length && (
                    <p>No agent observations in this range.</p>
                  )}
                </div>
              </article>
              <TrendChart
                title="Cohort first-resolution trend"
                rows={data.trend}
                field="resolved"
              />
              <Bars
                title="Resolution contributions over time"
                rows={data.agentResolutionTrend.map((r) => ({
                  label: `${data.agents.find((a) => a.id === r.agent_id)?.name || "Staff"} ${r.day}`,
                  value: r.value,
                }))}
              />
            </>
          )}
          {(mode === "reports" || personal) && (
            <article className="panel preferences-panel">
              <h2>Download report</h2>
              {!personal && (
                <label>
                  Report type
                  <select
                    aria-label="Report type"
                    value={report}
                    onChange={(e) => setReport(e.target.value)}
                  >
                    {[
                      "TICKETS",
                      "STATUS",
                      "PRIORITY",
                      "AGENTS",
                      "SLA",
                      "ESCALATIONS",
                      "OVERDUE",
                    ].map((r) => (
                      <option key={r}>{r}</option>
                    ))}
                  </select>
                </label>
              )}
              <p className="metric-explanation">
                UTF-8 CSV with UTC timestamps and formula-safe fields. No ticket
                messages or internal notes are exported.
              </p>
              {!data.counts.total && <p>No records match these filters.</p>}
              <button
                className="primary"
                disabled={busy}
                onClick={() => void download()}
              >
                {busy ? "Exporting..." : "Download CSV"}
              </button>
            </article>
          )}
        </>
      )}
    </>
  );
}
