package com.helpdesk.tickets;

import static com.helpdesk.tickets.TicketModels.*;

import com.helpdesk.organizations.WorkspaceAccess;
import com.helpdesk.portal.PortalService;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class TicketWorkflow {

  private final com.helpdesk.sla.SlaService sla;
  private final com.helpdesk.notifications.NotificationService notices;
  private final JdbcTemplate db;
  private final WorkspaceAccess access;
  private final PortalService portals;
  private final TicketRepository tickets;

  public TicketWorkflow(
    JdbcTemplate db,
    WorkspaceAccess access,
    PortalService portals,
    TicketRepository tickets,
    com.helpdesk.sla.SlaService sla,
    com.helpdesk.notifications.NotificationService notices
  ) {
    this.sla = sla;
    this.notices = notices;
    this.db = db;
    this.access = access;
    this.portals = portals;
    this.tickets = tickets;
  }

  public record Change(
    @NotNull @Min(0) Long version,
    @NotBlank
    @Pattern(regexp = "ASSIGN|CLAIM|UNASSIGN|STATUS|PRIORITY|REOPEN")
    String action,
    UUID agentId,
    @Size(max = 32) String value
  ) {}

  public record Activity(
    UUID id,
    String kind,
    String previousValue,
    String newValue,
    String actorName,
    String actorRole,
    java.time.Instant createdAt
  ) {}

  public record Options(
    List<String> actions,
    List<String> statuses,
    List<Map<String, Object>> agents
  ) {}

  private ResponseStatusException error(HttpStatus status, String message) {
    return new ResponseStatusException(status, message);
  }

  private Scope scope(String slug, Jwt jwt, boolean staff) {
    if (staff) {
      var m = access.staff(slug, jwt);
      return new Scope(m.companyId(), null);
    }
    var m = portals.customer(slug, jwt);
    return new Scope(m.companyId(), m.accountId());
  }

  private Ticket ticket(Scope scope, UUID id) {
    return tickets
      .detail(scope, id)
      .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "Ticket not found."));
  }

  public List<Activity> history(String slug, Jwt jwt, UUID id, boolean staff) {
    var scope = scope(slug, jwt, staff);
    ticket(scope, id);
    return db
      .query(
        "SELECT * FROM ticket_activity WHERE company_id=? AND ticket_id=? " +
          (staff
            ? ""
            : "AND kind IN ('CREATED','STATUS','REOPENED','PRIORITY') ") +
          "ORDER BY event_order",
        (r, n) ->
          new Activity(
            r.getObject("id", UUID.class),
            r.getString("kind"),
            r.getString("previous_value"),
            r.getString("new_value"),
            staff ? r.getString("actor_name") : null,
            staff ? r.getString("actor_role") : null,
            r.getTimestamp("created_at").toInstant()
          ),
        scope.company(),
        id
      )
      .stream()
      .filter(a -> staff || !a.kind().equals("ASSIGNMENT"))
      .toList();
  }

  public Options options(String slug, Jwt jwt, UUID id, boolean staff) {
    var scope = scope(slug, jwt, staff);
    var t = ticket(scope, id);
    var actions = new ArrayList<String>();
    var statuses = new ArrayList<String>();
    var agents = new ArrayList<Map<String, Object>>();
    boolean admin =
      staff && access.staff(slug, jwt).role().equals("COMPANY_ADMIN");
    UUID actor = access.account(jwt);
    boolean owner =
      t.assignedAgent() != null && t.assignedAgent().id().equals(actor);
    boolean active = Set.of("OPEN", "ASSIGNED", "IN_PROGRESS").contains(
      t.status()
    );
    if (staff && active) {
      if (admin) {
        actions.add("ASSIGN");
        agents.addAll(
          db.queryForList(
            "SELECT a.id,a.full_name AS name,(SELECT count(*) FROM tickets t WHERE t.company_id=m.company_id AND t.assigned_agent_id=m.account_id AND t.status IN ('ASSIGNED','IN_PROGRESS')) AS workload FROM memberships m JOIN accounts a ON a.id=m.account_id WHERE m.company_id=? AND m.role='SUPPORT_AGENT' AND m.status='ACTIVE' ORDER BY a.full_name",
            scope.company()
          )
        );
      } else if (t.status().equals("OPEN")) actions.add("CLAIM");
      if (t.assignedAgent() != null && (admin || owner)) actions.add(
        "UNASSIGN"
      );
      if (admin || owner) {
        actions.add("PRIORITY");
        if (t.status().equals("ASSIGNED")) statuses.add("IN_PROGRESS");
        if (
          Set.of("ASSIGNED", "IN_PROGRESS").contains(t.status())
        ) statuses.add("RESOLVED");
      }
    }
    if (
      t.status().equals("RESOLVED") && (!staff || admin || owner)
    ) statuses.add("CLOSED");
    if (
      Set.of("RESOLVED", "CLOSED").contains(t.status()) &&
      (!staff || admin || owner)
    ) actions.add("REOPEN");
    return new Options(actions, statuses, agents);
  }

  @Transactional
  public Ticket change(
    String slug,
    Jwt jwt,
    UUID id,
    boolean staff,
    Change change
  ) {
    var scope = scope(slug, jwt, staff);
    access.lockCompany(scope.company());
    scope = scope(slug, jwt, staff);
    var t = ticket(scope, id);
    if (t.version() != change.version()) throw error(
      HttpStatus.CONFLICT,
      "This ticket changed. Refresh it before trying again."
    );
    var options = options(slug, jwt, id, staff);
    String status = t.status(),
      priority = t.priority();
    UUID agent = t.assignedAgent() == null ? null : t.assignedAgent().id();
    UUID oldAgent = agent;
    if (change.action().equals("STATUS")) {
      if (!options.statuses().contains(change.value())) throw error(
        HttpStatus.CONFLICT,
        "This status transition is not allowed."
      );
      status = change.value();
    } else {
      if (!options.actions().contains(change.action())) throw error(
        HttpStatus.FORBIDDEN,
        "This action is not permitted for this ticket."
      );
      switch (change.action()) {
        case "CLAIM":
          agent = access.account(jwt);
          status = "ASSIGNED";
          break;
        case "ASSIGN":
          if (
            change.agentId() == null ||
            options
              .agents()
              .stream()
              .noneMatch(a -> change.agentId().equals(a.get("id")))
          ) throw error(
            HttpStatus.BAD_REQUEST,
            "Select an active Support Agent from this company."
          );
          agent = change.agentId();
          if (status.equals("OPEN")) status = "ASSIGNED";
          break;
        case "UNASSIGN":
          agent = null;
          status = "OPEN";
          break;
        case "REOPEN":
          agent = null;
          status = "OPEN";
          break;
        case "PRIORITY":
          if (
            change.value() == null ||
            !Set.of("LOW", "MEDIUM", "HIGH", "CRITICAL").contains(
              change.value()
            )
          ) throw error(HttpStatus.BAD_REQUEST, "Select a valid priority.");
          priority = change.value();
          break;
        default:
          throw error(HttpStatus.BAD_REQUEST, "Unsupported action.");
      }
    }
    if (
      status.equals(t.status()) &&
      priority.equals(t.priority()) &&
      Objects.equals(agent, oldAgent)
    ) throw error(HttpStatus.CONFLICT, "The ticket already has these values.");
    db.update(
      "UPDATE tickets SET status=?,priority=?,assigned_agent_id=?,version=version+1,updated_at=clock_timestamp() WHERE company_id=? AND id=? AND version=?",
      status,
      priority,
      agent,
      scope.company(),
      id,
      t.version()
    );
    UUID actor = access.account(jwt);
    String role = staff ? access.staff(slug, jwt).role() : "CUSTOMER";
    if (!Objects.equals(agent, oldAgent)) record(
      scope.company(),
      id,
      actor,
      role,
      "ASSIGNMENT",
      agentLabel(oldAgent),
      agentLabel(agent)
    );
    if (!status.equals(t.status())) record(
      scope.company(),
      id,
      actor,
      role,
      change.action().equals("REOPEN") ? "REOPENED" : "STATUS",
      t.status(),
      status
    );
    if (!priority.equals(t.priority())) record(
      scope.company(),
      id,
      actor,
      role,
      "PRIORITY",
      t.priority(),
      priority
    );
    if (status.equals("RESOLVED") && !status.equals(t.status())) sla.resolved(
      scope.company(),
      id,
      actor
    );
    if (!Objects.equals(agent, oldAgent)) notices.ticket(
      scope.company(),
      id,
      actor,
      com.helpdesk.notifications.NotificationService.Type.ASSIGNMENT,
      "assignment:" + id + ":" + (t.version() + 1),
      oldAgent
    );
    if (!status.equals(t.status())) notices.ticket(
      scope.company(),
      id,
      actor,
      com.helpdesk.notifications.NotificationService.Type.STATUS,
      "status:" + id + ":" + (t.version() + 1),
      null
    );
    return ticket(scope, id);
  }

  private String agentLabel(UUID id) {
    if (id == null) return null;
    return (
      db.queryForObject(
        "SELECT full_name FROM accounts WHERE id=?",
        String.class,
        id
      ) +
      " (" +
      id +
      ")"
    );
  }

  public void record(
    UUID company,
    UUID ticket,
    UUID actor,
    String role,
    String kind,
    String previous,
    String next
  ) {
    db.update(
      "INSERT INTO ticket_activity(id,company_id,ticket_id,actor_id,actor_name,actor_role,kind,previous_value,new_value) SELECT ?,?,?,id,full_name,?,?,?,? FROM accounts WHERE id=?",
      UUID.randomUUID(),
      company,
      ticket,
      role,
      kind,
      previous,
      next,
      actor
    );
  }
}
