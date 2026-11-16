"use client";
import { useEffect, useState, use } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import Link from "next/link";
import Reporting, { AnalyticsPreview } from "../../../components/Reporting";
import { NotificationCenter } from "../../../components/Notifications";
import SlaManagement from "../../../components/Sla";
import { NotificationBell } from "../../../components/Notifications";
import TicketList from "../../../components/TicketList";
import TeamManagement from "../../../components/TeamManagement";
import {
  Layers3,
  LayoutDashboard,
  Ticket,
  Users,
  ChartNoAxesCombined,
  LogOut,
  ChevronDown,
  ArrowUpRight,
  Inbox,
  ShieldCheck,
  Menu,
  Check,
  CalendarDays,
  MessageSquare,
} from "lucide-react";
import {
  api,
  refresh,
  setAccess,
  Me,
  Workspace,
  ApiError,
} from "../../../components/api";
export default function Dashboard({
  params,
}: {
  params: Promise<{ slug: string }>;
}) {
  const { slug } = use(params),
    router = useRouter();
  const query = useSearchParams();
  const [me, setMe] = useState<Me>(),
    [workspace, setWorkspace] = useState<Workspace>(),
    [error, setError] = useState(""),
    [tab, setTab] = useState("Overview"),
    [mobile, setMobile] = useState(false);
  useEffect(() => {
    const tabs: Record<string, string> = {
      overview: "Overview",
      tickets: "Tickets",
      team: "Team",
      analytics: "Analytics",
      performance: "Performance",
      sla: "Sla",
      reports: "Reports",
      notifications: "Notifications",
      settings: "Settings",
      mine: "Mine",
    };
    const selected = query.get("view");
    if (selected && tabs[selected]) setTab(tabs[selected]);
  }, [query]);
  useEffect(() => {
    let live = true;
    const load = async (initial = false) => {
      try {
        if (initial && !(await refresh())) {
          router.replace("/login");
          return;
        }
        const [m, w] = await Promise.all([
          api<Me>("/me"),
          api<Workspace>(`/workspaces/${encodeURIComponent(slug)}`),
        ]);
        if (live) {
          setMe(m);
          setWorkspace(w);
          setError("");
        }
      } catch (e) {
        if (e instanceof ApiError && e.status === 401) {
          setAccess(null);
          router.replace("/login");
          return;
        }
        if (live) {
          if (e instanceof ApiError && [403, 404, 423].includes(e.status)) {
            setWorkspace(undefined);
            setMe(undefined);
          }
          setError(
            e instanceof Error ? e.message : "Unable to load workspace.",
          );
        }
      }
    };
    void load(true);
    const focus = () => {
      void load();
    };
    const interval = window.setInterval(focus, 20000);
    window.addEventListener("focus", focus);
    window.addEventListener("helpdesk-conversation-read", focus);
    return () => {
      live = false;
      window.clearInterval(interval);
      window.removeEventListener("focus", focus);
      window.removeEventListener("helpdesk-conversation-read", focus);
    };
  }, [slug, router]);
  async function logout() {
    try {
      await api("/auth/logout", {});
      setAccess(null);
      router.replace("/login");
    } catch {
      setError("Could not sign out. Please try again.");
    }
  }
  if (!workspace || !me)
    return (
      <main className="loading">
        <span className="brand-icon">
          <Layers3 />
        </span>
        <p role="status">{error || "Opening your workspace…"}</p>
        {error && <a href="/workspaces">Choose an active workspace</a>}
      </main>
    );
  const nav = [
    {
      label: "Overview",
      icon: LayoutDashboard,
      tab: "Overview",
      view: "overview",
    },
    { label: "Tickets", icon: Ticket, tab: "Tickets", view: "tickets" },
    ...(workspace.role === "COMPANY_ADMIN"
      ? [
          { label: "Team", icon: Users, tab: "Team", view: "team" },
          {
            label: "Analytics",
            icon: ChartNoAxesCombined,
            tab: "Analytics",
            view: "analytics",
          },
          {
            label: "Agent Performance",
            icon: Users,
            tab: "Performance",
            view: "performance",
          },
          {
            label: "SLA Management",
            icon: ShieldCheck,
            tab: "Sla",
            view: "sla",
          },
          {
            label: "Reports",
            icon: ChartNoAxesCombined,
            tab: "Reports",
            view: "reports",
          },
        ]
      : [
          { label: "My Tickets", icon: Ticket, tab: "Mine", view: "mine" },
          {
            label: "My Performance",
            icon: ChartNoAxesCombined,
            tab: "Performance",
            view: "performance",
          },
        ]),
    {
      label: "Notifications",
      icon: MessageSquare,
      tab: "Notifications",
      view: "notifications",
    },
    ...(workspace.role === "COMPANY_ADMIN"
      ? [
          {
            label: "Settings",
            icon: ShieldCheck,
            tab: "Settings",
            view: "settings",
          },
        ]
      : []),
  ];
  return (
    <div className="workspace">
      <aside
        id="workspace-navigation"
        className={mobile ? "sidebar show" : "sidebar"}
      >
        <div className="workspace-brand">
          <span className="brand-icon">
            <Layers3 size={20} />
          </span>
          <div>
            <strong>{workspace.name}</strong>
            <small>Company workspace</small>
          </div>
          <ChevronDown size={14} />
        </div>
        <div className="nav-heading">WORKSPACE</div>
        {me.workspaces.length > 1 && (
          <select
            className="workspace-switcher"
            aria-label="Switch workspace"
            value={workspace.slug}
            onChange={(e) =>
              window.location.assign(`/workspace/${e.target.value}`)
            }
          >
            {me.workspaces.map((w) => (
              <option key={w.slug} value={w.slug}>
                {w.name}
              </option>
            ))}
          </select>
        )}
        <nav>
          {nav.map(({ label, icon: Icon, tab: navTab, view }) => (
            <button
              key={label}
              className={tab === navTab ? "nav-item active" : "nav-item"}
              onClick={() => {
                setTab(navTab);
                setMobile(false);
                router.replace(
                  `/workspace/${slug}${label === "Overview" ? "" : `?view=${view}`}`,
                );
              }}
            >
              <Icon size={16} />
              {label}
              {label === "Tickets" && (
                <span className="counter">{workspace.totalTickets}</span>
              )}
            </button>
          ))}
        </nav>
        <div className="sidebar-note">
          <ShieldCheck size={19} />
          <strong>A strong start</strong>
          <p>
            Your private workspace is ready. Great support starts with a focused
            team.
          </p>
          <span className="pill">
            {workspace.role === "COMPANY_ADMIN"
              ? "COMPANY ADMIN"
              : "SUPPORT AGENT"}
          </span>
        </div>
        <div className="profile">
          <span className="avatar">{me.fullName.charAt(0)}</span>
          <div>
            <strong>{me.fullName}</strong>
            <small>{me.email}</small>
          </div>
          <button
            className="icon-button"
            aria-label="Sign out"
            onClick={logout}
          >
            <LogOut size={16} />
          </button>
        </div>
      </aside>
      <div className="main-area">
        <header>
          <button
            className="icon-button mobile-toggle"
            aria-label="Toggle navigation"
            aria-expanded={mobile}
            aria-controls="workspace-navigation"
            onClick={() => setMobile(!mobile)}
          >
            <Menu size={20} />
          </button>
          <span className="header-title">
            <LayoutDashboard size={17} />
            {tab}
          </span>
          <span className="live">
            <i /> Live workspace
          </span>
          <div className="header-right">
            <NotificationBell slug={slug} />
            <span className="muted">{workspace.name}</span>
            <span className="avatar">{me.fullName.charAt(0)}</span>
          </div>
        </header>
        <main className="dashboard">
          {error && (
            <div className="error" role="alert">
              {error}
            </div>
          )}
          <div className="page-heading">
            <div>
              <span className="eyebrow">YOUR SUPPORT, IN FOCUS</span>
              <h1>{tab === "Overview" ? "Workspace overview" : tab}</h1>
              <p className="muted">
                {tab === "Overview"
                  ? `Welcome back, ${me.fullName.split(" ")[0]}. Here’s where things stand.`
                  : "Everything your team needs, in one place."}
              </p>
            </div>
            <span className="date">
              <CalendarDays size={14} />
              {new Intl.DateTimeFormat("en", { dateStyle: "medium" }).format(
                new Date(),
              )}
            </span>
          </div>
          {tab === "Overview" && (
            <>
              <section className="metrics">
                {[
                  {
                    label: "Total tickets",
                    value: workspace.totalTickets,
                    icon: Ticket,
                    note: "All tickets in your workspace",
                  },
                  {
                    label: "Open tickets",
                    value: workspace.openTickets,
                    icon: Inbox,
                    note: "Waiting for your team",
                  },
                  {
                    label: "Support agents",
                    value: workspace.agents,
                    icon: Users,
                    note: "Dedicated support members",
                  },
                  {
                    label: "Company admins",
                    value: workspace.admins,
                    icon: ShieldCheck,
                    note: "Managing your workspace",
                  },
                ].map(({ label, value, icon: Icon, note }) => (
                  <article className="metric" key={label}>
                    <div className="metric-label">
                      {label}
                      <Icon size={15} />
                    </div>
                    <strong>{value}</strong>
                    <small>{note}</small>
                  </article>
                ))}
              </section>
              <section
                className="workflow-summary"
                aria-label="Ticket workload"
              >
                {[
                  ["Unread messages", workspace.unreadMessages],
                  ["Unassigned", workspace.unassignedTickets],
                  ["Assigned", workspace.assignedTickets],
                  ["In progress", workspace.inProgressTickets],
                  ["Resolved", workspace.resolvedTickets],
                  ["Closed", workspace.closedTickets],
                  ["My active tickets", workspace.myTickets],
                ].map(([label, value]) => (
                  <div key={label}>
                    <span>{label}</span>
                    <strong>{value}</strong>
                  </div>
                ))}
              </section>
              <section className="panel-grid">
                <article className="panel">
                  <div className="panel-heading">
                    <div>
                      <h2>Ticket intake</h2>
                      <p>Customer requests, in one workspace</p>
                    </div>
                    <button
                      className="subtle"
                      onClick={() => setTab("Tickets")}
                    >
                      View tickets <ArrowUpRight size={13} />
                    </button>
                  </div>
                  <Empty
                    icon={<MessageSquare size={22} />}
                    title={
                      workspace.openTickets === 0
                        ? "Your inbox is clear"
                        : `${workspace.openTickets} open ${workspace.openTickets === 1 ? "ticket" : "tickets"}`
                    }
                    text="Customers can submit requests through your support portal. Review their tickets and details from the Tickets section."
                  />
                </article>
                <article className="panel">
                  <div className="panel-heading">
                    <div>
                      <h2>Your workspace is ready</h2>
                      <p>A solid foundation for your company</p>
                    </div>
                    <span className="lime-dot">
                      <Check size={15} />
                    </span>
                  </div>
                  <div className="checklist">
                    {[
                      "Company workspace created",
                      "Account secured",
                      workspace.role === "COMPANY_ADMIN"
                        ? "Administrator access enabled"
                        : "Support agent access enabled",
                    ].map((x) => (
                      <div key={x}>
                        <span>
                          <Check size={14} />
                        </span>
                        {x}
                        <small>Complete</small>
                      </div>
                    ))}
                  </div>
                  <div className="workspace-address">
                    <span>WORKSPACE ADDRESS</span>
                    <strong>/workspace/{workspace.slug}</strong>
                    <small>Access is restricted to company members.</small>
                    <Link
                      className="ticket-inline-link"
                      href={`/support/${slug}`}
                    >
                      Open customer support portal <ArrowUpRight size={12} />
                    </Link>
                  </div>
                </article>
              </section>
              <TicketList slug={slug} staff recent />
              <section className="bottom-grid">
                <article className="panel">
                  <div className="panel-heading">
                    <div>
                      <h2>Team</h2>
                      <p>The people behind your support</p>
                    </div>
                    <Users size={16} />
                  </div>
                  <div className="team-row">
                    <span className="avatar">{me.fullName.charAt(0)}</span>
                    <div>
                      <strong>{me.fullName}</strong>
                      <small>{me.email}</small>
                    </div>
                    <span className="pill">
                      {workspace.role === "COMPANY_ADMIN"
                        ? "Company admin"
                        : "Support agent"}
                    </span>
                  </div>
                  {workspace.agents === 0 && (
                    <p className="panel-foot">
                      No support agents yet.{" "}
                      {workspace.role === "COMPANY_ADMIN" ? (
                        <button
                          className="text-button"
                          onClick={() => setTab("Team")}
                        >
                          Invite your first agent
                        </button>
                      ) : (
                        "Your administrator can invite your teammates."
                      )}
                    </p>
                  )}
                </article>
                <AnalyticsPreview
                  slug={slug}
                  personal={workspace.role === "SUPPORT_AGENT"}
                />
              </section>
            </>
          )}
          {tab === "Tickets" && <TicketList slug={slug} staff />}
          {tab === "Team" && workspace.role === "COMPANY_ADMIN" && (
            <TeamManagement
              slug={slug}
              onChanged={() => {
                void api<Workspace>(`/workspaces/${encodeURIComponent(slug)}`)
                  .then(setWorkspace)
                  .catch(() => {});
              }}
            />
          )}
          {tab === "Sla" && workspace.role === "COMPANY_ADMIN" && (
            <SlaManagement slug={slug} />
          )}
          {tab === "Analytics" && workspace.role === "COMPANY_ADMIN" && (
            <Reporting slug={slug} />
          )}
          {tab === "Performance" && (
            <Reporting
              slug={slug}
              mode="performance"
              personal={workspace.role === "SUPPORT_AGENT"}
            />
          )}
          {tab === "Reports" && workspace.role === "COMPANY_ADMIN" && (
            <Reporting slug={slug} mode="reports" />
          )}
          {(tab === "Notifications" || tab === "Settings") && (
            <NotificationCenter slug={slug} />
          )}
          {tab === "Mine" && (
            <TicketList key="mine" slug={slug} staff initialQueue="MINE" />
          )}
          <footer>
            HelpDesk Pro <span>Private workspace · {workspace.name}</span>
          </footer>
        </main>
      </div>
    </div>
  );
}
function Empty({
  icon,
  title,
  text,
}: {
  icon: React.ReactNode;
  title: string;
  text: string;
}) {
  return (
    <div className="empty">
      <span className="empty-icon">{icon}</span>
      <h3>{title}</h3>
      <p>{text}</p>
    </div>
  );
}
