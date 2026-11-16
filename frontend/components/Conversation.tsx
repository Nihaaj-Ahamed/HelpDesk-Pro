"use client";
import { useEffect, useRef, useState } from "react";
import { LockKeyhole, Send, MessagesSquare } from "lucide-react";
import { api, ApiError, conversationSocketUrl } from "./api";
import { ticketDate, ticketLabel } from "./tickets";
type Message = {
  id: string;
  position: number;
  senderId: string;
  senderName: string;
  senderRole: string;
  visibility: "PUBLIC" | "INTERNAL";
  content: string;
  createdAt: string;
};
type History = {
  items: Message[];
  hasMore: boolean;
  cursor: number;
  canReply: boolean;
  canNote: boolean;
  viewerId: string;
};
export default function Conversation({
  endpoint,
  version,
}: {
  endpoint: string;
  version: number;
}) {
  const [messages, setMessages] = useState<Message[]>([]),
    [loading, setLoading] = useState(true),
    [error, setError] = useState(""),
    [notice, setNotice] = useState(""),
    [status, setStatus] = useState("Connecting"),
    [permissions, setPermissions] = useState({
      canReply: false,
      canNote: false,
    }),
    [draft, setDraft] = useState(""),
    [visibility, setVisibility] = useState<"PUBLIC" | "INTERNAL">("PUBLIC"),
    [busy, setBusy] = useState(false),
    [retry, setRetry] = useState(false);
  const end = useRef<HTMLDivElement>(null),
    thread = useRef<HTMLDivElement>(null),
    pending = useRef<string | null>(null),
    syncRef = useRef<() => Promise<void>>(async () => {}),
    cursorRef = useRef(0);
  useEffect(() => {
    let live = true,
      denied = false,
      running = false,
      again = false,
      first = true,
      reconnectTimer: ReturnType<typeof setTimeout> | undefined,
      socket: WebSocket | undefined,
      attempt = 0;
    let items: Message[] = [];
    cursorRef.current = 0;
    async function sync() {
      if (!live || denied) return;
      if (running) {
        again = true;
        return;
      }
      running = true;
      const follow =
        first ||
        !!(
          thread.current &&
          thread.current.scrollHeight -
            thread.current.scrollTop -
            thread.current.clientHeight <
            80
        );
      try {
        let more = true;
        while (more && live) {
          const h = await api<History>(
            endpoint + `?after=${cursorRef.current}&size=100`,
          );
          if (!live) return;
          const known = new Map(items.map((m) => [m.id, m]));
          for (const m of h.items) known.set(m.id, m);
          items = [...known.values()].sort((a, b) => a.position - b.position);
          cursorRef.current = h.cursor;
          setMessages(items);
          setPermissions(h);
          setError("");
          more = h.hasMore;
        }
        first = false;
        if (follow)
          requestAnimationFrame(() => {
            if (live && thread.current)
              thread.current.scrollTop = thread.current.scrollHeight;
          });
      } catch (e) {
        if (!live) return;
        setError(
          e instanceof Error ? e.message : "Unable to load conversation.",
        );
        if (e instanceof ApiError && [401, 403, 404, 423].includes(e.status)) {
          denied = true;
          items = [];
          setMessages([]);
          setPermissions({ canReply: false, canNote: false });
          socket?.close();
        }
      } finally {
        running = false;
        if (live) setLoading(false);
        if (again) {
          again = false;
          void sync();
        }
      }
    }
    syncRef.current = sync;
    async function connect() {
      if (!live || denied) return;
      try {
        const grant = await api<{ credential: string }>(endpoint + "/live", {});
        if (!live) return;
        socket = new WebSocket(conversationSocketUrl());
        socket.onopen = () => {
          socket?.send(JSON.stringify(grant));
        };
        socket.onmessage = (e) => {
          try {
            const frame = JSON.parse(e.data);
            if (frame.type === "ready") {
              attempt = 0;
              setStatus("Live updates");
              void sync();
            } else if (frame.type === "conversation.changed") void sync();
          } catch {}
        };
        socket.onclose = () => {
          if (live && !denied) {
            setStatus("Reconnecting - automatic refresh active");
            reconnectTimer = setTimeout(
              () => void connect(),
              Math.min(30000, 1000 * 2 ** Math.min(attempt++, 5)),
            );
            void sync();
          }
        };
        socket.onerror = () => socket?.close();
      } catch {
        if (live && !denied) {
          setStatus("Automatic refresh - live connection unavailable");
          reconnectTimer = setTimeout(() => void connect(), 10000);
        }
      }
    }
    void sync();
    void connect();
    const polling = setInterval(() => {
      if (document.visibilityState === "visible") void sync();
    }, 15000);
    const heartbeat = setInterval(() => {
      if (socket?.readyState === WebSocket.OPEN)
        socket.send(JSON.stringify({ type: "ping" }));
    }, 15000);
    const visible = () => {
      if (document.visibilityState === "visible") void sync();
    };
    document.addEventListener("visibilitychange", visible);
    return () => {
      live = false;
      clearInterval(polling);
      clearInterval(heartbeat);
      clearTimeout(reconnectTimer);
      socket?.close();
      document.removeEventListener("visibilitychange", visible);
    };
  }, [endpoint]);
  useEffect(() => {
    void syncRef.current();
  }, [version]);
  useEffect(() => {
    let live = true,
      reading = false,
      last = -1;
    const node = end.current;
    if (!node) return;
    async function mark() {
      if (!live || reading || document.visibilityState !== "visible" || loading)
        return;
      const rect = node!.getBoundingClientRect(),
        box = thread.current?.getBoundingClientRect();
      if (
        !box ||
        rect.top < box.top ||
        rect.bottom > box.bottom ||
        rect.top < 0 ||
        rect.bottom > window.innerHeight
      )
        return;
      const position = messages.at(-1)?.position || 0;
      if (position <= last) return;
      reading = true;
      try {
        await api(endpoint + "/read", { position });
        last = position;
        window.dispatchEvent(new Event("helpdesk-conversation-read"));
      } catch {
      } finally {
        reading = false;
      }
    }
    const observer = new IntersectionObserver(() => void mark(), {
      threshold: 1,
    });
    observer.observe(node);
    document.addEventListener("visibilitychange", mark);
    return () => {
      live = false;
      observer.disconnect();
      document.removeEventListener("visibilitychange", mark);
    };
  }, [messages, loading, endpoint]);
  async function send(e: React.FormEvent) {
    e.preventDefault();
    if (busy || !draft.trim()) return;
    setBusy(true);
    setError("");
    setNotice("");
    pending.current ||= crypto.randomUUID();
    try {
      await api<Message>(endpoint, {
        clientId: pending.current,
        content: draft,
        visibility,
      });
      pending.current = null;
      setRetry(false);
      setDraft("");
      setNotice(
        visibility === "INTERNAL"
          ? "Internal note saved privately."
          : "Reply sent successfully.",
      );
      void syncRef.current();
    } catch (e) {
      setRetry(true);
      setError(
        e instanceof Error
          ? e.message
          : "Message could not be sent. Retry this same message.",
      );
    } finally {
      setBusy(false);
    }
  }
  return (
    <article
      className="panel conversation-panel"
      aria-label="Ticket conversation"
    >
      <div className="panel-heading">
        <div>
          <h2>
            <MessagesSquare size={16} /> Conversation
          </h2>
          <p>{status}</p>
        </div>
        <button className="subtle" onClick={() => void syncRef.current()}>
          Refresh conversation
        </button>
      </div>
      {error && (
        <p role="alert" className="conversation-feedback error">
          {error}
        </p>
      )}
      {notice && (
        <p role="status" className="conversation-feedback notice">
          {notice}
        </p>
      )}
      <div
        ref={thread}
        className="conversation-thread"
        tabIndex={0}
        aria-label="Message thread"
        aria-busy={loading}
      >
        {loading ? (
          <p className="conversation-empty">Loading conversation...</p>
        ) : !messages.length ? (
          <div className="conversation-empty">
            <MessagesSquare size={22} />
            <h3>No messages yet</h3>
            <p>Start a conversation about this request.</p>
          </div>
        ) : (
          messages.map((m) => (
            <section
              key={m.id}
              className={`conversation-message ${m.visibility === "INTERNAL" ? "internal-note" : "public-reply"}`}
              data-message-id={m.id}
            >
              <header>
                <strong>{m.senderName}</strong>
                <span>{ticketLabel(m.senderRole)}</span>
                {m.visibility === "INTERNAL" && (
                  <span className="private-label">
                    <LockKeyhole size={12} /> Internal note - private
                  </span>
                )}
                <time>{ticketDate(m.createdAt, true)}</time>
              </header>
              <p>{m.content}</p>
            </section>
          ))
        )}
        <div ref={end} className="conversation-end" />
      </div>
      {permissions.canReply ? (
        <form className="conversation-composer" onSubmit={send}>
          {permissions.canNote && (
            <label className="conversation-mode">
              Message visibility
              <select
                aria-label="Message visibility"
                disabled={busy || retry}
                value={visibility}
                onChange={(e) => {
                  if (
                    e.target.value === "PUBLIC" &&
                    visibility === "INTERNAL" &&
                    draft.trim() &&
                    !window.confirm(
                      "Make this draft public? The customer will see its content.",
                    )
                  )
                    return;
                  setVisibility(e.target.value as "PUBLIC" | "INTERNAL");
                }}
              >
                <option value="PUBLIC">Public Reply</option>
                <option value="INTERNAL">Internal Note</option>
              </select>
            </label>
          )}
          <label htmlFor="conversation-message">
            {visibility === "INTERNAL" ? "Internal note" : "Public reply"}
          </label>
          {visibility === "INTERNAL" && (
            <p className="private-help">
              <LockKeyhole size={13} /> Only authorized company staff can read
              this note.
            </p>
          )}
          <textarea
            id="conversation-message"
            required
            maxLength={5000}
            rows={4}
            disabled={busy || retry}
            value={draft}
            onChange={(e) => setDraft(e.target.value)}
            placeholder={
              visibility === "INTERNAL"
                ? "Share private context with your team..."
                : "Write a reply..."
            }
          />
          <div className="conversation-composer-footer">
            <small>{draft.length}/5000</small>
            {retry && (
              <button
                type="button"
                className="subtle"
                onClick={() => {
                  if (
                    window.confirm(
                      "Discard this draft? A request that lost its response may already have been saved.",
                    )
                  ) {
                    pending.current = null;
                    setRetry(false);
                    setDraft("");
                    setError("");
                  }
                }}
              >
                Discard draft
              </button>
            )}
            <button className="primary" disabled={busy || !draft.trim()}>
              <Send size={14} />
              {busy
                ? "Sending..."
                : retry
                  ? "Retry send"
                  : visibility === "INTERNAL"
                    ? "Save internal note"
                    : "Send reply"}
            </button>
          </div>
        </form>
      ) : (
        !loading && (
          <p className="conversation-readonly">
            This conversation is read-only for your role or the ticket's current
            status. Closed tickets must be reopened before anyone can reply.
          </p>
        )
      )}
    </article>
  );
}
