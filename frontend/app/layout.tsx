import "./globals.css";
import "./team.css";
import "./tickets.css";
import "./operations.css";
import type { Metadata } from "next";
export const metadata: Metadata = {
  title: "HelpDesk Pro | Your support workspace",
  description: "A focused workspace for better customer support.",
};
export default function Layout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en">
      <body>{children}</body>
    </html>
  );
}
