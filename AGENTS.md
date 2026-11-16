HelpDesk Pro — Project Context and Agent Instructions
1. Product vision
HelpDesk Pro is a multi-tenant, enterprise-style customer support SaaS. Independent organizations create workspaces and manage support tickets from their own customers. Each organization gets a branded support portal and a private workspace for administrators and support agents. The product must be usable end to end, not a collection of disconnected CRUD screens or mock dashboards.

Primary goals: deliver trustworthy customer-to-company support workflows; protect each organization's data; demonstrate solid Java/Spring Boot backend engineering with a polished Next.js frontend; and develop the application incrementally as complete vertical slices.

Scope clarification: This is a portfolio-quality SaaS product, not a bespoke system for one company. Payment plans, billing, AI assistance, and third-party CRM integrations are optional future enhancements, not launch requirements.
2. Personas and authorization
- Platform Admin: administer the SaaS itself, monitor operational health, manage company account status, and review platform-level metadata and audit events. Do not grant routine access to private ticket content across tenants.
- Company Admin: configure their organization, manage support agents and invitations, define ticket categories and SLA rules, and access their organization's tickets and reports.
- Support Agent: work with tickets permitted within their company, reply to customers, add internal notes, and update ticket status within policy.
- Customer: submit tickets to a specific company's portal, view and reply to their own tickets, and confirm resolution or give feedback.
Roles are scoped to the appropriate organization. A person may eventually belong to more than one organization; authorization must always check both role and tenant membership, not merely a global role string. Customer identity is not established solely by knowing a public portal URL.
3. Core end-to-end journeys
Company onboarding
1. A company representative registers and verifies their identity.
2. The application creates an organization workspace and a unique, stable support-portal slug.
3. The representative becomes the first company admin.
4. The admin can configure the organization and invite agents.
5. Agents accept invitations and access only their assigned company workspace.
Customer support
1. A customer visits a company-specific support URL such as /support/abc-internet.
2. The customer registers/signs in or completes an approved identity-verification flow; public portal access does not imply company-customer verification.
3. The customer submits a ticket with subject, description, category and optional attachments.
4. The ticket is linked to the correct tenant and customer; a human-readable ticket reference is generated.
5. Company staff triage the ticket, set priority and assign an agent.
6. The agent and customer exchange messages in the ticket thread. Internal notes are visible only to authorized company staff.
7. The ticket progresses through defined statuses, is resolved, and can be reopened according to policy.
8. The customer can confirm resolution and provide feedback.
SLA and reporting
Company admins can configure response/resolution targets by priority. The system tracks due times, warns about approaching deadlines, flags breaches, and can escalate according to defined rules. Dashboards show real metrics computed from persisted data, never fabricated production figures.
4. Feature modules
1. Organizations and tenants: onboarding, unique slugs, company profile, workspace settings, activation/suspension, tenant-aware data access.
2. Identity and access: email verification, login/logout, secure password hashing, JWT access tokens, secure refresh-token lifecycle, password reset, agent invitations, role/membership authorization.
3. Tickets: creation, list/detail, filtering, search, categories, priorities, attachments, assignment, lifecycle, audit history.
4. Conversations: customer/agent replies, internal staff notes, unread indicators, authenticated ticket-scoped WebSocket updates (M5).
5. Notifications: in-app notifications and email for key events, with delivery failure handling.
6. SLA and automation: configurable targets, deadline calculation, scheduled reminders, escalation and overdue status.
7. Analytics: ticket volumes, backlog, resolution and first-response times, SLA compliance, workload and customer feedback.
8. Platform administration: organization status, operational metrics, safe administrative actions and audit logs.
5. Technical architecture
- Frontend: Next.js, TypeScript, Tailwind CSS; responsive web app with customer portal, company workspace, and platform-admin areas.
- Backend: Java 21, Spring Boot, REST API, Spring Security, Spring Data JPA/Hibernate.
- Database: PostgreSQL, versioned schema migrations using Flyway.
- API documentation: OpenAPI specification generated/documented via springdoc and browsable in Swagger UI. OpenAPI is an API description standard, not OpenAI/ChatGPT.
- Tests: JUnit 5, Mockito, Spring Boot integration tests, Testcontainers for PostgreSQL-backed tests; frontend component/e2e tests as appropriate.
- Local infrastructure: Docker Compose for supporting services, including PostgreSQL and Mailpit when email is introduced. The app may run locally or in containers; maintain a documented development workflow.
- CI: GitHub Actions for backend/frontend build, lint, and tests on pull requests and pushes. Docker is not a prerequisite for GitHub Actions.
- Deployment: deploy frontend and backend independently if useful; use a managed PostgreSQL service for a simpler initial public deployment. Keep secrets in environment configuration. Deploy only when a deployment target is selected.
- Optional later: Redis for justified caching/rate limits, Spring WebSocket for live conversations, MinIO/S3-compatible object storage for attachments, and an actual transactional email provider for production. Mailpit captures development email and is not a production email delivery provider.
Start as a modular monolith, not microservices. Keep clear package/module boundaries (e.g., identity, organizations, tickets, conversations, notifications, sla, reporting, platform). Within each module separate API/controller, application/service, domain rules, and persistence concerns where useful. Do not introduce infrastructure solely for buzzwords.
6. Multi-tenancy and security invariants
- Every tenant-owned record must have an unambiguous organization association, directly or through a safely constrained parent relationship.
- Every tenant-scoped read and write must enforce organization membership and authorization at the server. Never trust a tenant ID or role sent by the browser without checking it against the authenticated principal.
- Customer ticket access is restricted to their own tickets within the intended organization; agents and admins access only permitted organization data.
- Tenant filtering applies to searches, pagination, exports, aggregates, attachments, notifications and WebSocket subscriptions, not just basic CRUD endpoints.
- Prefer explicit organization-aware repository queries and service-level policy checks. Add automated cross-tenant negative tests for every relevant slice.
- Platform admins have controlled administrative capabilities, not unrestricted browsing of private customer messages.
- Passwords must be securely hashed; tokens, reset links and invitations must be time-limited and revocable where appropriate. Do not log secrets or sensitive ticket content.
- Validate input, enforce attachment type/size rules, protect file access, rate-limit sensitive endpoints when needed, and handle concurrent updates and ticket transitions safely.
- Use database constraints, indexes and transactions to preserve integrity. Avoid destructive schema changes without an explicit migration strategy.
7. Ticket lifecycle and conversation rules
M4 implemented statuses: OPEN, ASSIGNED, IN_PROGRESS, RESOLVED, CLOSED. Reopening is an explicit event that returns a ticket to OPEN/unassigned. Ticket priority: LOW, MEDIUM, HIGH, CRITICAL. See docs/milestone-4.md for the enforced role/transition rules; these supersede the initial proposed defaults below.

