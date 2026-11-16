# HelpDesk Pro

Milestones 1-7: company onboarding, secure sessions, team invitations, customer portals, tenant-isolated tickets, assignment, lifecycle, activity history, live conversations, notifications, SLA tracking, analytics, reports and platform administration.

## Start locally

Prerequisites: Docker Desktop running, Node.js 22.9+ (or 24), npm. Java 21 and Maven 3.9+ are needed only when running the API outside Docker.

1. Copy `.env.example` to `.env`. Replace `DATABASE_PASSWORD` with a local password and `JWT_SECRET` with a random secret of at least 32 bytes. Generate a secret with `node -e "console.log(require('crypto').randomBytes(48).toString('base64url'))"`. Never commit `.env`.
2. From the root run `docker compose up --build -d`. This starts PostgreSQL and builds/runs the Java 21 API. Flyway applies the schema automatically. First build downloads dependencies.
3. In `frontend`, run `npm ci`, then `npm run dev`.
4. Open http://localhost:3000/register. Register an owner, company and unique slug. The app opens `/workspace/<slug>`. Sign out using the icon beside your profile; sign in again at `/login`.

Open **Team → Invite Agent** as a company admin to invite a Support Agent. Read the captured invitation at http://localhost:8025 and open its private link in a separate browser profile, or sign out of the owner account first. New agents create an account; existing users sign in with the invited email. See [the complete Milestone 2 testing steps](docs/milestone-2.md).

Customers use `/support/<company-slug>` to register/sign in, verify portal access through Mailpit, submit tickets and track their own requests. Admins and Agents review company tickets from the workspace **Tickets** section. See [the Milestone 3 guide](docs/milestone-3.md) for the complete testing flow, API and limitations.

API: http://localhost:8080. Swagger UI: http://localhost:8080/swagger-ui/index.html. OpenAPI JSON: http://localhost:8080/v3/api-docs. `docker compose logs -f api` shows structured application logs. `docker compose down` stops services and preserves the database volume.

The frontend defaults to `http://localhost:8080`. Copy `frontend/.env.example` to `frontend/.env.local` only to override it. Keep frontend and API on the same site for the Strict refresh cookie (localhost ports are same-site). Set `FRONTEND_ORIGIN` to the exact frontend origin. Do not mix `127.0.0.1` and `localhost` in browser URLs.

To run the API on the host: stop the Compose API with `docker compose stop api`, start PostgreSQL and Mailpit with `docker compose up -d postgres mailpit`, export `DATABASE_PASSWORD` and `JWT_SECRET` from your local `.env` into the shell, then run `mvn spring-boot:run` in `backend`. Spring does not automatically load the root `.env`. Optional overrides include `DATABASE_URL`, `DATABASE_USER`, `FRONTEND_ORIGIN`, `SECURE_COOKIE`, `MAIL_HOST`, `MAIL_PORT` and `MAIL_FROM`.

## Checks

- Backend: `cd backend` then `mvn verify`. Docker must be available. Tests start isolated PostgreSQL 17 and Mailpit containers, apply Flyway and test the real HTTP/security/database/SMTP paths. They never use the development database and do not silently skip when Docker is missing.
- Frontend: `cd frontend` then `npm run build` and `npm run typecheck`.
- Browser journeys: with API and Mailpit running, `cd frontend` then `npm run build` and `npm run test:e2e`. Playwright starts its own frontend on port 3000; stop `npm run dev` first, or explicitly set `PLAYWRIGHT_REUSE_SERVER=true`. Tests create unique companies and memberships in the development database. Chrome must be installed, or install Playwright Chromium and set `PLAYWRIGHT_CHANNEL=chromium`.
- GitHub Actions runs backend verification, frontend production builds, and the M1-M7 browser suite with PostgreSQL and Mailpit on Docker-equipped Ubuntu runners.

If Maven cannot use its default cache, pass `"-Dmaven.repo.local=../.m2/repository"` to Maven. The cache is ignored.

## Security and architecture

The API is a modular Spring Boot monolith with identity, organizations, team, portal, tickets, conversations, notifications, SLA, reporting and platform modules. Account persistence uses Spring Data JPA; explicit parameterized SQL handles session locks, membership policy, scoped ticket queries and aggregates. PostgreSQL constraints enforce uniqueness and tenant relationships. Company-owner registration creates the account, company, default categories, admin membership and initial session transactionally; a conflict rolls everything back.

Access JWTs are HS256 signed, issuer/audience checked and valid for 10 minutes. Access tokens stay in browser memory, never localStorage. Refresh tokens are 256-bit random values in an HttpOnly, SameSite=Strict cookie restricted to `/api/auth`; only SHA-256 digests are persisted. Rotation locks a session family. Reusing a rotated token revokes the entire family. Families have an absolute seven-day lifetime; refreshing does not extend it. Logout revokes the family. Workspace endpoints check active sessions, so revoked access JWTs stop working immediately. Expired families are removed hourly.

All cookie-writing endpoints require the exact configured Origin. CORS allows only that origin; CSRF middleware is disabled because authenticated resources use explicit bearer headers and cookie endpoints independently enforce Origin. HTTPS and `SECURE_COOKIE=true` are required for a public deployment. JWT signing secrets must be generated privately, shared only with API instances and rotated deliberately (rotation signs everyone out).

Workspace slugs are addresses, never credentials. Staff workspace reads require an active Admin/Agent membership before company-scoped queries. Nonmembers receive 404; Customer memberships explicitly receive 403 on staff routes. Customer ticket queries additionally require ownership by the authenticated account. No browser-provided role, customer ID or company ID is trusted. Future writes must continue checking the required company role.

Passwords require 12 characters and at most 72 UTF-8 bytes and use BCrypt cost 12. Authentication failures use the same response for unknown emails and wrong passwords. Login and registration are limited to 20 attempts per minute per remote IP. This limiter is bounded, in-memory and intended for one API instance; add a shared edge limiter before scaling. Do not enable request-body/header logging, which could expose credentials. The API does not log tokens or passwords.

Owner registration proves control of a password, **not ownership of the email address**. Agent invitation acceptance proves ownership through a private, single-use emailed link plus the matching account. Customer portals now require their own company-bound email proof before granting Customer access. Both record verified email ownership; password reset and general owner email verification remain follow-up work. Ticket submission, assignment, lifecycle and priority changes, scoped queues and audit history are active; public conversations, private notes and live updates are active; attachments remain deferred. Ticket/agent counts are database queries, and analytics use persisted aggregates. No operational statistics are seeded, and no public deployment has been made.

The supplied root instructions were named `agent.md`. An identical `AGENTS.md` was added as the canonical agent entry point. `docs/design-reference.png` is the UI reference.

See [the verification record](docs/verification.md) for executed checks, screenshots and environment limitations.

M4 adds ticket assignment, lifecycle, priority controls, role-aware activity timelines and staff queues. See [Milestone 4](docs/milestone-4.md) for workflow rules, concurrency, migration and local walkthrough. 

M5 adds persisted conversations, private staff notes, scoped WebSocket updates and per-user unread indicators. See [Milestone 5](docs/milestone-5.md) for permissions, messaging rules, REST/WebSocket contracts and verification. Attachments remain deferred.

M6-M7 add notifications, durable email delivery, SLA policies/escalation, analytics, CSV reports and controlled platform administration. See [setup, provisioning and metric rules](docs/milestones-6-7.md).
