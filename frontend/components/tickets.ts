export type Category = { id: string; name: string };
// A bounded, in-memory flash marker survives route remounts after a real 201.
// It contains no ticket content or credentials and disappears on reload.
let submittedTicketId: string | null = null;
export function rememberTicketSubmission(id: string) {
  submittedTicketId = id;
}
export function wasTicketSubmitted(id: string) {
  return submittedTicketId === id;
}
export function clearTicketSubmission(id: string) {
  if (submittedTicketId === id) submittedTicketId = null;
}
export type Ticket = {
  id: string;
  version: number;
  unreadCount: number;
  slaState: string | null;
  reference: string;
  subject: string;
  description: string | null;
  categoryId: string | null;
  category: string | null;
  priority: string;
  status: string;
  createdAt: string | null;
  updatedAt: string | null;
  customer: { id: string; name: string; email: string } | null;
  assignedAgent: { id: string; name: string } | null;
};
export type TicketPage = {
  items: Ticket[];
  total: number;
  page: number;
  size: number;
};
export type TicketDashboard = {
  counts: {
    total: number;
    open: number;
    resolved: number;
    closed: number;
    unassigned: number;
    unread: number;
  };
  recent: Ticket[];
};
export type PortalInfo = {
  name: string;
  slug: string;
  categories: Category[];
  priorities: string[];
};
export const statuses = [
  "OPEN",
  "ASSIGNED",
  "IN_PROGRESS",
  "RESOLVED",
  "CLOSED",
];
export function ticketLabel(value: string) {
  return value
    .toLowerCase()
    .replaceAll("_", " ")
    .replace(/^./, (x) => x.toUpperCase());
}
export function ticketDate(value: string | null, full = false) {
  return value
    ? new Intl.DateTimeFormat(
        "en",
        full
          ? { dateStyle: "medium", timeStyle: "short" }
          : { dateStyle: "medium" },
      ).format(new Date(value))
    : "Not recorded";
}
