ALTER TABLE memberships ADD COLUMN status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','INACTIVE'));
-- M1 did not record join dates: preserve unknown legacy dates instead of inventing them.
ALTER TABLE memberships ADD COLUMN joined_at timestamptz;
ALTER TABLE memberships ALTER COLUMN joined_at SET DEFAULT now();
ALTER TABLE accounts ADD COLUMN email_verified_at timestamptz;

CREATE TABLE invitations (
  id uuid PRIMARY KEY,
  company_id uuid NOT NULL REFERENCES companies(id),
  inviter_id uuid NOT NULL REFERENCES accounts(id),
  email varchar(254) NOT NULL CHECK(email = lower(trim(email))),
  role varchar(30) NOT NULL DEFAULT 'SUPPORT_AGENT' CHECK(role = 'SUPPORT_AGENT'),
  token_hash varchar(64) NOT NULL UNIQUE,
  status varchar(16) NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','ACCEPTED','EXPIRED','REVOKED')),
  created_at timestamptz NOT NULL DEFAULT now(),
  expires_at timestamptz NOT NULL,
  last_sent_at timestamptz NOT NULL DEFAULT now(),
  accepted_at timestamptz,
  accepted_by uuid REFERENCES accounts(id)
);
CREATE UNIQUE INDEX invitations_one_pending_email ON invitations(company_id,email) WHERE status='PENDING';
CREATE INDEX invitations_company_created ON invitations(company_id,created_at DESC);
CREATE TABLE invitation_deliveries (
  id uuid PRIMARY KEY,
  invitation_id uuid NOT NULL REFERENCES invitations(id),
  company_id uuid NOT NULL REFERENCES companies(id),
  actor_id uuid NOT NULL REFERENCES accounts(id),
  delivered_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX invitation_delivery_rate ON invitation_deliveries(company_id,delivered_at);
