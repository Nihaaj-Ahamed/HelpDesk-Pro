"use client";
import { useEffect, useState } from "react";
import { api } from "./api";
import { ticketDate, ticketLabel } from "./tickets";
type Target = {
  deadline: string | null;
  fulfilledAt: string | null;
  remainingSeconds: number;
  overdueSeconds: number;
  state: string;
};
type Detail = {
  tracked: boolean;
  legacy: boolean;
  prioritySnapshot?: string;
  firstResponse?: Target;
  resolution?: Target;
  events?: {
    id: string;
    target: string;
    kind: string;
    dueAt: string;
    createdAt: string;
  }[];
};
export function TicketSla({
  slug,
  id,
  staff,
  version,
}: {
  slug: string;
  id: string;
  staff: boolean;
  version: number;
}) {
  const [data, setData] = useState<Detail>(),
    [error, setError] = useState("");
  useEffect(() => {
    let live = true;
    const update = () =>
      api<Detail>(
        `/${staff ? "workspaces" : "support"}/${slug}/tickets/${id}/sla`,
      )
        .then((r) => {
          if (live) {
            setData(r);
            setError("");
          }
        })
        .catch((e) => {
          if (live) setError(e.message);
        });
    void update();
    const timer = setInterval(() => void update(), 30000);
    return () => {
      live = false;
      clearInterval(timer);
    };
  }, [slug, id, staff, version]);
  return (
    <article className="panel sla-panel">
      <div className="panel-heading">
        <div>
          <h2>Service targets</h2>
          <p>24/7 elapsed time | Original policy snapshot</p>
        </div>
      </div>
      {error ? (
        <p className="error">{error}</p>
      ) : !data ? (
        <p className="team-state">Loading SLA...</p>
      ) : (
        <div className="sla-targets">
          {!data.tracked && (
            <p>
              {data.legacy
                ? "Legacy ticket: no SLA policy was agreed at creation."
                : "SLA tracking was disabled when this ticket was created."}
            </p>
          )}
          {[
            ["First response", data.firstResponse],
            ["Resolution", data.resolution],
          ].map(([label, value]) => {
            const t = value as Target | undefined;
            return (
              t && (
                <div className="sla-target" key={String(label)}>
                  <strong>{String(label)}</strong>
                  <span className={`sla-chip ${t.state.toLowerCase()}`}>
                    {ticketLabel(t.state)}
                  </span>
                  <small>Deadline: {ticketDate(t.deadline, true)}</small>
                  {t.fulfilledAt && (
                    <small>Fulfilled: {ticketDate(t.fulfilledAt, true)}</small>
                  )}
                  {t.deadline && (
                    <small>
                      {t.overdueSeconds > 0
                        ? `${Math.ceil(t.overdueSeconds / 60)} minutes overdue`
                        : `${Math.max(0, Math.ceil(t.remainingSeconds / 60))} minutes remaining`}
                    </small>
                  )}
                </div>
              )
            );
          })}
          {staff && !!data.events?.length && (
            <div>
              <h3>Escalation history</h3>
              {data.events.map((e) => (
                <p key={e.id}>
                  {ticketLabel(e.target)} - {ticketLabel(e.kind)} -{" "}
                  {ticketDate(e.createdAt, true)}
                </p>
              ))}
            </div>
          )}
        </div>
      )}
    </article>
  );
}
type Policy = {
  priority: string;
  responseMinutes: number;
  resolutionMinutes: number;
  warningPercent: number;
  enabled: boolean;
  version: number;
};
type Delivery = {
  id: string;
  state: string;
  attempts: number;
  reference: string;
  recipient: string;
  lastError: string | null;
};
export default function SlaManagement({ slug }: { slug: string }) {
  const [queue, setQueue] = useState<
    {
      id: string;
      reference: string;
      subject: string;
      status: string;
      response_due: string | null;
      resolution_due: string | null;
    }[]
  >([]);
  const [policies, setPolicies] = useState<Policy[]>(),
    [overview, setOverview] = useState<Record<string, number>>(),
    [deliveries, setDeliveries] = useState<Delivery[]>([]),
    [error, setError] = useState(""),
    [notice, setNotice] = useState(""),
    [busy, setBusy] = useState(false),
    [version, setVersion] = useState(0);
  useEffect(() => {
    let live = true;
    Promise.all([
      api<Policy[]>(`/workspaces/${slug}/sla/policies`),
      api<Record<string, number>>(`/workspaces/${slug}/sla/overview`),
      api<Delivery[]>(`/companies/${slug}/notifications/deliveries`),
      api<typeof queue>(`/workspaces/${slug}/sla/queue`),
    ])
      .then(([p, o, d, q]) => {
        if (live) {
          setPolicies(p);
          setOverview(o);
          setDeliveries(d);
          setQueue(q);
          setError("");
        }
      })
      .catch((e) => {
        if (live) setError(e.message);
      });
    return () => {
      live = false;
    };
  }, [slug, version]);
  async function save(p: Policy) {
    setBusy(true);
    try {
      await api(`/workspaces/${slug}/sla/policies`, p);
      setNotice("Policy saved. Existing ticket deadlines are preserved.");
      setVersion((v) => v + 1);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Save failed.");
    } finally {
      setBusy(false);
    }
  }
  return (
    <>
      {error && (
        <p className="error" role="alert">
          {error}
        </p>
      )}
      {notice && (
        <p className="notice" role="status">
          {notice}
        </p>
      )}
      <article className="panel preferences-panel">
        <h2>SLA overview</h2>
        <p>
          24/7 elapsed time. Deadlines never reset on reassignment, priority
          edits, policy edits, or reopening. First response requires a public
          staff reply; resolution measures the first authorized resolution.
        </p>
        {overview && (
          <div className="workflow-summary">
            {Object.entries(overview).map(([key, value]) => (
              <div key={key}>
                <span>{ticketLabel(key.toUpperCase())}</span>
                <strong>{value}</strong>
              </div>
            ))}
          </div>
        )}
        <p>
          Resolution compliance:{" "}
          {overview && overview.resolution_measured > 0
            ? `${Math.round((100 * overview.resolution_met) / overview.resolution_measured)}%`
            : "No measured resolutions yet"}
        </p>
        <h3>Upcoming and overdue targets</h3>
        {!queue.length ? (
          <p>No outstanding tracked targets.</p>
        ) : (
          <div className="table-scroll">
            <table className="ticket-table">
              <thead>
                <tr>
                  <th>Ticket</th>
                  <th>Status</th>
                  <th>First response due</th>
                  <th>Resolution due</th>
                </tr>
              </thead>
              <tbody>
                {queue.map((t) => (
                  <tr key={t.id}>
                    <td>
                      <a href={`/workspace/${slug}/tickets/${t.id}`}>
                        {t.reference} - {t.subject}
                      </a>
                    </td>
                    <td>{ticketLabel(t.status)}</td>
                    <td>{ticketDate(t.response_due, true)}</td>
                    <td>{ticketDate(t.resolution_due, true)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        <h2>Company SLA policies</h2>
        {!policies ? (
          <p>Loading policies...</p>
        ) : (
          policies.map((p, index) => (
            <form
              key={p.priority}
              className="policy-row"
              onSubmit={(e) => {
                e.preventDefault();
                void save(p);
              }}
            >
              <strong>
                {p.priority === "CRITICAL" ? "Urgent (CRITICAL)" : p.priority}
              </strong>
              {[
                ["First response minutes", "responseMinutes", 525600],
                ["Resolution minutes", "resolutionMinutes", 525600],
                ["Warning remaining percent", "warningPercent", 99],
              ].map(([label, key, max]) => (
                <label key={String(key)}>
                  {String(label)}
                  <input
                    type="number"
                    min={1}
                    max={Number(max)}
                    required
                    value={
                      p[
                        key as
                          | "responseMinutes"
                          | "resolutionMinutes"
                          | "warningPercent"
                      ]
                    }
                    onChange={(e) =>
                      setPolicies(
                        policies.map((x, i) =>
                          i === index
                            ? { ...x, [key]: Number(e.target.value) }
                            : x,
                        ),
                      )
                    }
                  />
                </label>
              ))}
              <label className="check-row">
                <input
                  type="checkbox"
                  checked={p.enabled}
                  onChange={(e) =>
                    setPolicies(
                      policies.map((x, i) =>
                        i === index ? { ...x, enabled: e.target.checked } : x,
                      ),
                    )
                  }
                />
                Enabled
              </label>
              <button className="primary" disabled={busy}>
                Save {p.priority}
              </button>
            </form>
          ))
        )}
      </article>
      <article className="panel preferences-panel">
        <h2>Email delivery status</h2>
        <p>
          Messages retry up to six times. SMTP failures do not roll back ticket
          operations.
        </p>
        {!deliveries.length ? (
          <p>No queued email deliveries.</p>
        ) : (
          <div className="table-scroll">
            <table className="ticket-table">
              <thead>
                <tr>
                  <th>Ticket</th>
                  <th>Recipient</th>
                  <th>State</th>
                  <th>Attempts</th>
                  <th>Action</th>
                </tr>
              </thead>
              <tbody>
                {deliveries.map((d) => (
                  <tr key={d.id}>
                    <td>{d.reference}</td>
                    <td>{d.recipient}</td>
                    <td>
                      {d.state}
                      {d.lastError && <small>{d.lastError}</small>}
                    </td>
                    <td>{d.attempts}</td>
                    <td>
                      {d.state === "FAILED" && (
                        <button
                          className="subtle"
                          onClick={() => {
                            void api(
                              `/companies/${slug}/notifications/deliveries/${d.id}/retry`,
                              {},
                            )
                              .then(() => setVersion((v) => v + 1))
                              .catch((e) => setError(e.message));
                          }}
                        >
                          Retry delivery
                        </button>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </article>
    </>
  );
}
