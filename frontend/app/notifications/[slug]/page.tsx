"use client";
import { use, useEffect, useState } from "react";
import Link from "next/link";
import { api, refresh } from "../../../components/api";
import {
  NotificationCenter,
  NotificationBell,
} from "../../../components/Notifications";
export default function NotificationsPage({
  params,
}: {
  params: Promise<{ slug: string }>;
}) {
  const { slug } = use(params),
    [context, setContext] = useState<{ name: string; role: string }>(),
    [error, setError] = useState("");
  useEffect(() => {
    let live = true;
    (async () => {
      try {
        if (!(await refresh())) {
          window.location.replace("/login");
          return;
        }
        const c = await api<{ name: string; role: string }>(
          `/companies/${slug}/notifications/context`,
        );
        if (live) setContext(c);
      } catch (e) {
        if (live)
          setError(
            e instanceof Error ? e.message : "Company access unavailable.",
          );
      }
    })();
    return () => {
      live = false;
    };
  }, [slug]);
  useEffect(() => {
    const blocked = (e: Event) => {
      const detail = (e as CustomEvent).detail;
      if (detail?.slug === slug) {
        setError(detail.message);
        setContext(undefined);
      }
    };
    window.addEventListener("helpdesk-tenant-blocked", blocked);
    return () => window.removeEventListener("helpdesk-tenant-blocked", blocked);
  }, [slug]);
  return (
    <div className="staff-ticket-page">
      <header className="portal-header">
        <Link
          href={
            context?.role === "CUSTOMER"
              ? `/support/${slug}`
              : `/workspace/${slug}`
          }
        >
          {context?.name || "HelpDesk Pro"}
        </Link>
        {context && <NotificationBell slug={slug} />}
      </header>
      <main className="staff-ticket-content">
        {error ? (
          <p className="error" role="alert">
            {error}
          </p>
        ) : context ? (
          <NotificationCenter slug={slug} />
        ) : (
          <p role="status">Checking access...</p>
        )}
      </main>
    </div>
  );
}
