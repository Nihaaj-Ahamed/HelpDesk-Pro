package com.helpdesk.platform;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@Validated
@RequestMapping("/api/platform")
public class PlatformController {

  private final PlatformService service;

  public PlatformController(PlatformService service) {
    this.service = service;
  }

  @GetMapping("/overview")
  public Map<String, Object> overview(
    @AuthenticationPrincipal Jwt jwt,
    @RequestParam(
      required = false
    ) @org.springframework.format.annotation.DateTimeFormat(
      iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE
    ) java.time.LocalDate from,
    @RequestParam(
      required = false
    ) @org.springframework.format.annotation.DateTimeFormat(
      iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE
    ) java.time.LocalDate to
  ) {
    return service.overview(jwt, from, to);
  }

  @GetMapping("/companies")
  public Map<String, Object> companies(
    @AuthenticationPrincipal Jwt jwt,
    @RequestParam(defaultValue = "") @Size(max = 100) String search,
    @RequestParam(defaultValue = "") @Pattern(
      regexp = "|ACTIVE|SUSPENDED"
    ) String status,
    @RequestParam(defaultValue = "0") @Min(0) @Max(100000) int page
  ) {
    return service.directory(jwt, search, status, page);
  }

  @GetMapping("/companies/{id}")
  public Map<String, Object> detail(
    @AuthenticationPrincipal Jwt jwt,
    @PathVariable UUID id
  ) {
    return service.detail(jwt, id);
  }

  @PostMapping("/companies/{id}/status")
  public void change(
    @AuthenticationPrincipal Jwt jwt,
    @PathVariable UUID id,
    @Valid @RequestBody PlatformService.Change change
  ) {
    service.change(jwt, id, change);
  }

  @GetMapping("/activity")
  public Map<String, Object> activity(
    @AuthenticationPrincipal Jwt jwt,
    @RequestParam(defaultValue = "0") @Min(0) @Max(100000) int page
  ) {
    return service.audit(jwt, page);
  }
}