Define and document allowed transitions before implementing them. Customers may create tickets, reply to their own tickets, and request reopening subject to policy. Authorized staff may triage, assign, respond and resolve. Closing and reopening behavior must be consistent in UI, API, audit history and analytics. Use a durable audit trail for changes in assignment, priority and status.

M4 workflow and M5 messaging rules are implemented. Public replies and private staff notes are allowed on active and RESOLVED tickets without changing lifecycle. CLOSED tickets are read-only until explicitly reopened. Agents write only on their assigned tickets; Admins write company-wide; Customers write only on their own tickets. See docs/milestone-5.md for WebSocket, read-state and visibility rules.
8. Design system — Minimal Enterprise SaaS, Lime Edition
The supplied reference screenshot is the primary visual direction. It depicts a dense but orderly, premium light-mode SaaS dashboard, not a generic monochrome admin template.

Visual characteristics:
- Very light neutral-gray application canvas and sidebar; white cards and panels.
- Dark charcoal/near-black active sidebar item and prominent controls.
- Restrained bright lime-green accents for badges, charts, highlights and selective interactive elements; deeper green for complementary data visualization.
- Thin pale-gray borders, soft shadows, subtle corner radii and tight but readable spacing.
- Compact icon-and-label sidebar with grouped navigation, small counters and a user identity block near the bottom.
- Minimal top toolbar with context title, date/filter controls, utility actions and user avatar.
- KPI cards with small labels, clear numeric hierarchy and subtle trend indicators.
- High-density but legible ticket tables with avatars, small semantic status chips and clear row separation.
- Analytical panels with simple horizontal charts and concise supporting metrics.
Approximate starting palette, subject to screenshot-based tuning: canvas #F7F7F7, cards #FFFFFF, charcoal #171717, lime #C7F52E, borders #E8E8E8, secondary text #737373. These are visual approximations, not sampled exact colors.

