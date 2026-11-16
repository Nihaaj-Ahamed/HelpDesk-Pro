"use client";
import { useCallback, useEffect, useState } from "react";
import {
  Search,
  UserPlus,
  Users,
  Mail,
  RefreshCw,
  ShieldCheck,
} from "lucide-react";
import { api } from "./api";
import Modal from "./Modal";
import Field from "./Field";
type Member = {
  id: string;
  fullName: string;
  email: string;
  role: string;
  status: string;
  joinedAt: string | null;
};
type Invitation = {
  id: string;
  email: string;
  role: string;
  status: string;
  createdAt: string;
  expiresAt: string;
  lastSentAt: string;
  inviterName: string;
};
const date = (value: string) =>
  new Intl.DateTimeFormat("en", { dateStyle: "medium" }).format(
    new Date(value),
  );
const label = (value: string) =>
  value === "COMPANY_ADMIN"
    ? "Company admin"
    : value === "SUPPORT_AGENT"
      ? "Support agent"
      : value.charAt(0) + value.slice(1).toLowerCase();
export default function TeamManagement({
  slug,
  onChanged,
}: {
  slug: string;
  onChanged: () => void;
}) {
  const [members, setMembers] = useState<Member[]>([]),
    [invitations, setInvitations] = useState<Invitation[]>([]),
    [search, setSearch] = useState(""),
    [role, setRole] = useState(""),
    [status, setStatus] = useState(""),
    [inviteStatus, setInviteStatus] = useState("PENDING");
  const [loading, setLoading] = useState(true),
    [error, setError] = useState(""),
    [notice, setNotice] = useState(""),
    [inviteOpen, setInviteOpen] = useState(false),
    [busy, setBusy] = useState(false),
    [fieldError, setFieldError] = useState("");
  const [action, setAction] = useState<{
    kind: "status" | "resend" | "revoke";
    id: string;
    email: string;
    next?: string;
  } | null>(null);
  const base = `/workspaces/${encodeURIComponent(slug)}/team`;
  const load = useCallback(
    async (signal?: { cancelled: boolean }) => {
      setLoading(true);
      try {
        const query = new URLSearchParams({ search, role, status });
        const [m, i] = await Promise.all([
          api<Member[]>(`${base}/members?${query}`),
          api<Invitation[]>(`${base}/invitations`),
        ]);
        if (!signal?.cancelled) {
          setMembers(m);
          setInvitations(i);
          setError("");
        }
      } catch (e) {
        if (!signal?.cancelled)
          setError(
            e instanceof Error ? e.message : "Unable to load your team.",
          );
      } finally {
        if (!signal?.cancelled) setLoading(false);
      }
    },
    [base, search, role, status],
  );
  useEffect(() => {
    const signal = { cancelled: false };
    const timer = setTimeout(() => void load(signal), 200);
    return () => {
      signal.cancelled = true;
      clearTimeout(timer);
    };
  }, [load]);
  async function invite(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault();
    setBusy(true);
    setError("");
    setFieldError("");
    const data = Object.fromEntries(new FormData(e.currentTarget));
    try {
      await api(`${base}/invitations`, {
        email: String(data.email).trim().toLowerCase(),
        role: "SUPPORT_AGENT",
      });
      setInviteOpen(false);
      setNotice("Invitation sent. Your new agent has 48 hours to accept.");
      await load();
    } catch (e) {
      setFieldError(
        e instanceof Error ? e.message : "Could not send invitation.",
      );
    } finally {
      setBusy(false);
    }
  }
  async function confirm() {
    if (!action) return;
    setBusy(true);
    setError("");
    try {
      await api(
        action.kind === "status"
          ? `${base}/members/${action.id}/status`
          : `${base}/invitations/${action.id}/${action.kind}`,
        action.kind === "status" ? { status: action.next } : {},
      );
      setNotice(
        action.kind === "status"
          ? `Member ${action.next === "ACTIVE" ? "reactivated" : "deactivated"}.`
          : action.kind === "resend"
            ? "Invitation resent. The previous link no longer works."
            : "Invitation revoked. The link can no longer be accepted.",
      );
      setAction(null);
      await load();
      onChanged();
    } catch (e) {
      setError(
        e instanceof Error ? e.message : "Could not complete this action.",
      );
      setAction(null);
    } finally {
      setBusy(false);
    }
  }
  const filteredInvitations = invitations.filter(
    (i) => !inviteStatus || i.status === inviteStatus,
  );
  return (
    <section className="team-management">
      {notice && (
        <div className="notice" role="status">
          <ShieldCheck size={16} />
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
          <button className="subtle" onClick={() => void load()}>
            Try again
          </button>
        </div>
      )}
      <article className="panel">
        <div className="panel-heading">
          <div>
            <h2>
              Team members <span className="count-badge">{members.length}</span>
            </h2>
            <p>Manage the people who support your customers</p>
          </div>
          <button
            className="primary compact"
            onClick={() => {
              setFieldError("");
              setInviteOpen(true);
            }}
          >
            <UserPlus size={15} /> Invite Agent
          </button>
        </div>
        <div className="team-filters">
          <div className="search-field">
            <Search size={15} />
            <input
              aria-label="Search team members"
              placeholder="Search by name or email"
              value={search}
              maxLength={100}
              onChange={(e) => setSearch(e.target.value)}
            />
          </div>
          <select
            aria-label="Filter by role"
            value={role}
            onChange={(e) => setRole(e.target.value)}
          >
            <option value="">All roles</option>
            <option value="COMPANY_ADMIN">Company admin</option>
            <option value="SUPPORT_AGENT">Support agent</option>
          </select>
          <select
            aria-label="Filter by membership status"
            value={status}
            onChange={(e) => setStatus(e.target.value)}
          >
            <option value="">All statuses</option>
            <option value="ACTIVE">Active</option>
            <option value="INACTIVE">Inactive</option>
          </select>
        </div>
        {loading ? (
          <div className="team-state" role="status">
            Loading team members…
          </div>
        ) : members.length === 0 ? (
          <div className="empty">
            <span className="empty-icon">
              <Users size={22} />
            </span>
            <h3>No matching members</h3>
            <p>Try a different name, email, role or status.</p>
          </div>
        ) : (
          <div className="table-scroll">
            <table className="team-table">
              <thead>
                <tr>
                  {["Member", "Role", "Status", "Joined", "Actions"].map(
                    (x) => (
                      <th key={x} scope="col">
                        {x}
                      </th>
                    ),
                  )}
                </tr>
              </thead>
              <tbody>
                {members.map((m) => (
                  <tr key={m.id}>
                    <td>
                      <div className="member-identity">
                        <span className="avatar">{m.fullName.charAt(0)}</span>
                        <div>
                          <strong>{m.fullName}</strong>
                          <small>{m.email}</small>
                        </div>
                      </div>
                    </td>
                    <td>
                      <span
                        className={`role-badge ${m.role === "COMPANY_ADMIN" ? "admin" : ""}`}
                      >
                        {label(m.role)}
                      </span>
                    </td>
                    <td>
                      <span
                        className={`status-badge ${m.status.toLowerCase()}`}
                      >
                        {label(m.status)}
                      </span>
                    </td>
                    <td>
                      {m.joinedAt ? (
                        <time dateTime={m.joinedAt}>{date(m.joinedAt)}</time>
                      ) : (
                        <span className="muted">Not recorded</span>
                      )}
                    </td>
                    <td>
                      {m.role === "SUPPORT_AGENT" ? (
                        <button
                          className="subtle"
                          onClick={() =>
                            setAction({
                              kind: "status",
                              id: m.id,
                              email: m.email,
                              next:
                                m.status === "ACTIVE" ? "INACTIVE" : "ACTIVE",
                            })
                          }
                        >
                          {m.status === "ACTIVE" ? "Deactivate" : "Reactivate"}
                        </button>
                      ) : (
                        <span className="protected">
                          <ShieldCheck size={12} /> Protected
                        </span>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </article>
      <article className="panel invitation-panel">
        <div className="panel-heading">
          <div>
            <h2>
              Agent invitations{" "}
              <span className="count-badge">
                {invitations.filter((i) => i.status === "PENDING").length}{" "}
                pending
              </span>
            </h2>
            <p>Track invitations and control access before an agent joins</p>
          </div>
          <Mail size={17} />
        </div>
        <div
          className="invitation-tabs"
          role="group"
          aria-label="Filter invitations"
        >
          {[
            { value: "PENDING", text: "Pending" },
            { value: "ACCEPTED", text: "Accepted" },
            { value: "EXPIRED", text: "Expired" },
            { value: "REVOKED", text: "Revoked" },
            { value: "", text: "All" },
          ].map((x) => (
            <button
              key={x.value}
              aria-pressed={inviteStatus === x.value}
              className={inviteStatus === x.value ? "selected" : ""}
              onClick={() => setInviteStatus(x.value)}
            >
              {x.text}
            </button>
          ))}
        </div>
        {loading ? (
          <div className="team-state" role="status">
            Loading invitations…
          </div>
        ) : filteredInvitations.length === 0 ? (
          <div className="empty">
            <span className="empty-icon">
              <Mail size={22} />
            </span>
            <h3>
              {inviteStatus === "PENDING"
                ? "No pending invitations"
                : "No invitations in this view"}
            </h3>
            <p>
              Invite a Support Agent to help your team deliver better support.
            </p>
          </div>
        ) : (
          <div className="table-scroll">
            <table className="team-table">
              <thead>
                <tr>
                  {[
                    "Invited email",
                    "Role",
                    "Status",
                    "Sent / Expires",
                    "Actions",
                  ].map((x) => (
                    <th key={x} scope="col">
                      {x}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {filteredInvitations.map((i) => (
                  <tr key={i.id}>
                    <td>
                      <strong>{i.email}</strong>
                      <small className="cell-secondary">
                        Invited by {i.inviterName}
                      </small>
                    </td>
                    <td>
                      <span className="role-badge">{label(i.role)}</span>
                    </td>
                    <td>
                      <span
                        className={`status-badge ${i.status.toLowerCase()}`}
                      >
                        {label(i.status)}
                      </span>
                    </td>
                    <td>
                      <time dateTime={i.lastSentAt}>{date(i.lastSentAt)}</time>
                      <small className="cell-secondary">
                        Expires {date(i.expiresAt)}
                      </small>
                    </td>
                    <td>
                      {i.status === "PENDING" ? (
                        <div className="table-actions">
                          <button
                            className="subtle"
                            aria-label={`Resend invitation to ${i.email}`}
                            onClick={() =>
                              setAction({
                                kind: "resend",
                                id: i.id,
                                email: i.email,
                              })
                            }
                          >
                            <RefreshCw size={12} /> Resend
                          </button>
                          <button
                            className="subtle danger-text"
                            aria-label={`Revoke invitation to ${i.email}`}
                            onClick={() =>
                              setAction({
                                kind: "revoke",
                                id: i.id,
                                email: i.email,
                              })
                            }
                          >
                            Revoke
                          </button>
                        </div>
                      ) : (
                        <span className="muted">—</span>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </article>
      {inviteOpen && (
        <Modal
          title="Invite a Support Agent"
          onClose={() => setInviteOpen(false)}
          busy={busy}
        >
          <p className="modal-description">
            Send a private invitation to your company workspace. The link
            expires in 48 hours.
          </p>
          <form onSubmit={invite}>
            <Field
              label="Agent email address"
              name="email"
              type="email"
              required
              maxLength={254}
              placeholder="agent@company.com"
              autoFocus
            />
            <label>
              Company role
              <select aria-label="Company role" defaultValue="SUPPORT_AGENT">
                <option value="SUPPORT_AGENT">Support agent</option>
              </select>
              <small>
                Agents can access this workspace. Team administration stays with
                company admins.
              </small>
            </label>
            {fieldError && (
              <div className="error" role="alert">
                {fieldError}
              </div>
            )}
            <div className="modal-actions">
              <button
                type="button"
                className="subtle"
                onClick={() => setInviteOpen(false)}
                disabled={busy}
              >
                Cancel
              </button>
              <button className="primary compact" disabled={busy}>
                {busy ? "Sending…" : "Send invitation"}
                <Mail size={14} />
              </button>
            </div>
          </form>
        </Modal>
      )}
      {action && (
        <Modal
          title={
            action.kind === "status"
              ? action.next === "ACTIVE"
                ? "Reactivate agent?"
                : "Deactivate agent?"
              : action.kind === "resend"
                ? "Resend invitation?"
                : "Revoke invitation?"
          }
          onClose={() => setAction(null)}
          busy={busy}
        >
          <p className="modal-description">{action.email}</p>
          <p className="modal-copy">
            {action.kind === "status"
              ? action.next === "ACTIVE"
                ? "This agent will regain access to this company workspace."
                : "This agent will immediately lose access to this company workspace. Their account and memberships in other companies will remain."
              : action.kind === "resend"
                ? "A new invitation email will be sent. The previous link will immediately stop working."
                : "This invitation link will immediately stop working. You can send a new invitation later."}
          </p>
          <div className="modal-actions">
            <button
              className="subtle"
              onClick={() => setAction(null)}
              disabled={busy}
            >
              Cancel
            </button>
            <button
              className="primary compact"
              onClick={() => void confirm()}
              disabled={busy}
            >
              {busy ? "Please wait…" : "Confirm"}
            </button>
          </div>
        </Modal>
      )}
    </section>
  );
}
