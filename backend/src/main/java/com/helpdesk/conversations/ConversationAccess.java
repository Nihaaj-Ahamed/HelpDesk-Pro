package com.helpdesk.conversations;

import com.helpdesk.organizations.WorkspaceAccess;
import com.helpdesk.portal.PortalService;
import com.helpdesk.tickets.*;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ConversationAccess {

  private final WorkspaceAccess access;
  private final PortalService portals;
  private final TicketRepository tickets;

  public ConversationAccess(
    WorkspaceAccess access,
    PortalService portals,
    TicketRepository tickets
  ) {
    this.access = access;
    this.portals = portals;
    this.tickets = tickets;
  }

  public record Viewer(
    UUID company,
    UUID account,
    String role,
    boolean staff,
    TicketModels.Ticket ticket
  ) {
    public boolean writable() {
      return (
        !ticket.status().equals("CLOSED") &&
        (!staff ||
          role.equals("COMPANY_ADMIN") ||
          (ticket.assignedAgent() != null &&
            ticket.assignedAgent().id().equals(account)))
      );
    }
  }

  public Viewer require(String slug, UUID id, Jwt jwt, boolean staff) {
    if (
      jwt == null ||
      jwt.getExpiresAt() == null ||
      !jwt.getExpiresAt().isAfter(Instant.now())
    ) throw new ResponseStatusException(
      HttpStatus.UNAUTHORIZED,
      "Session expired. Please sign in again."
    );
    UUID company, account;
    String role;
    if (staff) {
      var m = access.staff(slug, jwt);
      company = m.companyId();
      account = m.accountId();
      role = m.role();
    } else {
      var m = portals.customer(slug, jwt);
      company = m.companyId();
      account = m.accountId();
      role = "CUSTOMER";
    }
    var ticket = tickets
      .detail(
        new TicketModels.Scope(company, staff ? null : account, "ALL", account),
        id
      )
      .orElseThrow(() ->
        new ResponseStatusException(HttpStatus.NOT_FOUND, "Ticket not found.")
      );
    return new Viewer(company, account, role, staff, ticket);
  }

  public void lock(UUID company) {
    access.lockCompany(company);
  }
}
