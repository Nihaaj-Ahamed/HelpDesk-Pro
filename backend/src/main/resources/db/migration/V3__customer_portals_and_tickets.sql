ALTER TABLE memberships DROP CONSTRAINT memberships_role_check;
ALTER TABLE memberships ADD CONSTRAINT memberships_role_check CHECK(role IN ('COMPANY_ADMIN','SUPPORT_AGENT','CUSTOMER'));
ALTER TABLE companies ADD COLUMN customer_max_priority varchar(16) NOT NULL DEFAULT 'HIGH' CHECK(customer_max_priority IN ('LOW','MEDIUM','HIGH'));

CREATE TABLE ticket_categories (
  id uuid PRIMARY KEY,
  company_id uuid NOT NULL REFERENCES companies(id),
  name varchar(80) NOT NULL,
  active boolean NOT NULL DEFAULT true,
  UNIQUE(company_id,name), UNIQUE(id,company_id)
);
INSERT INTO ticket_categories(id,company_id,name)
SELECT gen_random_uuid(),c.id,n.name FROM companies c CROSS JOIN (VALUES ('General'),('Technical support'),('Billing')) AS n(name);

CREATE TABLE portal_verifications (
  id uuid PRIMARY KEY,
  company_id uuid NOT NULL REFERENCES companies(id),
  account_id uuid NOT NULL REFERENCES accounts(id),
  token_hash varchar(64) NOT NULL UNIQUE,
  status varchar(16) NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','USED','REPLACED')),
  created_at timestamptz NOT NULL DEFAULT now(),
  expires_at timestamptz NOT NULL DEFAULT now()+interval '30 minutes'
);
CREATE UNIQUE INDEX portal_one_pending_proof ON portal_verifications(company_id,account_id) WHERE status='PENDING';
CREATE INDEX portal_proof_rate ON portal_verifications(company_id,account_id,created_at);

CREATE SEQUENCE ticket_reference_sequence;
ALTER TABLE tickets ADD COLUMN reference varchar(32) NOT NULL DEFAULT ('HDP-' || nextval('ticket_reference_sequence')::text) UNIQUE;
ALTER TABLE tickets ADD COLUMN customer_id uuid;
ALTER TABLE tickets ADD COLUMN description text;
ALTER TABLE tickets ADD COLUMN category_id uuid;
ALTER TABLE tickets ADD COLUMN priority varchar(16) NOT NULL DEFAULT 'MEDIUM' CHECK(priority IN ('LOW','MEDIUM','HIGH','CRITICAL'));
ALTER TABLE tickets ADD COLUMN assigned_agent_id uuid;
-- Legacy foundation rows have no historical timestamps/customer identity. Keep unknowns null.
ALTER TABLE tickets ADD COLUMN created_at timestamptz;
ALTER TABLE tickets ALTER COLUMN created_at SET DEFAULT now();
ALTER TABLE tickets ADD COLUMN updated_at timestamptz;
ALTER TABLE tickets ALTER COLUMN updated_at SET DEFAULT now();
ALTER TABLE tickets ADD CONSTRAINT tickets_customer_membership FOREIGN KEY(customer_id,company_id) REFERENCES memberships(account_id,company_id);
ALTER TABLE tickets ADD CONSTRAINT tickets_agent_membership FOREIGN KEY(assigned_agent_id,company_id) REFERENCES memberships(account_id,company_id);
ALTER TABLE tickets ADD CONSTRAINT tickets_category_company FOREIGN KEY(category_id,company_id) REFERENCES ticket_categories(id,company_id);
ALTER TABLE tickets ADD CONSTRAINT tickets_status_valid CHECK(status IN ('OPEN','IN_PROGRESS','WAITING_FOR_CUSTOMER','RESOLVED','CLOSED'));
ALTER TABLE tickets ADD CONSTRAINT tickets_subject_valid CHECK(length(trim(subject)) BETWEEN 1 AND 200);
ALTER TABLE tickets ADD CONSTRAINT tickets_customer_data CHECK(customer_id IS NULL OR (description IS NOT NULL AND length(trim(description)) BETWEEN 1 AND 10000 AND category_id IS NOT NULL AND created_at IS NOT NULL AND updated_at IS NOT NULL));
CREATE INDEX tickets_customer_list ON tickets(company_id,customer_id,created_at DESC,id);
CREATE INDEX tickets_company_list ON tickets(company_id,created_at DESC,id);
