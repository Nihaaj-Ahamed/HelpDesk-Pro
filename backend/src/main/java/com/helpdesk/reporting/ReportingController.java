package com.helpdesk.reporting;

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
public class ReportingController {

  private final ReportingService service;

  public ReportingController(ReportingService service) {
    this.service = service;
  }

  @GetMapping("/api/workspaces/{slug}/analytics")
  public Map<String, Object> analytics(
    @PathVariable String slug,
    @AuthenticationPrincipal Jwt jwt,
    @Valid @ModelAttribute ReportingService.Filter filter
  ) {
    return service.analytics(slug, jwt, false, filter);
  }

  @GetMapping("/api/workspaces/{slug}/my-performance")
  public Map<String, Object> personal(
    @PathVariable String slug,
    @AuthenticationPrincipal Jwt jwt,
    @Valid @ModelAttribute ReportingService.Filter filter
  ) {
    return service.analytics(slug, jwt, true, filter);
  }

  @GetMapping("/api/workspaces/{slug}/reports/export")
  public ResponseEntity<byte[]> export(
    @PathVariable String slug,
    @AuthenticationPrincipal Jwt jwt,
    @Valid @ModelAttribute ReportingService.Filter filter,
    @RequestParam(defaultValue = "TICKETS") @Pattern(
      regexp = "TICKETS|STATUS|PRIORITY|AGENTS|SLA|ESCALATIONS|OVERDUE"
    ) String report
  ) {
    return csv(service.export(slug, jwt, false, filter, report), report);
  }

  @GetMapping("/api/workspaces/{slug}/my-performance/export")
  public ResponseEntity<byte[]> personalExport(
    @PathVariable String slug,
    @AuthenticationPrincipal Jwt jwt,
    @Valid @ModelAttribute ReportingService.Filter filter
  ) {
    return csv(
      service.export(slug, jwt, true, filter, "AGENTS"),
      "MY_PERFORMANCE"
    );
  }

  private ResponseEntity<byte[]> csv(String value, String report) {
    return ResponseEntity.ok()
      .header(
        HttpHeaders.CONTENT_DISPOSITION,
        "attachment; filename=helpdesk-" +
          report.toLowerCase(Locale.ROOT) +
          ".csv"
      )
      .contentType(
        new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8)
      )
      .body(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }
}
