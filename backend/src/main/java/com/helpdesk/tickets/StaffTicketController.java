package com.helpdesk.tickets;

import static com.helpdesk.tickets.TicketModels.*;

import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@Validated
@RequestMapping("/api/workspaces/{slug}")
public class StaffTicketController {

  private final TicketService tickets;
  private final TicketWorkflow workflow;

  public StaffTicketController(TicketService tickets, TicketWorkflow workflow) {
    this.workflow = workflow;
    this.tickets = tickets;
  }

  @GetMapping("/tickets")
  public Page list(
    @PathVariable String slug,
    @AuthenticationPrincipal Jwt jwt,
    @RequestParam(defaultValue = "ALL") @Pattern(
      regexp = "ALL|MINE|UNASSIGNED|IN_PROGRESS|DONE"
    ) String queue,
    @RequestParam(defaultValue = "") @Size(max = 100) String search,
    @RequestParam(defaultValue = "") @Pattern(
      regexp = "|OPEN|ASSIGNED|IN_PROGRESS|RESOLVED|CLOSED"
    ) String status,
    @RequestParam(defaultValue = "") @Pattern(
      regexp = "|LOW|MEDIUM|HIGH|CRITICAL"
    ) String priority,
    @RequestParam(required = false) UUID category,
    @RequestParam(defaultValue = "0") @Min(0) @Max(100000) int page,
    @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
  ) {
    return tickets.staffList(
      slug,
      jwt,
      queue,
      search,
      status,
      priority,
      category,
      page,
      size
    );
  }

  @GetMapping("/tickets/{id}")
  public Ticket detail(
    @PathVariable String slug,
    @PathVariable UUID id,
    @AuthenticationPrincipal Jwt jwt
  ) {
    return tickets.staffDetail(slug, jwt, id);
  }

  @GetMapping("/ticket-overview")
  public Dashboard dashboard(
    @PathVariable String slug,
    @AuthenticationPrincipal Jwt jwt
  ) {
    return tickets.staffDashboard(slug, jwt);
  }

  @GetMapping("/tickets/{id}/activity")
  public java.util.List<TicketWorkflow.Activity> activity(
    @PathVariable String slug,
    @PathVariable UUID id,
    @AuthenticationPrincipal Jwt jwt
  ) {
    return workflow.history(slug, jwt, id, true);
  }

  @GetMapping("/tickets/{id}/actions")
  public TicketWorkflow.Options actions(
    @PathVariable String slug,
    @PathVariable UUID id,
    @AuthenticationPrincipal Jwt jwt
  ) {
    return workflow.options(slug, jwt, id, true);
  }

  @PostMapping("/tickets/{id}/actions")
  public Ticket change(
    @PathVariable String slug,
    @PathVariable UUID id,
    @AuthenticationPrincipal Jwt jwt,
    @jakarta.validation.Valid @RequestBody TicketWorkflow.Change change
  ) {
    return workflow.change(slug, jwt, id, true, change);
  }
}
