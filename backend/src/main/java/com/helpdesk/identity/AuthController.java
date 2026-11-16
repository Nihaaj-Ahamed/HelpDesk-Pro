package com.helpdesk.identity;

import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/auth")
@io.swagger.v3.oas.annotations.security.SecurityRequirements
public class AuthController {

  private final IdentityService service;
  private final boolean secure;
  private final String origin;
  private final com.helpdesk.team.InvitationService invitations;
  private final com.helpdesk.portal.PortalService portals;

  public AuthController(
    IdentityService service,
    @Value("${app.secure-cookie}") boolean secure,
    @Value("${app.origin}") String origin,
    com.helpdesk.team.InvitationService invitations,
    com.helpdesk.portal.PortalService portals
  ) {
    this.service = service;
    this.secure = secure;
    this.origin = origin;
    this.invitations = invitations;
    this.portals = portals;
  }

  public record Registration(
    @NotBlank @Size(max = 100) String fullName,
    @NotBlank @Email @Size(max = 254) String email,
    @NotBlank @Size(min = 12, max = 72) String password,
    @NotBlank String confirmPassword,
    @NotBlank @Size(max = 120) String companyName,
    @Pattern(regexp = "[a-z0-9]+(?:-[a-z0-9]+)*")
    @NotNull
    @Size(min = 3, max = 48)
    String slug
  ) {
    @Override
    public String toString() {
      return "Registration[REDACTED]";
    }
  }

  public record Login(
    @NotBlank @Email @Size(max = 254) String email,
    @NotBlank @Size(max = 72) String password
  ) {
    @Override
    public String toString() {
      return "Login[REDACTED]";
    }
  }

  public record Access(String accessToken) {
    @Override
    public String toString() {
      return "Access[REDACTED]";
    }
  }

  private void checkOrigin(HttpServletRequest request) {
    // Cookie endpoints require an explicit trusted Origin; SameSite is additional protection.
    if (
      !origin.equals(request.getHeader("Origin"))
    ) throw new ResponseStatusException(
      HttpStatus.FORBIDDEN,
      "Untrusted request origin."
    );
  }

  private ResponseEntity<Access> response(
    IdentityService.Session s,
    HttpStatus status
  ) {
    return ResponseEntity.status(status)
      .header(HttpHeaders.CACHE_CONTROL, "no-store")
      .header(
        HttpHeaders.SET_COOKIE,
        cookie(s.refreshToken(), Duration.ofDays(7))
      )
      .body(new Access(s.accessToken()));
  }

  private String cookie(String token, Duration age) {
    return ResponseCookie.from("hd_refresh", token)
      .httpOnly(true)
      .secure(secure)
      .sameSite("Strict")
      .path("/api/auth")
      .maxAge(age)
      .build()
      .toString();
  }

  @PostMapping("/register")
  public ResponseEntity<Access> register(
    @Valid @RequestBody Registration r,
    HttpServletRequest req
  ) {
    checkOrigin(req);
    return response(service.register(r), HttpStatus.CREATED);
  }

  @PostMapping("/login")
  public ResponseEntity<Access> login(
    @Valid @RequestBody Login r,
    HttpServletRequest req
  ) {
    checkOrigin(req);
    return response(service.login(r), HttpStatus.OK);
  }

  @PostMapping("/invitations/register")
  public ResponseEntity<Access> registerInvited(
    @Valid @RequestBody com.helpdesk.team.InvitationController.Registration request,
    HttpServletRequest req
  ) {
    checkOrigin(req);
    return response(invitations.register(request), HttpStatus.CREATED);
  }

  @PostMapping("/refresh")
  public ResponseEntity<Access> refresh(
    @CookieValue(name = "hd_refresh", required = false) String token,
    HttpServletRequest req
  ) {
    checkOrigin(req);
    return response(service.refresh(token), HttpStatus.OK);
  }

  @PostMapping("/support/{slug}/register")
  public ResponseEntity<Access> registerCustomer(
    @PathVariable String slug,
    @Valid @RequestBody com.helpdesk.portal.PortalController.Registration request,
    HttpServletRequest req
  ) {
    checkOrigin(req);
    return response(portals.register(slug, request), HttpStatus.CREATED);
  }

  @PostMapping("/logout")
  public ResponseEntity<Void> logout(
    @CookieValue(name = "hd_refresh", required = false) String token,
    HttpServletRequest req
  ) {
    checkOrigin(req);
    service.logout(token);
    return ResponseEntity.noContent()
      .header(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO))
      .build();
  }
}
