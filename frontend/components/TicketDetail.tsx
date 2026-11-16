"use client";
import { useEffect, useState } from "react";
import Link from "next/link";
import {
  ArrowLeft,
  Ticket as TicketIcon,
  UserRound,
  Clock3,
} from "lucide-react";
import { TicketSla } from "./Sla";
import Conversation from "./Conversation";
import TicketActions from "./TicketActions";
import { api } from "./api";
import {
  Ticket,
  ticketDate,
  ticketLabel,
  wasTicketSubmitted,
  clearTicketSubmission,
} from "./tickets";
export default function TicketDetail({
  slug,
  id,
  staff = false,
}: {
  slug: string;
  id: string;
  staff?: boolean;
}) {
  const [ticket, setTicket] = useState<Ticket>(),
    [error, setError] = useState(""),
    [loading, setLoading] = useState(true);
  const [submittedId] = useState(() => (wasTicketSubmitted(id) ? id : null));
  useEffect(() => {
    clearTicketSubmission(id);
  }, [id]);
  useEffect(() => {
    let live = true;
    setLoading(true);
    setTicket(undefined);
    api<Ticket>(
      `/${staff ? "workspaces" : "support"}/${encodeURIComponent(slug)}/tickets/${encodeURIComponent(id)}`,
    )
      .then((t) => {
        if (live) {
          setTicket(t);
          setError("");
        }
      })
      .catch((e) => {
        if (live)
          setError(
            e instanceof Error ? e.message : "Unable to load this ticket.",
          );
      })
      .finally(() => {
        if (live) setLoading(false);
      });
    return () => {
      live = false;
    };
  }, [slug, id, staff]);
  useEffect(() => {
    const blocked = (event: Event) => {
      const detail = (event as CustomEvent).detail;
      if (detail?.slug === slug) setError(detail.message);
    };
    window.addEventListener("helpdesk-tenant-blocked", blocked);
    return () => window.removeEventListener("helpdesk-tenant-blocked", blocked);
  }, [slug]);
  const back = staff
    ? `/workspace/${slug}?view=tickets`
    : `/support/${slug}/tickets`;
  if (loading)
    return (
      <div className="team-state" role="status">
        Loading ticket…
      </div>
    );
  if (error || !ticket)
    return (
      <article className="panel">
        <div className="empty">
          <span className="empty-icon">
            <TicketIcon />
          </span>
          <h1>Ticket unavailable</h1>
          <p role="alert">{error || "Ticket not found."}</p>
          <Link href={back} className="subtle invitation-return">
            Back to tickets
          </Link>
        </div>
      </article>
    );
  return (
    <section className="ticket-detail">
      <Link className="back-link" href={back}>
        <ArrowLeft size={14} /> Back to tickets
      </Link>
      {submittedId === id && (
        <div className="notice" role="status">
          Ticket {ticket.reference} submitted successfully. Your support team
          can now view it.
        </div>
      )}
      <div className="ticket-detail-heading">
        <div>
          <span className="eyebrow">{ticket.reference}</span>
          <h1>{ticket.subject}</h1>
          <p>Created {ticketDate(ticket.createdAt, true)}</p>
        </div>
        <span className={`ticket-status ${ticket.status.toLowerCase()}`}>
          {ticketLabel(ticket.status)}
        </span>
      </div>
      <div className="ticket-detail-grid">
        <article className="panel">
          <div className="panel-heading">
            <h2>Request details</h2>
            <TicketIcon size={16} />
          </div>
          <div className="ticket-description">
            {ticket.description ||
              "No description was recorded for this legacy ticket."}
          </div>
          <div className="ticket-readonly">
            <Clock3 size={14} />
            {staff
              ? "Manage this request using the permitted actions below."
              : "Use the conversation below to contact your support team."}
          </div>
        </article>
        <aside className="panel ticket-metadata">
          <div className="panel-heading">
            <h2>Ticket information</h2>
          </div>
          <dl>
            <div>
              <dt>Reference</dt>
              <dd>{ticket.reference}</dd>
            </div>
            <div>
              <dt>Category</dt>
              <dd>{ticket.category || "Not recorded"}</dd>
            </div>
            <div>
              <dt>Priority</dt>
              <dd>
                <span
                  className={`ticket-priority ${ticket.priority.toLowerCase()}`}
                >
                  <i />
                  {ticketLabel(ticket.priority)}
                </span>
              </dd>
            </div>
            <div>
              <dt>Assigned agent</dt>
              <dd>
                {ticket.assignedAgent?.name || (
                  <span className="unassigned">Unassigned</span>
                )}
              </dd>
            </div>
            <div>
              <dt>Created</dt>
              <dd>{ticketDate(ticket.createdAt, true)}</dd>
            </div>
            <div>
              <dt>Last updated</dt>
              <dd>{ticketDate(ticket.updatedAt, true)}</dd>
            </div>
          </dl>
          <div className="ticket-customer">
            <UserRound size={16} />
            <div>
              <strong>
                {ticket.customer?.name || "Customer not recorded"}
              </strong>
              <small>{ticket.customer?.email || "Legacy record"}</small>
            </div>
          </div>
        </aside>
      </div>
      <TicketSla slug={slug} id={id} staff={staff} version={ticket.version} />
      <Conversation
        key={`${staff}-${id}`}
        endpoint={`/${staff ? "workspaces" : "support"}/${encodeURIComponent(slug)}/tickets/${id}/conversation`}
        version={ticket.version}
      />
      <TicketActions
        ticket={ticket}
        endpoint={`/${staff ? "workspaces" : "support"}/${encodeURIComponent(slug)}/tickets/${id}`}
        onUpdate={setTicket}
      />
    </section>
  );
}
