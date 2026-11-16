package com.helpdesk.sla;

import com.helpdesk.conversations.ConversationAccess;
import com.helpdesk.notifications.NotificationService;
import com.helpdesk.organizations.WorkspaceAccess;
import jakarta.validation.constraints.*;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class SlaService {

  private final JdbcTemplate db;
  private final WorkspaceAccess access;
  private final ConversationAccess tickets;
  private final NotificationService notifications;

  public SlaService(
    JdbcTemplate db,
    WorkspaceAccess access,
    ConversationAccess tickets,
    NotificationService notifications
  ) {
    this.db = db;
    this.access = access;
    this.tickets = tickets;
    this.notifications = notifications;
  }

  public record Policy(
    @NotNull @Pattern(regexp = "LOW|MEDIUM|HIGH|CRITICAL") String priority,
    @Min(1) @Max(525600) int responseMinutes,
    @Min(1) @Max(525600) int resolutionMinutes,
    @Min(1) @Max(99) int warningPercent,
    boolean enabled,
    @Min(0) long version
  ) {}

  public void defaults(UUID company) {
    db.update(
      "INSERT INTO sla_policies(company_id,priority,response_minutes,resolution_minutes) SELECT ?,priority,response,resolution FROM (VALUES ('LOW',1440,4320),('MEDIUM',480,2880),('HIGH',60,480),('CRITICAL',15,240)) p(priority,response,resolution) ON CONFLICT DO NOTHING",
      company
    );
  }

  @Transactional
  public List<Map<String, Object>> policies(String slug, Jwt jwt) {
    var m = access.lockAdmin(slug, jwt);
    defaults(m.companyId());
    return db.queryForList(
      "SELECT priority,response_minutes AS \"responseMinutes\",resolution_minutes AS \"resolutionMinutes\",warning_percent AS \"warningPercent\",enabled,version FROM sla_policies WHERE company_id=? ORDER BY response_minutes DESC",
      m.companyId()
    );
  }

  @Transactional
  public void save(String slug, Jwt jwt, Policy p) {
    var m = access.lockAdmin(slug, jwt);
    defaults(m.companyId());
    if (
      p.resolutionMinutes() < p.responseMinutes()
    ) throw new ResponseStatusException(
      HttpStatus.BAD_REQUEST,
      "Resolution target must be at least the first-response target."
    );
    if (
      db.update(
        "UPDATE sla_policies SET response_minutes=?,resolution_minutes=?,warning_percent=?,enabled=?,version=version+1 WHERE company_id=? AND priority=? AND version=?",
        p.responseMinutes(),
        p.resolutionMinutes(),
        p.warningPercent(),
        p.enabled(),
        m.companyId(),
        p.priority(),
        p.version()
      ) != 1
    ) throw new ResponseStatusException(
      HttpStatus.CONFLICT,
      "Policy changed. Reload before saving."
    );
  }

  public void created(UUID company, UUID ticket) {
    defaults(company);
    db.update(
      "INSERT INTO ticket_sla(company_id,ticket_id,priority,enabled,started_at,response_minutes,resolution_minutes,warning_percent,response_due,resolution_due) SELECT t.company_id,t.id,t.priority,p.enabled,t.created_at,p.response_minutes,p.resolution_minutes,p.warning_percent,CASE WHEN p.enabled THEN t.created_at+p.response_minutes*interval '1 minute' END,CASE WHEN p.enabled THEN t.created_at+p.resolution_minutes*interval '1 minute' END FROM tickets t JOIN sla_policies p ON p.company_id=t.company_id AND p.priority=t.priority WHERE t.company_id=? AND t.id=? ON CONFLICT DO NOTHING",
      company,
      ticket
    );
  }

  public void response(UUID company, UUID ticket, UUID actor, Instant at) {
    db.update(
      "UPDATE ticket_sla SET first_response_at=?,first_responder_id=? WHERE company_id=? AND ticket_id=? AND first_response_at IS NULL",
      java.sql.Timestamp.from(at),
      actor,
      company,
      ticket
    );
  }

  public void resolved(UUID company, UUID ticket, UUID actor) {
    db.update(
      "UPDATE ticket_sla SET resolved_at=clock_timestamp(),resolver_id=? WHERE company_id=? AND ticket_id=? AND resolved_at IS NULL",
      actor,
      company,
      ticket
    );
  }

  public List<UUID> pending() {
    return db.queryForList(
      "SELECT DISTINCT company_id FROM ticket_sla WHERE company_id IN (SELECT id FROM companies WHERE status='ACTIVE') AND enabled AND ((response_breached_at IS NULL AND response_due<coalesce(first_response_at,now())) OR (resolution_breached_at IS NULL AND resolution_due<coalesce(resolved_at,now())) OR (first_response_at IS NULL AND response_warned_at IS NULL AND response_due-response_minutes*warning_percent/100.0*interval '1 minute'<=now()) OR (resolved_at IS NULL AND resolution_warned_at IS NULL AND resolution_due-resolution_minutes*warning_percent/100.0*interval '1 minute'<=now())) ORDER BY company_id LIMIT 100",
      UUID.class
    );
  }

  @Transactional
  public void evaluate(UUID company, Instant now) {
    if (
      db
        .queryForList(
          "SELECT id FROM companies WHERE id=? AND status='ACTIVE' FOR UPDATE SKIP LOCKED",
          UUID.class,
          company
        )
        .isEmpty()
    ) return;
    var rows = db.queryForList(
      "SELECT * FROM ticket_sla WHERE company_id=? AND enabled",
      company
    );
    for (var r : rows) {
      target(
        company,
        r,
        "RESPONSE",
        "response",
        instant(r.get("first_response_at")),
        now
      );
      target(
        company,
        r,
        "RESOLUTION",
        "resolution",
        instant(r.get("resolved_at")),
        now
      );
    }
  }

  private Instant instant(Object value) {
    return value == null ? null : ((java.sql.Timestamp) value).toInstant();
  }

  private void target(
    UUID company,
    Map<String, Object> row,
    String target,
    String prefix,
    Instant fulfilled,
    Instant now
  ) {
    Instant due = instant(row.get(prefix + "_due"));
    if (due == null) return;
    Instant reference = fulfilled == null ? now : fulfilled;
    String kind = null;
    if (
      reference.isAfter(due) && row.get(prefix + "_breached_at") == null
    ) kind = "BREACH";
    else if (
      fulfilled == null &&
      reference.isBefore(due) &&
      row.get(prefix + "_warned_at") == null
    ) {
      long seconds =
        (((Number) row.get(prefix + "_minutes")).longValue() *
          60 *
          ((Number) row.get("warning_percent")).longValue()) /
        100;
      if (!now.isBefore(due.minusSeconds(seconds))) kind = "WARNING";
    }
    if (kind == null) return;
    UUID ticket = (UUID) row.get("ticket_id");
    UUID event = UUID.randomUUID();
    int inserted = db.update(
      "INSERT INTO sla_events(id,company_id,ticket_id,target,kind,due_at,created_at) VALUES (?,?,?,?,?,?,?) ON CONFLICT DO NOTHING",
      event,
      company,
      ticket,
      target,
      kind,
      java.sql.Timestamp.from(due),
      java.sql.Timestamp.from(now)
    );
    if (inserted == 0) return;
    db.update(
      "UPDATE ticket_sla SET " +
        prefix +
        (kind.equals("BREACH") ? "_breached_at" : "_warned_at") +
        "=? WHERE company_id=? AND ticket_id=?",
      java.sql.Timestamp.from(now),
      company,
      ticket
    );
    notifications.ticket(
      company,
      ticket,
      null,
      kind.equals("BREACH")
        ? NotificationService.Type.SLA_BREACH
        : NotificationService.Type.SLA_WARNING,
      "sla:" + event,
      null
    );
    if (kind.equals("BREACH")) db.update(
      "INSERT INTO ticket_activity(id,company_id,ticket_id,actor_name,actor_role,kind,previous_value,new_value) VALUES (?,?,?,'SLA monitor','SYSTEM','SLA_ESCALATED',?,?)",
      UUID.randomUUID(),
      company,
      ticket,
      due.toString(),
      target
    );
  }

  public Map<String, Object> detail(
    String slug,
    UUID id,
    Jwt jwt,
    boolean staff
  ) {
    var v = tickets.require(slug, id, jwt, staff);
    var rows = db.queryForList(
      "SELECT * FROM ticket_sla WHERE company_id=? AND ticket_id=?",
      v.company(),
      id
    );
    if (rows.isEmpty()) return Map.of("tracked", false, "legacy", true);
    var r = rows.getFirst();
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("tracked", r.get("enabled"));
    result.put("legacy", r.get("legacy"));
    result.put("prioritySnapshot", r.get("priority"));
    result.put(
      "firstResponse",
      summary(
        instant(r.get("response_due")),
        instant(r.get("first_response_at")),
        (Number) r.get("response_minutes"),
        (Number) r.get("warning_percent")
      )
    );
    result.put(
      "resolution",
      summary(
        instant(r.get("resolution_due")),
        instant(r.get("resolved_at")),
        (Number) r.get("resolution_minutes"),
        (Number) r.get("warning_percent")
      )
    );
    result.put(
      "events",
      staff
        ? db.queryForList(
            "SELECT id,target,kind,due_at AS \"dueAt\",created_at AS \"createdAt\" FROM sla_events WHERE company_id=? AND ticket_id=? ORDER BY created_at,id",
            v.company(),
            id
          )
        : List.of()
    );
    return result;
  }

  private Map<String, Object> summary(
    Instant due,
    Instant fulfilled,
    Number minutes,
    Number warning
  ) {
    var r = new LinkedHashMap<String, Object>();
    r.put("deadline", due);
    r.put("fulfilledAt", fulfilled);
    long remaining =
      due == null
        ? 0
        : Duration.between(
            fulfilled == null ? Instant.now() : fulfilled,
            due
          ).getSeconds();
    r.put("remainingSeconds", remaining);
    r.put("overdueSeconds", Math.max(0, -remaining));
    r.put(
      "state",
      due == null
        ? "UNTRACKED"
        : remaining < 0
          ? "BREACHED"
          : fulfilled != null
            ? "MET"
            : remaining <=
                (minutes.longValue() * 60 * warning.longValue()) / 100
              ? "AT_RISK"
              : "ON_TRACK"
    );
    return r;
  }

  public Map<String, Object> overview(String slug, Jwt jwt) {
    UUID company = access.admin(slug, jwt).companyId();
    return db.queryForMap(
      "SELECT count(*) FILTER(WHERE s.enabled) AS tracked,count(*) FILTER(WHERE s.enabled AND ((s.first_response_at IS NULL AND s.response_due<now()) OR (s.resolved_at IS NULL AND s.resolution_due<now()))) AS overdue,count(*) FILTER(WHERE s.enabled AND ((s.first_response_at IS NULL AND s.response_due>now() AND s.response_due-s.response_minutes*s.warning_percent/100.0*interval '1 minute'<=now()) OR (s.resolved_at IS NULL AND s.resolution_due>now() AND s.resolution_due-s.resolution_minutes*s.warning_percent/100.0*interval '1 minute'<=now()))) AS at_risk,count(*) FILTER(WHERE s.enabled AND s.resolved_at IS NOT NULL AND s.resolved_at<=s.resolution_due) AS resolution_met,count(*) FILTER(WHERE s.enabled AND s.resolved_at IS NOT NULL) AS resolution_measured FROM ticket_sla s WHERE s.company_id=?",
      company
    );
  }

  public List<Map<String, Object>> queue(String slug, Jwt jwt) {
    UUID company = access.admin(slug, jwt).companyId();
    return db.queryForList(
      "SELECT t.id,t.reference,t.subject,t.status,s.response_due,s.resolution_due,s.first_response_at,s.resolved_at FROM ticket_sla s JOIN tickets t ON t.id=s.ticket_id AND t.company_id=s.company_id WHERE s.company_id=? AND s.enabled AND (s.first_response_at IS NULL OR s.resolved_at IS NULL) ORDER BY least(CASE WHEN s.first_response_at IS NULL THEN s.response_due END,CASE WHEN s.resolved_at IS NULL THEN s.resolution_due END) NULLS LAST,t.id LIMIT 50",
      company
    );
  }
}
