package com.helpdesk.tickets;

import static com.helpdesk.tickets.TicketModels.*;

import java.sql.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class TicketRepository {

  private final JdbcTemplate db;

  public TicketRepository(JdbcTemplate db) {
    this.db = db;
  }

  private static final String FROM =
    " FROM tickets t LEFT JOIN accounts c ON c.id=t.customer_id LEFT JOIN ticket_categories cat ON cat.id=t.category_id AND cat.company_id=t.company_id LEFT JOIN accounts a ON a.id=t.assigned_agent_id ";
  private static final String SELECT =
    "SELECT (SELECT CASE WHEN NOT s.enabled THEN 'UNTRACKED' WHEN (s.response_due<coalesce(s.first_response_at,now()) OR s.resolution_due<coalesce(s.resolved_at,now())) THEN 'BREACHED' WHEN ((s.first_response_at IS NULL AND s.response_due-s.response_minutes*s.warning_percent/100.0*interval '1 minute'<=now()) OR (s.resolved_at IS NULL AND s.resolution_due-s.resolution_minutes*s.warning_percent/100.0*interval '1 minute'<=now())) THEN 'AT_RISK' ELSE 'ON_TRACK' END FROM ticket_sla s WHERE s.company_id=t.company_id AND s.ticket_id=t.id) AS sla_state,t.*,c.full_name AS customer_name,c.email AS customer_email,cat.name AS category_name,a.full_name AS agent_name";

  private Ticket map(ResultSet r, int row) throws SQLException {
    return new Ticket(
      r.getObject("id", UUID.class),
      r.getString("reference"),
      r.getString("subject"),
      r.getString("description"),
      r.getObject("category_id", UUID.class),
      r.getString("category_name"),
      r.getString("priority"),
      r.getString("status"),
      r.getTimestamp("created_at") == null
        ? null
        : r.getTimestamp("created_at").toInstant(),
      r.getTimestamp("updated_at") == null
        ? null
        : r.getTimestamp("updated_at").toInstant(),
      r.getObject("customer_id") == null
        ? null
        : new Customer(
            r.getObject("customer_id", UUID.class),
            r.getString("customer_name"),
            r.getString("customer_email")
          ),
      r.getObject("assigned_agent_id") == null
        ? null
        : new Agent(
            r.getObject("assigned_agent_id", UUID.class),
            r.getString("agent_name")
          ),
      r.getLong("version"),
      0,
      r.getString("sla_state")
    );
  }

  private String scope(Scope scope, List<Object> args) {
    args.add(scope.company());
    if (scope.customer() == null) {
      String where = " WHERE t.company_id=? ";
      switch (scope.queue()) {
        case "MINE":
          where += " AND t.assigned_agent_id=? ";
          args.add(scope.actor());
          break;
        case "UNASSIGNED":
          where += " AND t.assigned_agent_id IS NULL AND t.status='OPEN' ";
          break;
        case "IN_PROGRESS":
          where += " AND t.status='IN_PROGRESS' ";
          break;
        case "DONE":
          where += " AND t.status IN ('RESOLVED','CLOSED') ";
          break;
      }
      return where;
    }
    args.add(scope.customer());
    return " WHERE t.company_id=? AND t.customer_id=? ";
  }

  public Page list(
    Scope scope,
    String search,
    String status,
    String priority,
    UUID category,
    int page,
    int size
  ) {
    var args = new ArrayList<Object>();
    String where = scope(scope, args);
    search = search.strip().toLowerCase(Locale.ROOT);
    if (!search.isEmpty()) {
      where +=
        " AND (position(? in lower(t.subject))>0 OR position(? in lower(t.reference))>0 OR position(? in lower(c.full_name))>0 OR position(? in lower(c.email))>0) ";
      args.addAll(Collections.nCopies(4, search));
    }
    if (!status.isEmpty()) {
      where += " AND t.status=? ";
      args.add(status);
    }
    if (!priority.isEmpty()) {
      where += " AND t.priority=? ";
      args.add(priority);
    }
    if (category != null) {
      where += " AND t.category_id=? ";
      args.add(category);
    }
    long total = db.queryForObject(
      "SELECT count(*)" + FROM + where,
      Long.class,
      args.toArray()
    );
    args.add(size);
    args.add(Math.multiplyExact(page, size));
    var items = db.query(
      SELECT +
        FROM +
        where +
        " ORDER BY t.created_at DESC NULLS LAST,t.id LIMIT ? OFFSET ?",
      this::map,
      args.toArray()
    );
    return new Page(enrich(items, scope), total, page, size);
  }

  public Optional<Ticket> detail(Scope scope, UUID id) {
    var args = new ArrayList<Object>();
    String where = scope(scope, args);
    args.add(id);
    return db
      .query(SELECT + FROM + where + " AND t.id=?", this::map, args.toArray())
      .stream()
      .map(t -> enrich(List.of(t), scope).getFirst())
      .findFirst();
  }

  public Counts counts(Scope scope) {
    var args = new ArrayList<Object>();
    String where = scope(scope, args);
    Counts values = db.queryForObject(
      "SELECT count(*) AS total,count(*) FILTER(WHERE status IN ('OPEN','ASSIGNED','IN_PROGRESS')) AS open,count(*) FILTER(WHERE status='RESOLVED') AS resolved,count(*) FILTER(WHERE status='CLOSED') AS closed,count(*) FILTER(WHERE assigned_agent_id IS NULL AND status='OPEN') AS unassigned FROM tickets t" +
        where,
      (r, n) ->
        new Counts(
          r.getLong("total"),
          r.getLong("open"),
          r.getLong("resolved"),
          r.getLong("closed"),
          r.getLong("unassigned"),
          0
        ),
      args.toArray()
    );
    return new Counts(
      values.total(),
      values.open(),
      values.resolved(),
      values.closed(),
      values.unassigned(),
      unreadTotal(scope)
    );
  }

  private List<Ticket> enrich(List<Ticket> items, Scope scope) {
    if (items.isEmpty() || scope.actor() == null) return items;
    var args = new ArrayList<Object>();
    args.add(scope.actor());
    args.add(scope.company());
    args.add(scope.actor());
    args.add(scope.customer() == null);
    args.add(scope.customer() == null);
    String owner = scope.customer() == null ? "" : " AND t.customer_id=? ";
    if (scope.customer() != null) args.add(scope.customer());
    String placeholders = String.join(
      ",",
      Collections.nCopies(items.size(), "?")
    );
    items.forEach(t -> args.add(t.id()));
    var counts = new HashMap<UUID, Long>();
    db.query(
      "SELECT msg.ticket_id,count(*) AS unread FROM ticket_messages msg JOIN tickets t ON t.id=msg.ticket_id AND t.company_id=msg.company_id LEFT JOIN ticket_reads r ON r.company_id=msg.company_id AND r.ticket_id=msg.ticket_id AND r.account_id=? WHERE msg.company_id=? AND msg.sender_id<>? AND (msg.visibility='PUBLIC' OR ?) AND (CASE WHEN ? THEN msg.position ELSE msg.public_position END)>coalesce(r.last_position,0) " +
        owner +
        " AND msg.ticket_id IN (" +
        placeholders +
        ") GROUP BY msg.ticket_id",
      rs -> {
        counts.put(rs.getObject("ticket_id", UUID.class), rs.getLong("unread"));
      },
      args.toArray()
    );
    return items
      .stream()
      .map(t -> t.withUnread(counts.getOrDefault(t.id(), 0L)))
      .toList();
  }

  private long unreadTotal(Scope scope) {
    if (scope.actor() == null) return 0;
    var args = new ArrayList<Object>(
      List.of(
        scope.actor(),
        scope.company(),
        scope.actor(),
        scope.customer() == null,
        scope.customer() == null
      )
    );
    String owner = scope.customer() == null ? "" : " AND t.customer_id=?";
    if (scope.customer() != null) args.add(scope.customer());
    return db.queryForObject(
      "SELECT count(*) FROM ticket_messages msg JOIN tickets t ON t.id=msg.ticket_id AND t.company_id=msg.company_id LEFT JOIN ticket_reads r ON r.company_id=msg.company_id AND r.ticket_id=msg.ticket_id AND r.account_id=? WHERE msg.company_id=? AND msg.sender_id<>? AND (msg.visibility='PUBLIC' OR ?) AND (CASE WHEN ? THEN msg.position ELSE msg.public_position END)>coalesce(r.last_position,0)" +
        owner,
      Long.class,
      args.toArray()
    );
  }

  public long recentCount(Scope scope) {
    return db.queryForObject(
      "SELECT count(*) FROM tickets WHERE company_id=? AND customer_id=? AND created_at>now()-interval '1 hour'",
      Long.class,
      scope.company(),
      scope.customer()
    );
  }

  public UUID create(
    Scope scope,
    Create request,
    UUID category,
    String priority
  ) {
    UUID id = UUID.randomUUID();
    db.update(
      "INSERT INTO tickets(id,company_id,customer_id,subject,description,category_id,priority,status) VALUES (?,?,?,?,?,?,?,'OPEN')",
      id,
      scope.company(),
      scope.customer(),
      request.subject().strip(),
      request.description().strip(),
      category,
      priority
    );
    db.update(
      "INSERT INTO ticket_activity(id,company_id,ticket_id,actor_id,actor_name,actor_role,kind,new_value) SELECT ?,?,?,id,full_name,'CUSTOMER','CREATED','OPEN' FROM accounts WHERE id=?",
      UUID.randomUUID(),
      scope.company(),
      id,
      scope.customer()
    );
    return id;
  }
}
