package com.helpdesk.organizations;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** All workspace queries constrain membership before reading tenant aggregates. */
@Repository
public class WorkspaceRepository {

  private final JdbcTemplate db;

  public WorkspaceRepository(JdbcTemplate db) {
    this.db = db;
  }

  public boolean platformAdmin(UUID account) {
    return Boolean.TRUE.equals(
      db.queryForObject(
        "SELECT EXISTS(SELECT 1 FROM platform_administrators WHERE account_id=?)",
        Boolean.class,
        account
      )
    );
  }

  public boolean activeSession(UUID session, UUID account) {
    return Integer.valueOf(1).equals(
      db.queryForObject(
        "SELECT count(*) FROM refresh_sessions s JOIN session_families f ON f.id=s.family_id WHERE s.id=? AND s.account_id=? AND s.revoked=false AND f.revoked=false AND s.expires_at>now()",
        Integer.class,
        session,
        account
      )
    );
  }

  public Map<String, Object> profile(UUID account) {
    return db.queryForMap(
      "SELECT full_name AS \"fullName\",email FROM accounts WHERE id=?",
      account
    );
  }

  public List<Map<String, Object>> portals(UUID account) {
    return db.queryForList(
      "SELECT c.name,c.slug FROM companies c JOIN memberships m ON m.company_id=c.id WHERE m.account_id=? AND m.status='ACTIVE' AND m.role='CUSTOMER' ORDER BY c.name",
      account
    );
  }

  public List<Map<String, Object>> memberships(UUID account) {
    return db.queryForList(
      "SELECT c.name,c.slug,m.role FROM companies c JOIN memberships m ON m.company_id=c.id WHERE m.account_id=? AND m.status='ACTIVE' AND m.role IN ('COMPANY_ADMIN','SUPPORT_AGENT') ORDER BY c.name",
      account
    );
  }

  public Optional<Map<String, Object>> dashboard(String slug, UUID account) {
    var rows = db.queryForList(
      "SELECT c.id,c.name,c.slug,m.role FROM companies c JOIN memberships m ON m.company_id=c.id WHERE c.slug=? AND m.account_id=? AND m.status='ACTIVE' AND m.role IN ('COMPANY_ADMIN','SUPPORT_AGENT')",
      slug,
      account
    );
    if (rows.isEmpty()) return Optional.empty();
    var workspace = rows.getFirst();
    Object company = workspace.get("id");
    workspace.put(
      "totalTickets",
      db.queryForObject(
        "SELECT count(*) FROM tickets WHERE company_id=?",
        Long.class,
        company
      )
    );
    workspace.put(
      "openTickets",
      db.queryForObject(
        "SELECT count(*) FROM tickets WHERE company_id=? AND status IN ('OPEN','ASSIGNED','IN_PROGRESS')",
        Long.class,
        company
      )
    );
    workspace.put(
      "agents",
      db.queryForObject(
        "SELECT count(*) FROM memberships WHERE company_id=? AND role='SUPPORT_AGENT' AND status='ACTIVE'",
        Long.class,
        company
      )
    );
    workspace.put(
      "admins",
      db.queryForObject(
        "SELECT count(*) FROM memberships WHERE company_id=? AND role='COMPANY_ADMIN' AND status='ACTIVE'",
        Long.class,
        company
      )
    );
    for (String state : List.of(
      "ASSIGNED",
      "IN_PROGRESS",
      "RESOLVED",
      "CLOSED"
    )) {
      workspace.put(
        state.equals("IN_PROGRESS")
          ? "inProgressTickets"
          : state.toLowerCase(Locale.ROOT) + "Tickets",
        db.queryForObject(
          "SELECT count(*) FROM tickets WHERE company_id=? AND status=?",
          Long.class,
          company,
          state
        )
      );
    }
    workspace.put(
      "unassignedTickets",
      db.queryForObject(
        "SELECT count(*) FROM tickets WHERE company_id=? AND status='OPEN' AND assigned_agent_id IS NULL",
        Long.class,
        company
      )
    );
    workspace.put(
      "myTickets",
      db.queryForObject(
        "SELECT count(*) FROM tickets WHERE company_id=? AND assigned_agent_id=? AND status IN ('ASSIGNED','IN_PROGRESS')",
        Long.class,
        company,
        account
      )
    );
    workspace.put(
      "unreadMessages",
      db.queryForObject(
        "SELECT count(*) FROM ticket_messages msg LEFT JOIN ticket_reads r ON r.company_id=msg.company_id AND r.ticket_id=msg.ticket_id AND r.account_id=? WHERE msg.company_id=? AND msg.sender_id<>? AND msg.position>coalesce(r.last_position,0)",
        Long.class,
        account,
        company,
        account
      )
    );
    return Optional.of(workspace);
  }
}
