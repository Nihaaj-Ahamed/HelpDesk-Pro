package com.helpdesk.sla;

import jakarta.validation.Valid;
import java.util.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
public class SlaController {

  private final SlaService service;

  public SlaController(SlaService service) {
    this.service = service;
  }

  @GetMapping("/api/workspaces/{slug}/sla/policies")
  public List<Map<String, Object>> policies(
    @PathVariable String slug,
    @AuthenticationPrincipal Jwt jwt
  ) {
    return service.policies(slug, jwt);
  }

  @PostMapping("/api/workspaces/{slug}/sla/policies")
  public void save(
    @PathVariable String slug,
    @AuthenticationPrincipal Jwt jwt,
    @Valid @RequestBody SlaService.Policy policy
  ) {
    service.save(slug, jwt, policy);
  }

  @GetMapping("/api/workspaces/{slug}/sla/queue")
  public List<Map<String, Object>> queue(
    @PathVariable String slug,
    @AuthenticationPrincipal Jwt jwt
  ) {
    return service.queue(slug, jwt);
  }

  @GetMapping("/api/workspaces/{slug}/sla/overview")
  public Map<String, Object> overview(
    @PathVariable String slug,
    @AuthenticationPrincipal Jwt jwt
  ) {
    return service.overview(slug, jwt);
  }

  @GetMapping("/api/workspaces/{slug}/tickets/{id}/sla")
  public Map<String, Object> staff(
    @PathVariable String slug,
    @PathVariable UUID id,
    @AuthenticationPrincipal Jwt jwt
  ) {
    return service.detail(slug, id, jwt, true);
  }

  @GetMapping("/api/support/{slug}/tickets/{id}/sla")
  public Map<String, Object> customer(
    @PathVariable String slug,
    @PathVariable UUID id,
    @AuthenticationPrincipal Jwt jwt
  ) {
    return service.detail(slug, id, jwt, false);
  }
}
