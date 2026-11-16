package com.helpdesk.team;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/invitations")
public class InvitationController {

  private final InvitationService service;

  public InvitationController(InvitationService service) {
    this.service = service;
  }

  public record Token(
    @NotNull @Pattern(regexp = "[A-Za-z0-9_-]{43}") String token
  ) {
    @Override
    public String toString() {
      return "InvitationToken[REDACTED]";
    }
  }

  public record Registration(
    @NotNull @Pattern(regexp = "[A-Za-z0-9_-]{43}") String token,
    @NotBlank @Size(max = 100) String fullName,
    @NotBlank @Size(min = 12, max = 72) String password,
    @NotBlank @Size(max = 72) String confirmPassword
  ) {
    @Override
    public String toString() {
      return "InvitationRegistration[REDACTED]";
    }
  }

  @PostMapping("/preview")
  @io.swagger.v3.oas.annotations.security.SecurityRequirements
  public ResponseEntity<InvitationService.Preview> preview(
    @Valid @RequestBody Token request
  ) {
    return ResponseEntity.ok()
      .cacheControl(CacheControl.noStore())
      .body(service.preview(request.token()));
  }

  @PostMapping("/accept")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void accept(
    @Valid @RequestBody Token request,
    @AuthenticationPrincipal Jwt jwt
  ) {
    service.acceptExisting(request.token(), jwt);
  }
}
