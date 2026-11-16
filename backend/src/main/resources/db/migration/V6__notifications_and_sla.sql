CREATE TABLE notification_preferences (
 company_id uuid NOT NULL, account_id uuid NOT NULL, email_enabled boolean NOT NULL DEFAULT true,
 categories text NOT NULL DEFAULT 'CREATED,ASSIGNMENT,REPLY,STATUS,SLA_WARNING,SLA_BREACH',
 PRIMARY KEY(company_id,account_id), FOREIGN KEY(account_id,company_id) REFERENCES memberships(account_id,company_id)
);
CREATE TABLE notifications (
 id uuid PRIMARY KEY,company_id uuid NOT NULL,ticket_id uuid NOT NULL,recipient_id uuid NOT NULL,
 type varchar(32) NOT NULL CHECK(type IN ('CREATED','ASSIGNMENT','REPLY','STATUS','SLA_WARNING','SLA_BREACH')),
 title varchar(180) NOT NULL,event_key varchar(200) NOT NULL,created_at timestamptz NOT NULL DEFAULT clock_timestamp(),read_at timestamptz,
 FOREIGN KEY(ticket_id,company_id) REFERENCES tickets(id,company_id),
 FOREIGN KEY(recipient_id,company_id) REFERENCES memberships(account_id,company_id),UNIQUE(company_id,recipient_id,event_key)
);
CREATE INDEX notifications_inbox ON notifications(company_id,recipient_id,created_at DESC,id);
CREATE TABLE email_outbox (
 id uuid PRIMARY KEY, notification_id uuid NOT NULL UNIQUE REFERENCES notifications(id),
 state varchar(16) NOT NULL DEFAULT 'PENDING' CHECK(state IN ('PENDING','FAILED','SENT','SUPPRESSED')),
 attempts integer NOT NULL DEFAULT 0,next_attempt_at timestamptz NOT NULL DEFAULT now(),sent_at timestamptz,last_error varchar(120)
);
CREATE INDEX email_outbox_ready ON email_outbox(next_attempt_at) WHERE state IN ('PENDING','FAILED');
CREATE TABLE sla_policies (
 company_id uuid REFERENCES companies(id),priority varchar(16) CHECK(priority IN ('LOW','MEDIUM','HIGH','CRITICAL')),
 response_minutes integer NOT NULL CHECK(response_minutes BETWEEN 1 AND 525600),resolution_minutes integer NOT NULL CHECK(resolution_minutes BETWEEN 1 AND 525600),
 warning_percent integer NOT NULL DEFAULT 20 CHECK(warning_percent BETWEEN 1 AND 99),enabled boolean NOT NULL DEFAULT true,
 version bigint NOT NULL DEFAULT 0,PRIMARY KEY(company_id,priority),CHECK(resolution_minutes>=response_minutes)
);
INSERT INTO sla_policies(company_id,priority,response_minutes,resolution_minutes)
 SELECT c.id,p.priority,p.response,p.resolution FROM companies c CROSS JOIN (VALUES('LOW',1440,4320),('MEDIUM',480,2880),('HIGH',60,480),('CRITICAL',15,240)) p(priority,response,resolution);
CREATE TABLE ticket_sla (
 company_id uuid NOT NULL,ticket_id uuid NOT NULL,priority varchar(16) NOT NULL,enabled boolean NOT NULL,
 started_at timestamptz,response_minutes integer,resolution_minutes integer,warning_percent integer,
 response_due timestamptz,resolution_due timestamptz,first_response_at timestamptz,resolved_at timestamptz,
 first_responder_id uuid REFERENCES accounts(id),resolver_id uuid REFERENCES accounts(id),
 response_warned_at timestamptz,response_breached_at timestamptz,resolution_warned_at timestamptz,resolution_breached_at timestamptz,
 legacy boolean NOT NULL DEFAULT false,PRIMARY KEY(company_id,ticket_id),FOREIGN KEY(ticket_id,company_id) REFERENCES tickets(id,company_id)
);
-- Existing tickets had no agreed SLA at creation. Preserve measured facts without inventing historical promises.
INSERT INTO ticket_sla(company_id,ticket_id,priority,enabled,started_at,legacy,first_response_at,first_responder_id,resolved_at,resolver_id)
 SELECT t.company_id,t.id,t.priority,false,t.created_at,true,m.created_at,m.sender_id,a.created_at,a.actor_id FROM tickets t
 LEFT JOIN LATERAL(SELECT created_at,sender_id FROM ticket_messages WHERE company_id=t.company_id AND ticket_id=t.id AND visibility='PUBLIC' AND sender_role IN ('COMPANY_ADMIN','SUPPORT_AGENT') ORDER BY position LIMIT 1)m ON true
 LEFT JOIN LATERAL(SELECT created_at,actor_id FROM ticket_activity WHERE company_id=t.company_id AND ticket_id=t.id AND kind='STATUS' AND new_value='RESOLVED' AND actor_role IN ('COMPANY_ADMIN','SUPPORT_AGENT') ORDER BY event_order LIMIT 1)a ON true;
CREATE INDEX ticket_sla_deadlines ON ticket_sla(company_id,response_due,resolution_due) WHERE enabled;
CREATE TABLE sla_events (
 id uuid PRIMARY KEY,company_id uuid NOT NULL,ticket_id uuid NOT NULL,target varchar(16) NOT NULL CHECK(target IN ('RESPONSE','RESOLUTION')),
 kind varchar(16) NOT NULL CHECK(kind IN ('WARNING','BREACH')),due_at timestamptz NOT NULL,created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 FOREIGN KEY(ticket_id,company_id) REFERENCES tickets(id,company_id),UNIQUE(company_id,ticket_id,target,kind)
);
CREATE TRIGGER sla_events_immutable BEFORE UPDATE OR DELETE ON sla_events FOR EACH ROW EXECUTE FUNCTION immutable_ticket_activity();
