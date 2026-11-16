"use client";
import { useState } from "react";
import Link from "next/link";
import { ArrowLeft, ArrowUpRight, ShieldCheck } from "lucide-react";
import { api, ApiError } from "./api";
import Field from "./Field";
import { PortalInfo, Ticket, ticketLabel } from "./tickets";
export default function CreateTicket({
  info,
  onCreated,
}: {
  info: PortalInfo;
  onCreated: (ticket: Ticket) => void;
}) {
  const [busy, setBusy] = useState(false),
    [error, setError] = useState(""),
    [fields, setFields] = useState<Record<string, string>>({});
  async function submit(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault();
    if (busy) return;
    setBusy(true);
    setError("");
    setFields({});
    const data = Object.fromEntries(new FormData(e.currentTarget));
    try {
      const ticket = await api<Ticket>(
        `/support/${encodeURIComponent(info.slug)}/tickets`,
        data,
      );
      onCreated(ticket);
    } catch (e) {
      setError(
        e instanceof Error ? e.message : "Could not submit your ticket.",
      );
      if (e instanceof ApiError) setFields(e.fields);
    } finally {
      setBusy(false);
    }
  }
  return (
    <section className="create-ticket">
      <Link className="back-link" href={`/support/${info.slug}/tickets`}>
        <ArrowLeft size={14} /> My tickets
      </Link>
      <div className="portal-page-heading">
        <div>
          <span className="eyebrow">LET’S GET IT SORTED</span>
          <h1>Create a support ticket</h1>
          <p>Tell {info.name} what you need help with.</p>
        </div>
      </div>
      <div className="create-ticket-grid">
        <article className="panel">
          <div className="panel-heading">
            <h2>How can we help?</h2>
          </div>
          <form onSubmit={submit} className="ticket-form">
            <Field
              label="Subject"
              name="subject"
              required
              maxLength={200}
              placeholder="A short summary of your request"
              error={fields.subject}
            />
            <label htmlFor="description">
              Detailed description
              <textarea
                id="description"
                name="description"
                required
                maxLength={10000}
                rows={8}
                placeholder="Describe the issue, what you expected, and any steps you have already tried."
                aria-invalid={!!fields.description}
                aria-describedby={
                  fields.description ? "description-error" : undefined
                }
              />
              {fields.description && (
                <small id="description-error" className="field-error">
                  {fields.description}
                </small>
              )}
            </label>
            <div className="form-row">
              <label htmlFor="ticket-category">
                Category
                <select
                  id="ticket-category"
                  aria-label="Category"
                  name="categoryId"
                  required
                  defaultValue={
                    info.categories.find((c) => c.name === "General")?.id ||
                    info.categories[0]?.id
                  }
                >
                  {info.categories.map((c) => (
                    <option value={c.id} key={c.id}>
                      {c.name}
                    </option>
                  ))}
                </select>
              </label>
              <label htmlFor="ticket-priority">
                Priority
                <select
                  id="ticket-priority"
                  aria-label="Priority"
                  name="priority"
                  required
                  defaultValue={
                    info.priorities.includes("MEDIUM")
                      ? "MEDIUM"
                      : info.priorities[0]
                  }
                >
                  {info.priorities.map((p) => (
                    <option value={p} key={p}>
                      {ticketLabel(p)}
                    </option>
                  ))}
                </select>
              </label>
            </div>
            {error && (
              <div className="error" role="alert">
                {error}
              </div>
            )}
            <div className="ticket-form-actions">
              <Link className="subtle" href={`/support/${info.slug}/tickets`}>
                Cancel
              </Link>
              <button
                className="primary compact"
                disabled={busy || !info.categories.length}
              >
                {busy ? "Submitting…" : "Submit ticket"}
                <ArrowUpRight size={15} />
              </button>
            </div>
          </form>
        </article>
        <aside className="panel request-guidance">
          <span className="empty-icon">
            <ShieldCheck size={22} />
          </span>
          <h2>A clear request helps us help you</h2>
          <p>
            Include relevant dates, error messages and the steps you have
            already tried.
          </p>
          <p>
            Keep passwords, payment details and other sensitive credentials out
            of your request.
          </p>
          <div>
            <strong>What happens next?</strong>
            <p>
              Your ticket starts as Open and unassigned. You can view its status
              and details from My Tickets.
            </p>
          </div>
        </aside>
      </div>
    </section>
  );
}
