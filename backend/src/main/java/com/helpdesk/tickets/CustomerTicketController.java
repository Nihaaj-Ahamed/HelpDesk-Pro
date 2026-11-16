package com.helpdesk.tickets;

import static com.helpdesk.tickets.TicketModels.*;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@Validated
@RequestMapping("/api/support/{slug}")
public class CustomerTicketController {

  private final TicketService tickets;
  private final TicketWorkflow workflow;

  public CustomerTicketController(
    TicketService tickets,
    TicketWorkflow workflow
  ) {
    this.workflow = workflow;
    this.tickets = tickets;
  }

  @GetMapping("/tickets")
  public Page list(
    @PathVariable String slug,
    @AuthenticationPrincipal Jwt jwt,
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
    return tickets.customerList(
      slug,
      jwt,
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
    return tickets.customerDetail(slug, jwt, id);
  }

  @GetMapping("/dashboard")
  public Dashboard dashboard(
    @PathVariable String slug,
    @AuthenticationPrincipal Jwt jwt
  ) {
    return tickets.customerDashboard(slug, jwt);
  }

  @PostMapping("/tickets")
  @ResponseStatus(HttpStatus.CREATED)
  public Ticket create(
    @PathVariable String slug,
    @AuthenticationPrincipal Jwt jwt,
    @Valid @RequestBody Create request
  ) {
    return tickets.create(slug, jwt, request);
  }

  @GetMapping("/tickets/{id}/activity")
  public java.util.List<TicketWorkflow.Activity> activity(
    @PathVariable String slug,
    @PathVariable UUID id,
    @AuthenticationPrincipal Jwt jwt
  ) {
    return workflow.history(slug, jwt, id, false);
  }

  @GetMapping("/tickets/{id}/actions")
  public TicketWorkflow.Options actions(
    @PathVariable String slug,
    @PathVariable UUID id,
    @AuthenticationPrincipal Jwt jwt
  ) {
    return workflow.options(slug, jwt, id, false);
  }

  @PostMapping("/tickets/{id}/actions")
  public Ticket change(
    @PathVariable String slug,
    @PathVariable UUID id,
    @AuthenticationPrincipal Jwt jwt,
    @jakarta.validation.Valid @RequestBody TicketWorkflow.Change change
  ) {
    return workflow.change(slug, jwt, id, false, change);
  }
}
