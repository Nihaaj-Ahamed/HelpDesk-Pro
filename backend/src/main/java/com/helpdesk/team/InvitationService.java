package com.helpdesk.team;

import com.helpdesk.identity.IdentityService;
import com.helpdesk.organizations.WorkspaceAccess;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class InvitationService {

  private final TeamRepository repository;
  private final WorkspaceAccess access;
  private final IdentityService identity;

  public InvitationService(
    TeamRepository repository,
    WorkspaceAccess access,
    IdentityService identity
  ) {
    this.repository = repository;
    this.access = access;
    this.identity = identity;
  }

  public record Preview(
    String companyName,
    String slug,
    String email,
    String role,
    Instant expiresAt,
    boolean accountExists
  ) {}

  private TeamRepository.Invitation find(String token) {
    return repository
      .byToken(IdentityService.hash(token))
      .orElseThrow(() ->
        new ResponseStatusException(
          HttpStatus.NOT_FOUND,
          "This invitation link is invalid or has been replaced. Ask your company administrator for a new invitation."
        )
      );
  }

  public Preview preview(String token) {
    var i = find(token);
    TeamService.requirePending(i);
    return new Preview(
      i.companyName(),
      i.slug(),
      i.email(),
      i.role(),
      i.expiresAt(),
      repository.accountExists(i.email())
    );
  }

  private TeamRepository.Invitation locked(String token) {
    var first = find(token);
    access.lockCompany(first.companyId());
    var current = find(token);
    TeamService.requirePending(current);
    return current;
  }

  private void accept(TeamRepository.Invitation i, UUID user) {
    if (
      !i.email().equals(repository.accountEmail(user))
    ) throw new ResponseStatusException(
      HttpStatus.FORBIDDEN,
      "Sign in with the invited email address to accept this invitation."
    );
    if (
      repository.hasMembership(i.companyId(), i.email())
    ) throw new ResponseStatusException(
      HttpStatus.CONFLICT,
      "You are already a company member. Contact your administrator if your membership is inactive."
    );
    // The unconsumed emailed token plus the matching account proves ownership.
    // An arbitrary, previously unverified account email alone never grants access.
    repository.accept(i, user);
  }

  @Transactional
  public void acceptExisting(String token, Jwt jwt) {
    UUID user = access.account(jwt);
    var i = locked(token);
    accept(i, user);
  }

  @Transactional
  public IdentityService.Session register(InvitationController.Registration r) {
    var i = locked(r.token());
    UUID user = identity.createInvitedAccount(
      r.fullName(),
      i.email(),
      r.password(),
      r.confirmPassword()
    );
    accept(i, user);
    return identity.startSession(user);
  }
}