Use consistent typography, spacing, form fields, loading states, empty states, error states and accessible focus indicators. Preserve contrast; lime should not be used as low-contrast body text. The customer-facing portal should use the same design language with a simpler, more approachable navigation. Responsive behavior is required. Do not add gratuitous gradients or oversized decorative elements. Do not substitute placeholder metrics for actual operational data.
9. Vertical-slice development contract
Build one complete user-facing capability at a time, including database migration, backend domain/service/API, authentication/authorization, frontend page/components, integration, and appropriate tests. Do not complete the entire backend first and then build the frontend. Each slice should leave the application runnable and demonstrable.

For every slice:
1. Inspect existing conventions and extend them rather than recreating infrastructure.
2. Define user journey, acceptance criteria, authorization boundaries and data model.
3. Implement the minimal full-stack path with migrations and error handling.
4. Connect the real frontend to real backend responses; avoid fake success states and hardcoded dashboard numbers.
5. Add relevant unit/integration/security tests, including cross-tenant cases where applicable.
6. Run feasible checks and report results honestly, including skipped or blocked checks.
7. Document any required manual environment setup, migrations and configuration.
Avoid broad rewrites unrelated to the slice. Prefer maintainable, explicit solutions over unnecessary abstraction. Keep API contracts and frontend types synchronized.
10. Proposed delivery sequence
Slice 1 — Secure company onboarding: scaffold web/API, PostgreSQL + Flyway, minimal authentication, verified company-admin registration, organization creation, unique portal slug, first workspace screen, Swagger UI and foundational tests. This is a genuine end-to-end slice, not just a landing page.

Slice 2 — Identity and team: login/logout refinement, password reset, agent invitations, tenant-scoped membership/role checks, team management UI and negative authorization tests.

Slice 3 — Customer portal and ticket creation: company-specific support portal, verified customer identity, ticket submission, company inbox, own-ticket view and tenant isolation.

Slice 4 — Ticket operations: assignment, priority/category management, filtering, lifecycle transitions, audit events and agent workspace.

Slice 5 — Conversations: ticket replies, internal notes, attachments and live updates if justified; enforce customer/staff visibility boundaries.

Slice 6 — Notifications and SLA: Mailpit-backed development emails, notification preferences, response/resolution deadlines, scheduled reminders and escalation.

Slice 7 — Reporting and platform operations: accurate KPI dashboards, performance reports, company administration and audit access.

Slice 8 — Hardening and deployment: comprehensive tests, security review, GitHub Actions CI, Docker-based workflows, environment documentation and public deployment when ready.

Slice order can be refined as dependencies emerge; keep each milestone end to end.
11. Non-goals and future options
Not required for the initial release: subscriptions/payments, AI ticket triage/chatbot, external CRM integrations, advanced omnichannel support, microservices, Kubernetes, Power BI embedding, and Redis before a measurable need exists. These may be added after the core support workflow works reliably.
12. Agent operating rules
- Treat this document as the product and engineering baseline. If a future user instruction changes a decision, follow the newer instruction and update this file when appropriate.
- Before implementing a feature, understand the affected tenant/role and full customer-to-company workflow.
- Never weaken authorization or bypass tenant checks to make a UI demo work.
- Keep the project functional after each vertical slice; no disconnected mock-only feature delivery.
- Follow the supplied visual reference and reusable design tokens consistently.
- Do not claim tests, integrations, deployments or security guarantees that were not actually verified.
- Ask for decisions only when they materially change product behavior, security or cost; otherwise use sensible, documented defaults.