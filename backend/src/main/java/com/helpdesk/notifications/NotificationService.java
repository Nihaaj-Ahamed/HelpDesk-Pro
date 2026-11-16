package com.helpdesk.notifications;

import com.helpdesk.organizations.WorkspaceAccess;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class NotificationService {

  public enum Type {
    CREATED,
    ASSIGNMENT,
    REPLY,
    STATUS,
    SLA_WARNING,
    SLA_BREACH,
  }

  public record Context(
    UUID company,
    UUID account,
    String name,
    String slug,
    String role
  ) {}

  public record Preferences(
    boolean emailEnabled,
    @NotNull @Size(max = 6) Set<Type> categories
  ) {}

  private final JdbcTemplate db;
  private final WorkspaceAccess access;

  public NotificationService(JdbcTemplate db, WorkspaceAccess access) {
    this.db = db;
    this.access = access;
  }

  public Context context(String slug, Jwt jwt) {
    UUID account = access.account(jwt);
    var rows = db.queryForList(
      "SELECT c.id,c.name,m.role FROM companies c JOIN memberships m ON m.company_id=c.id WHERE c.slug=? AND m.account_id=? AND m.status='ACTIVE'",
      slug,
      account
    );
    if (rows.isEmpty()) throw new ResponseStatusException(
      HttpStatus.NOT_FOUND,
      "Company membership is unavailable."
    );
    var r = rows.getFirst();
    access.requireActiveCompany((UUID) r.get("id"));
    return new Context(
      (UUID) r.get("id"),
      account,
      r.get("name").toString(),
      slug,
      r.get("role").toString()
    );
  }

  public void ticket(
    UUID company,
    UUID ticket,
    UUID actor,
    Type type,
    String key,
    UUID extra
  ) {
    var t = db.queryForMap(
      "SELECT customer_id,assigned_agent_id FROM tickets WHERE company_id=? AND id=?",
      company,
      ticket
    );
    var people = new HashSet<UUID>(
      db.queryForList(
        "SELECT account_id FROM memberships WHERE company_id=? AND status='ACTIVE' AND role='COMPANY_ADMIN'",
        UUID.class,
        company
      )
    );
    if (
      type != Type.SLA_WARNING &&
      type != Type.SLA_BREACH &&
      t.get("customer_id") != null
    ) people.add((UUID) t.get("customer_id"));
    if (t.get("assigned_agent_id") != null) people.add(
      (UUID) t.get("assigned_agent_id")
    );
    if (extra != null) people.add(extra);
    if (type != Type.CREATED) people.remove(actor);
    for (UUID recipient : people) {
      if (
        !Boolean.TRUE.equals(
          db.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM memberships WHERE company_id=? AND account_id=? AND status='ACTIVE')",
            Boolean.class,
            company,
            recipient
          )
        )
      ) continue;
      UUID id = UUID.randomUUID();
      String title = switch (type) {
        case CREATED -> "Ticket created";
        case ASSIGNMENT -> "Ticket assignment updated";
        case REPLY -> "New public reply";
        case STATUS -> "Ticket status updated";
        case SLA_WARNING -> "SLA deadline approaching";
        case SLA_BREACH -> "SLA breached - escalated";
      };
      if (
        db.update(
          "INSERT INTO notifications(id,company_id,ticket_id,recipient_id,type,title,event_key) VALUES (?,?,?,?,?,?,?) ON CONFLICT(company_id,recipient_id,event_key) DO NOTHING",
          id,
          company,
          ticket,
          recipient,
          type.name(),
          title,
          key
        ) == 1
      ) db.update(
        "INSERT INTO email_outbox(id,notification_id) VALUES (?,?)",
        UUID.randomUUID(),
        id
      );
    }
  }

  private String policy(Context c, List<Object> args) {
    args.add(c.company());
    args.add(c.account());
    String where =
      " FROM notifications n JOIN tickets t ON t.id=n.ticket_id AND t.company_id=n.company_id WHERE n.company_id=? AND n.recipient_id=? ";
    if (c.role().equals("CUSTOMER")) {
      where += " AND t.customer_id=? ";
      args.add(c.account());
    }
    return where;
  }

  public Map<String, Object> list(
    String slug,
    Jwt jwt,
    String type,
    boolean unread,
    int page,
    int size
  ) {
    var c = context(slug, jwt);
    var args = new ArrayList<Object>();
    String where = policy(c, args);
    long unreadTotal = db.queryForObject(
      "SELECT count(*)" + where + " AND n.read_at IS NULL",
      Long.class,
      args.toArray()
    );
    if (!type.isBlank()) {
      where += " AND n.type=?";
      args.add(type);
    }
    if (unread) where += " AND n.read_at IS NULL";
    long total = db.queryForObject(
      "SELECT count(*)" + where,
      Long.class,
      args.toArray()
    );
    args.add(size);
    args.add(page * size);
    var items = db.queryForList(
      "SELECT n.id,n.ticket_id AS \"ticketId\",n.type,n.title,n.created_at AS \"createdAt\",n.read_at AS \"readAt\",t.reference" +
        where +
        " ORDER BY n.created_at DESC,n.id LIMIT ? OFFSET ?",
      args.toArray()
    );
    for (var n : items)
      n.put("url", url(c.slug(), c.role(), n.get("ticketId").toString()));
    return Map.of(
      "items",
      items,
      "total",
      total,
      "unread",
      unreadTotal,
      "page",
      page,
      "size",
      size
    );
  }

  public static String url(String slug, String role, String ticket) {
    return (
      "/" +
      (role.equals("CUSTOMER") ? "support" : "workspace") +
      "/" +
      slug +
      "/tickets/" +
      ticket
    );
  }

  @Transactional
  public void read(String slug, Jwt jwt, UUID id) {
    var c = context(slug, jwt);
    if (
      db.update(
        "UPDATE notifications SET read_at=coalesce(read_at,clock_timestamp()) WHERE company_id=? AND recipient_id=? " +
          (id == null ? "" : " AND id=?"),
        id == null
          ? new Object[] { c.company(), c.account() }
          : new Object[] { c.company(), c.account(), id }
      ) == 0 &&
      id != null
    ) throw new ResponseStatusException(
      HttpStatus.NOT_FOUND,
      "Notification not found."
    );
  }

  public Preferences preferences(String slug, Jwt jwt) {
    var c = context(slug, jwt);
    var rows = db.queryForList(
      "SELECT email_enabled,categories FROM notification_preferences WHERE company_id=? AND account_id=?",
      c.company(),
      c.account()
    );
    if (rows.isEmpty()) return new Preferences(true, EnumSet.allOf(Type.class));
    var r = rows.getFirst();
    var types = EnumSet.noneOf(Type.class);
    for (String value : r.get("categories").toString().split(","))
      if (!value.isBlank()) types.add(Type.valueOf(value));
    return new Preferences((Boolean) r.get("email_enabled"), types);
  }

  @Transactional
  public void preferences(String slug, Jwt jwt, Preferences p) {
    var c = context(slug, jwt);
    String types = p
      .categories()
      .stream()
      .map(Enum::name)
      .sorted()
      .collect(java.util.stream.Collectors.joining(","));
    db.update(
      "INSERT INTO notification_preferences(company_id,account_id,email_enabled,categories) VALUES (?,?,?,?) ON CONFLICT(company_id,account_id) DO UPDATE SET email_enabled=excluded.email_enabled,categories=excluded.categories",
      c.company(),
      c.account(),
      p.emailEnabled(),
      types
    );
  }

  public List<Map<String, Object>> deliveries(String slug, Jwt jwt) {
    var c = access.admin(slug, jwt);
    return db.queryForList(
      "SELECT o.id,o.state,o.attempts,o.next_attempt_at AS \"nextAttemptAt\",o.last_error AS \"lastError\",n.type,t.reference,a.full_name AS recipient FROM email_outbox o JOIN notifications n ON n.id=o.notification_id JOIN tickets t ON t.id=n.ticket_id AND t.company_id=n.company_id JOIN accounts a ON a.id=n.recipient_id WHERE n.company_id=? ORDER BY n.created_at DESC LIMIT 100",
      c.companyId()
    );
  }

  @Transactional
  public void retry(String slug, Jwt jwt, UUID id) {
    var c = access.lockAdmin(slug, jwt);
    if (
      db.update(
        "UPDATE email_outbox o SET attempts=0,next_attempt_at=now(),state='PENDING',last_error=NULL FROM notifications n WHERE n.id=o.notification_id AND n.company_id=? AND o.id=? AND o.state='FAILED'",
        c.companyId(),
        id
      ) != 1
    ) throw new ResponseStatusException(
      HttpStatus.NOT_FOUND,
      "Failed delivery not found."
    );
  }
}
