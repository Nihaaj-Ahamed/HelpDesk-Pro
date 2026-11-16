package com.helpdesk.notifications;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@Validated
@RequestMapping("/api/companies/{slug}/notifications")
public class NotificationController {

  private final NotificationService service;

  public NotificationController(NotificationService service) {
    this.service = service;
  }

  @GetMapping("/context")
  public NotificationService.Context context(
    @PathVariable String slug,
    @AuthenticationPrincipal Jwt jwt
  ) {
    return service.context(slug, jwt);
  }

  @GetMapping
  public Map<String, Object> list(
    @PathVariable String slug,
    @AuthenticationPrincipal Jwt jwt,
    @RequestParam(defaultValue = "") @Pattern(
      regexp = "|CREATED|ASSIGNMENT|REPLY|STATUS|SLA_WARNING|SLA_BREACH"
    ) String type,
    @RequestParam(defaultValue = "false") boolean unread,
    @RequestParam(defaultValue = "0") @Min(0) @Max(100000) int page,
    @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
  ) {
    return service.list(slug, jwt, type, unread, page, size);
  }

  @PostMapping("/read-all")
  public void all(@PathVariable String slug, @AuthenticationPrincipal Jwt jwt) {
    service.read(slug, jwt, null);
  }

  @PostMapping("/{id}/read")
  public void read(
    @PathVariable String slug,
    @PathVariable UUID id,
    @AuthenticationPrincipal Jwt jwt
  ) {
    service.read(slug, jwt, id);
  }

  @GetMapping("/preferences")
  public NotificationService.Preferences preferences(
    @PathVariable String slug,
    @AuthenticationPrincipal Jwt jwt
  ) {
    return service.preferences(slug, jwt);
  }

  @PostMapping("/preferences")
  public void preferences(
    @PathVariable String slug,
    @AuthenticationPrincipal Jwt jwt,
    @Valid @RequestBody NotificationService.Preferences value
  ) {
    service.preferences(slug, jwt, value);
  }

  @GetMapping("/deliveries")
  public List<Map<String, Object>> deliveries(
    @PathVariable String slug,
    @AuthenticationPrincipal Jwt jwt
  ) {
    return service.deliveries(slug, jwt);
  }

  @PostMapping("/deliveries/{id}/retry")
  public void retry(
    @PathVariable String slug,
    @PathVariable UUID id,
    @AuthenticationPrincipal Jwt jwt
  ) {
    service.retry(slug, jwt, id);
  }
}
