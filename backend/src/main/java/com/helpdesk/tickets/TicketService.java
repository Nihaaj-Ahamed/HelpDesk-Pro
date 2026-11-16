package com.helpdesk.tickets;

import static com.helpdesk.tickets.TicketModels.*;

import com.helpdesk.organizations.WorkspaceAccess;
import com.helpdesk.portal.PortalService;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class TicketService {

  private final com.helpdesk.sla.SlaService sla;
  private final com.helpdesk.notifications.NotificationService notices;
  private final TicketRepository tickets;
  private final PortalService portals;
  private final WorkspaceAccess access;

  public TicketService(
    TicketRepository tickets,
    PortalService portals,
    WorkspaceAccess access,
    com.helpdesk.sla.SlaService sla,
    com.helpdesk.notifications.NotificationService notices
  ) {
    this.sla = sla;
    this.notices = notices;
    this.tickets = tickets;
    this.portals = portals;
    this.access = access;
  }

  private Scope customerScope(String slug, Jwt jwt) {
    var c = portals.customer(slug, jwt);
    return new Scope(c.companyId(), c.accountId());
  }

  private Scope staffScope(String slug, Jwt jwt) {
    var staff = access.staff(slug, jwt);
    // Enforce staff roles explicitly before creating any company-wide scope.
    if (
      !Set.of("COMPANY_ADMIN", "SUPPORT_AGENT").contains(staff.role())
    ) throw new ResponseStatusException(
      HttpStatus.FORBIDDEN,
      "Staff access is required."
    );
    return new Scope(staff.companyId(), null, "ALL", staff.accountId());
  }

  public Page customerList(
    String slug,
    Jwt jwt,
    String search,
    String status,
    String priority,
    UUID category,
    int page,
    int size
  ) {
    return tickets.list(
      customerScope(slug, jwt),
      search,
      status,
      priority,
      category,
      page,
      size
    );
  }

  public Page staffList(
    String slug,
    Jwt jwt,
    String queue,
    String search,
    String status,
    String priority,
    UUID category,
    int page,
    int size
  ) {
    return tickets.list(
      new Scope(
        staffScope(slug, jwt).company(),
        null,
        queue,
        access.account(jwt)
      ),
      search,
      status,
      priority,
      category,
      page,
      size
    );
  }

  private Ticket detail(Scope scope, UUID id) {
    return tickets
      .detail(scope, id)
      .orElseThrow(() ->
        new ResponseStatusException(HttpStatus.NOT_FOUND, "Ticket not found.")
      );
  }

  public Ticket customerDetail(String slug, Jwt jwt, UUID id) {
    return detail(customerScope(slug, jwt), id);
  }

  public Ticket staffDetail(String slug, Jwt jwt, UUID id) {
    return detail(staffScope(slug, jwt), id);
  }

  private Dashboard dashboard(Scope scope) {
    return new Dashboard(
      tickets.counts(scope),
      tickets.list(scope, "", "", "", null, 0, 5).items()
    );
  }

  public Dashboard customerDashboard(String slug, Jwt jwt) {
    return dashboard(customerScope(slug, jwt));
  }

  public Dashboard staffDashboard(String slug, Jwt jwt) {
    return dashboard(staffScope(slug, jwt));
  }

  @Transactional
  public Ticket create(String slug, Jwt jwt, Create request) {
    var scope = customerScope(slug, jwt);
    access.lockCompany(scope.company());
    scope = customerScope(slug, jwt);
    if (tickets.recentCount(scope) >= 20) throw new ResponseStatusException(
      HttpStatus.TOO_MANY_REQUESTS,
      "You have reached the ticket submission limit. Please try again in an hour."
    );
    var info = portals.info(slug);
    String priority =
      request.priority() == null
        ? info.priorities().contains("MEDIUM")
          ? "MEDIUM"
          : "LOW"
        : request.priority().name();
    if (
      !info.priorities().contains(priority)
    ) throw new ResponseStatusException(
      HttpStatus.BAD_REQUEST,
      "This priority is not available for customer submissions in this company."
    );
    UUID category = request.categoryId();
    if (category == null) category = info
      .categories()
      .stream()
      .filter(c -> c.name().equals("General"))
      .map(CategoryRepository.Category::id)
      .findFirst()
      .orElseThrow(() ->
        new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "Select a valid support category."
        )
      );
    UUID chosen = category;
    if (
      info
        .categories()
        .stream()
        .noneMatch(c -> c.id().equals(chosen))
    ) throw new ResponseStatusException(
      HttpStatus.BAD_REQUEST,
      "Select an active category from this support portal."
    );
    UUID id = tickets.create(scope, request, category, priority);
    sla.created(scope.company(), id);
    notices.ticket(
      scope.company(),
      id,
      scope.customer(),
      com.helpdesk.notifications.NotificationService.Type.CREATED,
      "ticket:" + id,
      null
    );
    return tickets.detail(scope, id).orElseThrow();
  }
}
