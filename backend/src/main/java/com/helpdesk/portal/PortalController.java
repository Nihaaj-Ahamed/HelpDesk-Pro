package com.helpdesk.portal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/support/{slug}")
public class PortalController {

  private final PortalService service;

  public PortalController(PortalService service) {
    this.service = service;
  }

  public record Registration(
    @NotBlank @Size(max = 100) String fullName,
    @NotBlank @Email @Size(max = 254) String email,
    @NotBlank @Size(min = 12, max = 72) String password,
    @NotBlank @Size(max = 72) String confirmPassword
  ) {
    @Override
    public String toString() {
      return "CustomerRegistration[REDACTED]";
    }
  }

  public record Proof(
    @NotNull @Pattern(regexp = "[A-Za-z0-9_-]{43}") String token
  ) {
    @Override
    public String toString() {
      return "PortalProof[REDACTED]";
    }
  }

  @GetMapping
  @io.swagger.v3.oas.annotations.security.SecurityRequirements
  public PortalService.Info info(@PathVariable String slug) {
    return service.info(slug);
  }

  @GetMapping("/session")
  public PortalService.Session session(
    @PathVariable String slug,
    @AuthenticationPrincipal Jwt jwt
  ) {
    return service.session(slug, jwt);
  }

  @PostMapping("/verification")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void requestProof(
    @PathVariable String slug,
    @AuthenticationPrincipal Jwt jwt
  ) {
    service.requestProof(slug, jwt);
  }

  @PostMapping("/verify")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void verify(
    @PathVariable String slug,
    @AuthenticationPrincipal Jwt jwt,
    @Valid @RequestBody Proof request
  ) {
    service.verify(slug, jwt, request.token());
  }
}
