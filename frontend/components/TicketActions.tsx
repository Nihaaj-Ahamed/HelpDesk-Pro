"use client";
import { useEffect, useState } from "react";
import { api } from "./api";
import { Ticket, ticketDate, ticketLabel } from "./tickets";
type Options = {
  actions: string[];
  statuses: string[];
  agents: { id: string; name: string; workload: number }[];
};
type Activity = {
  id: string;
  kind: string;
  previousValue: string | null;
  newValue: string | null;
  actorName: string | null;
  actorRole: string | null;
  createdAt: string;
};
export default function TicketActions({
  ticket,
  endpoint,
  onUpdate,
}: {
  ticket: Ticket;
  endpoint: string;
  onUpdate: (t: Ticket) => void;
}) {
  const [options, setOptions] = useState<Options>(),
    [history, setHistory] = useState<Activity[]>([]),
    [error, setError] = useState(""),
    [notice, setNotice] = useState(""),
    [busy, setBusy] = useState(false),
    [agent, setAgent] = useState(""),
    [priority, setPriority] = useState(ticket.priority),
    [pending, setPending] = useState<{
      action: string;
      value?: string;
      agentId?: string;
    }>();
  useEffect(() => {
    let live = true;
    setOptions(undefined);
    setHistory([]);
    Promise.all([
      api<Options>(endpoint + "/actions"),
      api<Activity[]>(endpoint + "/activity"),
    ])
      .then(([o, h]) => {
        if (live) {
          setOptions(o);
          setHistory(h);
          setPriority(ticket.priority);
        }
      })
      .catch((e) => {
        if (live) setError(e.message);
      });
    return () => {
      live = false;
    };
  }, [endpoint, ticket.version, ticket.priority]);
  async function submit() {
    if (!pending) return;
    setBusy(true);
    setError("");
    setNotice("");
    try {
      const updated = await api<Ticket>(endpoint + "/actions", {
        ...pending,
        version: ticket.version,
      });
      onUpdate(updated);
      setNotice("Ticket updated successfully.");
      setPending(undefined);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Unable to update ticket.");
      try {
        onUpdate(await api<Ticket>(endpoint));
      } catch {}
    } finally {
      setBusy(false);
    }
  }
  function activityValue(value: string) {
    if (
      /^(OPEN|ASSIGNED|IN_PROGRESS|RESOLVED|CLOSED|LOW|MEDIUM|HIGH|CRITICAL)$/.test(
        value,
      )
    )
      return ticketLabel(value);
    return value.replace(/ \([a-f0-9-]{36}\)$/i, "");
  }
  return (
    <>
      <article className="panel workflow-panel">
        <div className="panel-heading">
          <h2>Ticket actions</h2>
        </div>
        <div className="workflow-controls">
          {error && (
            <p className="form-error" role="alert">
              {error}
            </p>
          )}
          {notice && (
            <p className="notice" role="status">
              {notice}
            </p>
          )}
          {!options ? (
            <p>Loading available actions...</p>
          ) : (
            <>
              {options.actions.includes("ASSIGN") && (
                <div className="workflow-row">
                  <label htmlFor="assign-agent">Assign agent</label>
                  <select
                    id="assign-agent"
                    value={agent}
                    onChange={(e) => setAgent(e.target.value)}
                  >
                    <option value="">Select an active agent</option>
                    {options.agents.map((a) => (
                      <option key={a.id} value={a.id}>
                        {a.name} - {a.workload} active tickets
                      </option>
                    ))}
                  </select>
                  <button
                    className="subtle"
                    disabled={
                      !agent || busy || agent === ticket.assignedAgent?.id
                    }
                    onClick={() =>
                      setPending({ action: "ASSIGN", agentId: agent })
                    }
                  >
                    Assign agent
                  </button>
                </div>
              )}
              {options.actions.includes("PRIORITY") && (
                <div className="workflow-row">
                  <label htmlFor="update-priority">Priority</label>
                  <select
                    id="update-priority"
                    value={priority}
                    onChange={(e) => setPriority(e.target.value)}
                  >
                    {["LOW", "MEDIUM", "HIGH", "CRITICAL"].map((p) => (
                      <option key={p}>{p}</option>
                    ))}
                  </select>
                  <button
                    className="subtle"
                    disabled={priority === ticket.priority || busy}
                    onClick={() =>
                      setPending({ action: "PRIORITY", value: priority })
                    }
                  >
                    Update priority
                  </button>
                </div>
              )}
              <div className="workflow-buttons">
                {options.actions
                  .filter((a) => !["ASSIGN", "PRIORITY"].includes(a))
                  .map((a) => (
                    <button
                      className="subtle"
                      disabled={busy}
                      key={a}
                      onClick={() => setPending({ action: a })}
                    >
                      {
                        (
                          {
                            CLAIM: "Assign to Me",
                            UNASSIGN: "Release ticket",
                            REOPEN: "Reopen ticket",
                          } as Record<string, string>
                        )[a]
                      }
                    </button>
                  ))}
                {options.statuses.map((s) => (
                  <button
                    className="primary"
                    disabled={busy}
                    key={s}
                    onClick={() => setPending({ action: "STATUS", value: s })}
                  >
                    {
                      (
                        {
                          IN_PROGRESS: "Start work",
                          RESOLVED: "Mark resolved",
                          CLOSED: "Close ticket",
                        } as Record<string, string>
                      )[s]
                    }
                  </button>
                ))}
              </div>
              {!options.actions.length && !options.statuses.length && (
                <p className="muted">
                  No changes are available for your role and this ticket's
                  current state.
                </p>
              )}
            </>
          )}
          {pending && (
            <div
              className="workflow-confirm"
              role="dialog"
              aria-modal="false"
              aria-label="Confirm ticket change"
            >
              <p>
                Confirm {ticketLabel(pending.action)}
                {pending.agentId
                  ? ` to ${options?.agents.find((a) => a.id === pending.agentId)?.name || "selected agent"}`
                  : ""}
                {pending.value ? ` to ${ticketLabel(pending.value)}` : ""}?
                {pending.action === "REOPEN"
                  ? " The ticket will return to the unassigned queue."
                  : ""}
              </p>
              <button
                autoFocus
                className="primary"
                disabled={busy}
                onClick={() => void submit()}
              >
                {busy ? "Saving..." : "Confirm change"}
              </button>
              <button
                className="subtle"
                disabled={busy}
                onClick={() => setPending(undefined)}
              >
                Cancel
              </button>
            </div>
          )}
        </div>
      </article>
      <article className="panel workflow-panel">
        <div className="panel-heading">
          <h2>Activity history</h2>
        </div>
        <ol className="activity-timeline">
          {history.map((h) => (
            <li key={h.id}>
              <strong>{ticketLabel(h.kind)}</strong>
              <span>
                {h.previousValue && `${activityValue(h.previousValue)} to `}
                {(h.newValue ? activityValue(h.newValue) : null) ||
                  (h.kind === "ASSIGNMENT" ? "Unassigned" : "Not recorded")}
              </span>
              {h.actorName && (
                <small>
                  {h.actorName} - {ticketLabel(h.actorRole || "")}
                </small>
              )}
              <time>{ticketDate(h.createdAt, true)}</time>
            </li>
          ))}
        </ol>
        {!history.length && (
          <p className="workflow-controls">
            {options
              ? "No historical activity was recorded."
              : "Loading activity..."}
          </p>
        )}
      </article>
    </>
  );
}
