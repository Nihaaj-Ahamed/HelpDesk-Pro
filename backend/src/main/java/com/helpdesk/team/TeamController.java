package com.helpdesk.team;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@Validated
@RequestMapping("/api/workspaces/{slug}/team")
public class TeamController {

  private final TeamService service;

  public TeamController(TeamService service) {
    this.service = service;
  }

  public record Invite(
    @NotBlank @Email @Size(max = 254) String email,
    @NotNull @Pattern(regexp = "SUPPORT_AGENT") String role
  ) {}

  public record Status(
    @NotNull @Pattern(regexp = "ACTIVE|INACTIVE") String status
  ) {}

  @GetMapping("/members")
  public List<TeamRepository.MemberView> members(
    @PathVariable String slug,
    @AuthenticationPrincipal Jwt jwt,
    @RequestParam(defaultValue = "") @Size(max = 100) String search,
    @RequestParam(defaultValue = "") @Pattern(
      regexp = "|COMPANY_ADMIN|SUPPORT_AGENT"
    ) String role,
    @RequestParam(defaultValue = "") @Pattern(
      regexp = "|ACTIVE|INACTIVE"
    ) String status
  ) {
    return service.members(slug, jwt, search, role, status);
  }

  @GetMapping("/invitations")
  public List<TeamRepository.InvitationView> invitations(
    @PathVariable String slug,
    @AuthenticationPrincipal Jwt jwt
  ) {
    return service.invitations(slug, jwt);
  }

  @PostMapping("/invitations")
  @ResponseStatus(HttpStatus.CREATED)
  public void invite(
    @PathVariable String slug,
    @AuthenticationPrincipal Jwt jwt,
    @Valid @RequestBody Invite request
  ) {
    service.invite(slug, jwt, request.email());
  }

  @PostMapping("/invitations/{id}/resend")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void resend(
    @PathVariable String slug,
    @PathVariable UUID id,
    @AuthenticationPrincipal Jwt jwt
  ) {
    service.resend(slug, jwt, id);
  }

  @PostMapping("/invitations/{id}/revoke")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void revoke(
    @PathVariable String slug,
    @PathVariable UUID id,
    @AuthenticationPrincipal Jwt jwt
  ) {
    service.revoke(slug, jwt, id);
  }

  @PostMapping("/members/{account}/status")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void status(
    @PathVariable String slug,
    @PathVariable UUID account,
    @AuthenticationPrincipal Jwt jwt,
    @Valid @RequestBody Status request
  ) {
    service.status(slug, jwt, account, request.status());
  }
}
