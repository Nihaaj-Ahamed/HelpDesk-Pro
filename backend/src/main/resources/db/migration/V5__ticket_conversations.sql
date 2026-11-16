CREATE TABLE ticket_messages (
 id uuid PRIMARY KEY, company_id uuid NOT NULL, ticket_id uuid NOT NULL,
 position bigint GENERATED ALWAYS AS IDENTITY UNIQUE NOT NULL,
 public_position bigint,
 sender_id uuid NOT NULL REFERENCES accounts(id), sender_name varchar(200) NOT NULL,
 sender_role varchar(32) NOT NULL CHECK(sender_role IN ('CUSTOMER','SUPPORT_AGENT','COMPANY_ADMIN')),
 visibility varchar(16) NOT NULL CHECK(visibility IN ('PUBLIC','INTERNAL')),
 content text NOT NULL CHECK(length(trim(content)) BETWEEN 1 AND 5000),
 client_id uuid NOT NULL, created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 FOREIGN KEY(ticket_id,company_id) REFERENCES tickets(id,company_id),
 FOREIGN KEY(sender_id,company_id) REFERENCES memberships(account_id,company_id),
 CHECK(public_position IS NULL OR public_position>0),
 UNIQUE(company_id,ticket_id,sender_id,client_id),
 UNIQUE(company_id,ticket_id,public_position),
 CHECK((visibility='PUBLIC' AND public_position IS NOT NULL) OR (visibility='INTERNAL' AND public_position IS NULL)),
 CHECK(visibility='PUBLIC' OR sender_role IN ('SUPPORT_AGENT','COMPANY_ADMIN'))
);
CREATE INDEX ticket_messages_history ON ticket_messages(company_id,ticket_id,position);
CREATE INDEX ticket_messages_rate ON ticket_messages(company_id,sender_id,created_at);
CREATE TABLE ticket_reads (
 company_id uuid NOT NULL, ticket_id uuid NOT NULL, account_id uuid NOT NULL,
 last_position bigint NOT NULL DEFAULT 0 CHECK(last_position>=0), viewed_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(company_id,ticket_id,account_id),
 FOREIGN KEY(ticket_id,company_id) REFERENCES tickets(id,company_id),
 FOREIGN KEY(account_id,company_id) REFERENCES memberships(account_id,company_id)
);
CREATE FUNCTION immutable_ticket_message() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'Ticket messages are immutable'; END $$;
CREATE TRIGGER ticket_messages_immutable BEFORE UPDATE OR DELETE ON ticket_messages FOR EACH ROW EXECUTE FUNCTION immutable_ticket_message();
