"use client";
import { useEffect, useState } from "react";
import Link from "next/link";
import { Bell } from "lucide-react";
import { api } from "./api";
import { ticketDate, ticketLabel } from "./tickets";
type Notice = {
  id: string;
  title: string;
  reference: string;
  type: string;
  createdAt: string;
  readAt: string | null;
  url: string;
};
type Inbox = {
  items: Notice[];
  total: number;
  unread: number;
  page: number;
  size: number;
};
export const notificationTypes = [
  "CREATED",
  "ASSIGNMENT",
  "REPLY",
  "STATUS",
  "SLA_WARNING",
  "SLA_BREACH",
];
export function NotificationBell({ slug }: { slug: string }) {
  const [data, setData] = useState<Inbox>(),
    [open, setOpen] = useState(false),
    [error, setError] = useState("");
  const endpoint = `/companies/${encodeURIComponent(slug)}/notifications`;
  useEffect(() => {
    let live = true;
    const update = () =>
      api<Inbox>(endpoint + "?size=5")
        .then((r) => {
          if (live) {
            setData(r);
            setError("");
          }
        })
        .catch(() => {
          if (live) setError("Notifications unavailable");
        });
    void update();
    const timer = setInterval(() => {
      if (document.visibilityState === "visible") void update();
    }, 15000);
    return () => {
      live = false;
      clearInterval(timer);
    };
  }, [endpoint]);
  return (
    <div className="notification-bell">
      <button
        className="icon-button"
        aria-label={`Notifications${data ? `, ${data.unread} unread` : ""}`}
        aria-expanded={open}
        onClick={() => setOpen(!open)}
      >
        <Bell size={17} />
        {!!data?.unread && <span className="bell-count">{data.unread}</span>}
      </button>
      {open && (
        <div className="notification-dropdown">
          <strong>Notifications</strong>
          {error && <p role="alert">{error}</p>}
          {!data && !error && <p>Loading...</p>}
          {data?.items.map((n) => (
            <Link
              key={n.id}
              className={!n.readAt ? "notification-unread" : ""}
              href={n.url}
              onClick={() => {
                void api(endpoint + `/${n.id}/read`, {});
                setOpen(false);
              }}
            >
              <strong>{n.title}</strong>
              <span>{n.reference}</span>
              <small>{ticketDate(n.createdAt, true)}</small>
            </Link>
          ))}
          {data && !data.items.length && <p>No notifications yet.</p>}
          <Link href={`/notifications/${slug}`}>
            View all notifications and preferences
          </Link>
        </div>
      )}
    </div>
  );
}
export function NotificationCenter({ slug }: { slug: string }) {
  const [data, setData] = useState<Inbox>(),
    [type, setType] = useState(""),
    [unread, setUnread] = useState(false),
    [page, setPage] = useState(0),
    [error, setError] = useState(""),
    [notice, setNotice] = useState(""),
    [version, setVersion] = useState(0),
    [busy, setBusy] = useState(false),
    [prefs, setPrefs] = useState<{
      emailEnabled: boolean;
      categories: string[];
    }>();
  const endpoint = `/companies/${encodeURIComponent(slug)}/notifications`;
  useEffect(() => {
    let live = true;
    setData(undefined);
    api<Inbox>(`${endpoint}?type=${type}&unread=${unread}&page=${page}`)
      .then((r) => {
        if (live) {
          setData(r);
          setError("");
        }
      })
      .catch((e) => {
        if (live) setError(e.message);
      });
    return () => {
      live = false;
    };
  }, [endpoint, type, unread, page, version]);
  useEffect(() => {
    let live = true;
    api<{ emailEnabled: boolean; categories: string[] }>(
      endpoint + "/preferences",
    )
      .then((p) => {
        if (live) setPrefs(p);
      })
      .catch((e) => {
        if (live) setError(e.message);
      });
    return () => {
      live = false;
    };
  }, [endpoint]);
  async function change(path: string, value: unknown = {}) {
    setBusy(true);
    try {
      await api(endpoint + path, value);
      setVersion((v) => v + 1);
      setNotice("Saved successfully.");
      setError("");
    } catch (e) {
      setError(e instanceof Error ? e.message : "Unable to save.");
    } finally {
      setBusy(false);
    }
  }
  return (
    <>
      <div className="panel-heading">
        <div>
          <h1>Notifications</h1>
          <p>Updates for your account in this company</p>
        </div>
        <button
          className="subtle"
          disabled={busy}
          onClick={() => void change("/read-all")}
        >
          Mark all as read
        </button>
      </div>
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
      <article className="panel">
        <div className="ticket-filters">
          <select
            aria-label="Notification type"
            value={type}
            onChange={(e) => {
              setType(e.target.value);
              setPage(0);
            }}
          >
            <option value="">All types</option>
            {notificationTypes.map((t) => (
              <option key={t}>{t}</option>
            ))}
          </select>
          <label className="check-row">
            <input
              type="checkbox"
              checked={unread}
              onChange={(e) => {
                setUnread(e.target.checked);
                setPage(0);
              }}
            />
            Unread only
          </label>
        </div>
        {!data ? (
          <p className="team-state">
            {error
              ? "Please retry or change the filter."
              : "Loading notifications..."}
          </p>
        ) : data.items.length ? (
          <div className="notification-list">
            {data.items.map((n) => (
              <div
                key={n.id}
                className={!n.readAt ? "notification-unread" : ""}
              >
                <div>
                  <Link
                    href={n.url}
                    onClick={() => void change(`/${n.id}/read`)}
                  >
                    {n.title} - {n.reference}
                  </Link>
                  <small>
                    {ticketLabel(n.type)} | {ticketDate(n.createdAt, true)}
                  </small>
                </div>
                {!n.readAt && (
                  <button
                    className="subtle"
                    disabled={busy}
                    onClick={() => void change(`/${n.id}/read`)}
                  >
                    Mark as read
                  </button>
                )}
              </div>
            ))}
          </div>
        ) : (
          <p className="empty">No matching notifications.</p>
        )}
        {data && (
          <div className="ticket-pagination">
            <span>{data.total} notifications</span>
            <button
              className="subtle"
              disabled={!page || busy}
              onClick={() => setPage((p) => p - 1)}
            >
              Previous
            </button>
            <button
              className="subtle"
              disabled={(page + 1) * data.size >= data.total || busy}
              onClick={() => setPage((p) => p + 1)}
            >
              Next
            </button>
          </div>
        )}
      </article>
      {prefs && (
        <article className="panel preferences-panel">
          <h2>Notification preferences</h2>
          <p>
            In-app operational updates stay available. Choose optional email
            categories.
          </p>
          <label className="check-row">
            <input
              type="checkbox"
              checked={prefs.emailEnabled}
              onChange={(e) =>
                setPrefs({ ...prefs, emailEnabled: e.target.checked })
              }
            />
            Enable optional emails
          </label>
          {notificationTypes.map((t) => (
            <label className="check-row" key={t}>
              <input
                type="checkbox"
                checked={prefs.categories.includes(t)}
                onChange={(e) =>
                  setPrefs({
                    ...prefs,
                    categories: e.target.checked
                      ? [...prefs.categories, t]
                      : prefs.categories.filter((x) => x !== t),
                  })
                }
              />
              {ticketLabel(t)}
            </label>
          ))}
          <button
            className="primary"
            disabled={busy}
            onClick={() => void change("/preferences", prefs)}
          >
            Save preferences
          </button>
        </article>
      )}
    </>
  );
}
