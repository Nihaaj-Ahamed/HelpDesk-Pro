ALTER TABLE tickets ADD COLUMN version bigint NOT NULL DEFAULT 0;
ALTER TABLE tickets DROP CONSTRAINT tickets_status_valid;
-- Preserve legacy waiting tickets as open work; M4 has no waiting state.
UPDATE tickets SET status=CASE WHEN assigned_agent_id IS NULL THEN 'OPEN' ELSE 'IN_PROGRESS' END WHERE status='WAITING_FOR_CUSTOMER';
UPDATE tickets SET status='OPEN' WHERE status='IN_PROGRESS' AND assigned_agent_id IS NULL;
UPDATE tickets SET status='ASSIGNED' WHERE status='OPEN' AND assigned_agent_id IS NOT NULL;
ALTER TABLE tickets ADD CONSTRAINT tickets_status_valid CHECK(status IN ('OPEN','ASSIGNED','IN_PROGRESS','RESOLVED','CLOSED'));
ALTER TABLE tickets ADD CONSTRAINT tickets_assignment_state CHECK((status!='OPEN' OR assigned_agent_id IS NULL) AND (status NOT IN ('ASSIGNED','IN_PROGRESS') OR assigned_agent_id IS NOT NULL));
ALTER TABLE tickets ADD CONSTRAINT tickets_id_company UNIQUE(id,company_id);
CREATE TABLE ticket_activity (
 event_order bigserial UNIQUE NOT NULL, id uuid PRIMARY KEY, company_id uuid NOT NULL, ticket_id uuid NOT NULL,
 actor_id uuid REFERENCES accounts(id), actor_name varchar(200), actor_role varchar(32) NOT NULL,
 kind varchar(32) NOT NULL, previous_value text, new_value text,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 FOREIGN KEY(ticket_id,company_id) REFERENCES tickets(id,company_id)
);
CREATE INDEX ticket_activity_timeline ON ticket_activity(company_id,ticket_id,created_at,id);
-- Historical creation time/actor are only recorded when known.
INSERT INTO ticket_activity(id,company_id,ticket_id,actor_id,actor_name,actor_role,kind,new_value,created_at)
 SELECT gen_random_uuid(),t.company_id,t.id,t.customer_id,a.full_name,'CUSTOMER','CREATED','OPEN',t.created_at FROM tickets t JOIN accounts a ON a.id=t.customer_id WHERE t.created_at IS NOT NULL ORDER BY t.created_at,t.id;
CREATE FUNCTION immutable_ticket_activity() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'Ticket activity is immutable'; END $$;
CREATE TRIGGER ticket_activity_immutable BEFORE UPDATE OR DELETE ON ticket_activity FOR EACH ROW EXECUTE FUNCTION immutable_ticket_activity();
