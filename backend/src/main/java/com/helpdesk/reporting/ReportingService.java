package com.helpdesk.reporting;

import com.helpdesk.organizations.WorkspaceAccess;
import jakarta.validation.constraints.*;
import java.time.*;
import java.util.*;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ReportingService {

  private final JdbcTemplate db;
  private final WorkspaceAccess access;

  public ReportingService(JdbcTemplate db, WorkspaceAccess access) {
    this.db = db;
    this.access = access;
  }

  public record Filter(
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
    @Pattern(regexp = "|OPEN|ASSIGNED|IN_PROGRESS|RESOLVED|CLOSED")
    String status,
    @Pattern(regexp = "|LOW|MEDIUM|HIGH|CRITICAL") String priority,
    UUID category,
    UUID agent
  ) {}

  private record Query(
    UUID company,
    UUID account,
    String where,
    List<Object> args,
    LocalDate from,
    LocalDate to,
    boolean personal
  ) {}

  private final String tables =
    " FROM tickets t LEFT JOIN ticket_sla s ON s.company_id=t.company_id AND s.ticket_id=t.id LEFT JOIN ticket_categories cat ON cat.id=t.category_id AND cat.company_id=t.company_id LEFT JOIN accounts assignee ON assignee.id=t.assigned_agent_id ";

  private Query query(String slug, Jwt jwt, boolean personal, Filter f) {
    var m = personal ? access.staff(slug, jwt) : access.admin(slug, jwt);
    LocalDate to = f.to() == null ? LocalDate.now(ZoneOffset.UTC) : f.to(),
      from = f.from() == null ? to.minusDays(29) : f.from();
    if (
      to.isBefore(from) ||
      java.time.temporal.ChronoUnit.DAYS.between(from, to) > 365
    ) throw new ResponseStatusException(
      HttpStatus.BAD_REQUEST,
      "Use an ordered date range of at most 366 days."
    );
    var args = new ArrayList<Object>(
      List.of(
        m.companyId(),
        java.sql.Timestamp.from(from.atStartOfDay().toInstant(ZoneOffset.UTC)),
        java.sql.Timestamp.from(
          to.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC)
        )
      )
    );
    String where =
      " WHERE t.company_id=? AND t.created_at>=? AND t.created_at<? ";
    if (f.status() != null && !f.status().isEmpty()) {
      where += " AND t.status=? ";
      args.add(f.status());
    }
    if (f.priority() != null && !f.priority().isEmpty()) {
      where += " AND t.priority=? ";
      args.add(f.priority());
    }
    if (f.category() != null) {
      if (
        !Boolean.TRUE.equals(
          db.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM ticket_categories WHERE company_id=? AND id=?)",
            Boolean.class,
            m.companyId(),
            f.category()
          )
        )
      ) throw new ResponseStatusException(
        HttpStatus.BAD_REQUEST,
        "Select a category from this company."
      );
      where += " AND t.category_id=? ";
      args.add(f.category());
    }
    if (f.agent() != null) {
      if (
        personal && !m.accountId().equals(f.agent())
      ) throw new ResponseStatusException(
        HttpStatus.FORBIDDEN,
        "Personal performance cannot select another agent."
      );
      if (
        !Boolean.TRUE.equals(
          db.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM memberships WHERE company_id=? AND account_id=? AND role='SUPPORT_AGENT')",
            Boolean.class,
            m.companyId(),
            f.agent()
          )
        )
      ) throw new ResponseStatusException(
        HttpStatus.BAD_REQUEST,
        "Select a Support Agent from this company."
      );
      where += " AND t.assigned_agent_id=? ";
      args.add(f.agent());
    }
    if (personal) {
      where +=
        " AND (t.assigned_agent_id=? OR s.first_responder_id=? OR s.resolver_id=?) ";
      args.addAll(Collections.nCopies(3, m.accountId()));
    }
    return new Query(
      m.companyId(),
      m.accountId(),
      where,
      args,
      from,
      to,
      personal
    );
  }

  private String totals() {
    return "SELECT count(*) AS total,count(*) FILTER(WHERE t.status='OPEN') AS open,count(*) FILTER(WHERE t.status='ASSIGNED') AS assigned,count(*) FILTER(WHERE t.status='IN_PROGRESS') AS in_progress,count(*) FILTER(WHERE t.status='RESOLVED') AS resolved,count(*) FILTER(WHERE t.status='CLOSED') AS closed,count(*) FILTER(WHERE t.assigned_agent_id IS NULL AND t.status='OPEN') AS unassigned,avg(extract(epoch FROM (s.first_response_at-s.started_at))/60) FILTER(WHERE s.first_response_at>=s.started_at) AS average_response_minutes,avg(extract(epoch FROM (s.resolved_at-s.started_at))/60) FILTER(WHERE s.resolved_at>=s.started_at) AS average_resolution_minutes,count(*) FILTER(WHERE s.enabled AND s.response_due IS NOT NULL AND (s.first_response_at IS NOT NULL OR s.response_due<now()))+count(*) FILTER(WHERE s.enabled AND s.resolution_due IS NOT NULL AND (s.resolved_at IS NOT NULL OR s.resolution_due<now())) AS measured_targets,count(*) FILTER(WHERE s.enabled AND s.first_response_at<=s.response_due)+count(*) FILTER(WHERE s.enabled AND s.resolved_at<=s.resolution_due) AS met_targets,count(*) FILTER(WHERE s.enabled AND (s.response_due<coalesce(s.first_response_at,now()) OR s.resolution_due<coalesce(s.resolved_at,now()))) AS breached_tickets,count(*) FILTER(WHERE NOT coalesce(s.enabled,false)) AS untracked ";
  }

  private List<Map<String, Object>> groups(Query q, String column) {
    return db.queryForList(
      "SELECT " +
        column +
        " AS label,count(*) AS value" +
        tables +
        q.where() +
        " GROUP BY 1 ORDER BY value DESC,label",
      q.args().toArray()
    );
  }

  public Map<String, Object> analytics(
    String slug,
    Jwt jwt,
    boolean personal,
    Filter filter
  ) {
    var q = query(slug, jwt, personal, filter);
    var args = new ArrayList<>(q.args());
    args.add(java.sql.Date.valueOf(q.from()));
    args.add(java.sql.Date.valueOf(q.to()));
    var trend = db.queryForList(
      "WITH cohort AS (SELECT t.id,t.company_id,t.created_at,s.resolved_at" +
        tables +
        q.where() +
        "), days AS (SELECT generate_series(?::date,?::date,interval '1 day')::date AS day) SELECT d.day,count(c.id) FILTER(WHERE (c.created_at AT TIME ZONE 'UTC')::date=d.day) AS created,count(c.id) FILTER(WHERE (c.resolved_at AT TIME ZONE 'UTC')::date=d.day) AS resolved,count(c.id) FILTER(WHERE (SELECT a.new_value FROM ticket_activity a WHERE a.company_id=c.company_id AND a.ticket_id=c.id AND a.kind IN ('CREATED','STATUS','REOPENED') AND a.created_at<(d.day+1)::timestamp AT TIME ZONE 'UTC' ORDER BY a.created_at DESC,a.event_order DESC LIMIT 1) IN ('OPEN','ASSIGNED','IN_PROGRESS')) AS backlog FROM days d LEFT JOIN cohort c ON c.created_at<(d.day+1)::timestamp AT TIME ZONE 'UTC' GROUP BY d.day ORDER BY d.day",
      args.toArray()
    );
    var result = new LinkedHashMap<String, Object>();
    result.put(
      "counts",
      db.queryForMap(totals() + tables + q.where(), q.args().toArray())
    );
    result.put("byStatus", groups(q, "t.status"));
    result.put("byPriority", groups(q, "t.priority"));
    result.put("byCategory", groups(q, "coalesce(cat.name,'Not recorded')"));
    result.put("trend", trend);
    result.put(
      "agentResolutionTrend",
      db.queryForList(
        "SELECT s.resolver_id AS agent_id,(s.resolved_at AT TIME ZONE 'UTC')::date AS day,count(*) AS value" +
          tables +
          q.where() +
          " AND s.resolver_id IS NOT NULL" +
          (q.personal()
            ? " AND s.resolver_id='" + q.account() + "'::uuid"
            : "") +
          " GROUP BY 1,2 ORDER BY 2,1 LIMIT 5000",
        q.args().toArray()
      )
    );
    result.put("from", q.from());
    result.put("to", q.to());
    result.put(
      "missingCreatedAt",
      db.queryForObject(
        "SELECT count(*) FROM tickets WHERE company_id=? AND created_at IS NULL",
        Long.class,
        q.company()
      )
    );
    result.put(
      "categories",
      db.queryForList(
        "SELECT id,name FROM ticket_categories WHERE company_id=? ORDER BY name",
        q.company()
      )
    );
    result.put("agents", agents(q));
    return result;
  }

  private List<Map<String, Object>> agents(Query q) {
    var args = new ArrayList<>(q.args());
    args.add(q.company());
    if (q.personal()) args.add(q.account());
    return db.queryForList(
      "WITH cohort AS (SELECT t.*,s.started_at,s.first_response_at,s.resolved_at,s.first_responder_id,s.resolver_id,s.enabled,s.response_due,s.resolution_due" +
        tables +
        q.where() +
        ") SELECT m.account_id AS id,a.full_name AS name,m.status,count(c.id) FILTER(WHERE c.assigned_agent_id=m.account_id AND c.status IN ('ASSIGNED','IN_PROGRESS')) AS workload,count(c.id) FILTER(WHERE c.resolver_id=m.account_id) AS resolved,avg(extract(epoch FROM(c.first_response_at-c.started_at))/60) FILTER(WHERE c.first_responder_id=m.account_id AND c.first_response_at>=c.started_at) AS average_response_minutes,avg(extract(epoch FROM(c.resolved_at-c.started_at))/60) FILTER(WHERE c.resolver_id=m.account_id AND c.resolved_at>=c.started_at) AS average_resolution_minutes,count(c.id) FILTER(WHERE c.enabled AND c.first_responder_id=m.account_id AND c.first_response_at<=c.response_due)+count(c.id) FILTER(WHERE c.enabled AND c.resolver_id=m.account_id AND c.resolved_at<=c.resolution_due) AS met_targets,count(c.id) FILTER(WHERE c.enabled AND c.first_responder_id=m.account_id AND c.first_response_at IS NOT NULL)+count(c.id) FILTER(WHERE c.enabled AND c.resolver_id=m.account_id AND c.resolved_at IS NOT NULL) AS measured_targets,count(c.id) FILTER(WHERE c.assigned_agent_id=m.account_id AND c.enabled AND ((c.first_response_at IS NULL AND c.response_due<now()) OR (c.resolved_at IS NULL AND c.resolution_due<now()))) AS overdue,count(c.id) FILTER(WHERE c.assigned_agent_id=m.account_id) AS assigned FROM memberships m JOIN accounts a ON a.id=m.account_id LEFT JOIN cohort c ON (c.assigned_agent_id=m.account_id OR c.first_responder_id=m.account_id OR c.resolver_id=m.account_id) WHERE m.company_id=? AND m.role='SUPPORT_AGENT'" +
        (q.personal() ? " AND m.account_id=?" : "") +
        " GROUP BY m.account_id,a.full_name,m.status ORDER BY a.full_name,m.account_id",
      args.toArray()
    );
  }

  @Transactional
  public String export(
    String slug,
    Jwt jwt,
    boolean personal,
    Filter filter,
    String report
  ) {
    var q = query(slug, jwt, personal, filter);
    access.lockCompany(q.company());
    List<Map<String, Object>> rows;
    switch (report) {
      case "STATUS":
        rows = groups(q, "t.status");
        break;
      case "PRIORITY":
        rows = groups(q, "t.priority");
        break;
      case "AGENTS":
        rows = agents(q);
        break;
      case "ESCALATIONS": {
        String join =
          tables +
          " JOIN sla_events e ON e.company_id=t.company_id AND e.ticket_id=t.id AND e.kind='BREACH' " +
          q.where();
        if (
          db.queryForObject(
            "SELECT count(*)" + join,
            Long.class,
            q.args().toArray()
          ) > 10000
        ) throw new ResponseStatusException(
          HttpStatus.PAYLOAD_TOO_LARGE,
          "Export is limited to 10000 events. Narrow the filters."
        );
        rows = db.queryForList(
          "SELECT t.reference,t.priority,t.status,e.target,e.kind,e.due_at,e.created_at AS escalated_at,assignee.full_name AS assigned_agent" +
            join +
            " ORDER BY e.created_at,e.id LIMIT 10000",
          q.args().toArray()
        );
        break;
      }
      default: {
        String extra = report.equals("OVERDUE")
          ? " AND s.enabled AND ((s.first_response_at IS NULL AND s.response_due<now()) OR (s.resolved_at IS NULL AND s.resolution_due<now()))"
          : "";
        long count = db.queryForObject(
          "SELECT count(*)" + tables + q.where() + extra,
          Long.class,
          q.args().toArray()
        );
        if (count > 10000) throw new ResponseStatusException(
          HttpStatus.PAYLOAD_TOO_LARGE,
          "Export is limited to 10000 tickets. Narrow the filters."
        );
        rows = db.queryForList(
          "SELECT t.reference,t.subject,t.status,t.priority,cat.name AS category,assignee.full_name AS assigned_agent,t.created_at,t.updated_at,s.response_due,s.first_response_at,s.resolution_due,s.resolved_at,s.enabled AS sla_tracked" +
            tables +
            q.where() +
            extra +
            " ORDER BY t.created_at,t.id LIMIT 10000",
          q.args().toArray()
        );
      }
    }
    var out = new StringBuilder();
    out.append("Workspace,").append(csv(slug)).append("\r\n");
    out
      .append("Report,")
      .append(csv(report))
      .append(",Generated UTC,")
      .append(csv(Instant.now().toString()))
      .append("\r\n");
    out
      .append("From UTC,")
      .append(q.from())
      .append(",Through UTC,")
      .append(q.to())
      .append("\r\n");
    if (rows.isEmpty()) return out.append("No matching records\r\n").toString();
    out
      .append(
        rows
          .getFirst()
          .keySet()
          .stream()
          .map(ReportingService::csv)
          .collect(java.util.stream.Collectors.joining(","))
      )
      .append("\r\n");
    for (var row : rows)
      out
        .append(
          row
            .values()
            .stream()
            .map(ReportingService::csv)
            .collect(java.util.stream.Collectors.joining(","))
        )
        .append("\r\n");
    return out.toString();
  }

  public static String csv(Object value) {
    String text =
      value == null
        ? ""
        : value instanceof java.sql.Timestamp
          ? ((java.sql.Timestamp) value).toInstant().toString()
          : value.toString();
    String stripped = text.replaceFirst("^[\\p{Z}\\p{C}\\s]+", "");
    if (
      (!stripped.isEmpty() && "=+-@".indexOf(stripped.charAt(0)) >= 0) ||
      text.startsWith("\t") ||
      text.startsWith("\r") ||
      text.startsWith("\n")
    ) text = "'" + text;
    return "\"" + text.replace("\"", "\"\"") + "\"";
  }
}
