package com.helpdesk.team;

import com.helpdesk.identity.IdentityService;
import com.helpdesk.organizations.WorkspaceAccess;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class TeamService {

  private final TeamRepository repository;
  private final WorkspaceAccess access;
  private final InvitationMailer mailer;
  private final int hourlyLimit;

  public TeamService(
    TeamRepository repository,
    WorkspaceAccess access,
    InvitationMailer mailer,
    @Value("${app.invitation-limit-per-hour}") int hourlyLimit
  ) {
    this.repository = repository;
    this.access = access;
    this.mailer = mailer;
    this.hourlyLimit = hourlyLimit;
  }

  public List<TeamRepository.MemberView> members(
    String slug,
    Jwt jwt,
    String search,
    String role,
    String status
  ) {
    return repository.members(
      access.admin(slug, jwt).companyId(),
      search.strip().toLowerCase(Locale.ROOT),
      role,
      status
    );
  }

  public List<TeamRepository.InvitationView> invitations(String slug, Jwt jwt) {
    return repository.invitations(access.admin(slug, jwt).companyId());
  }

  private void rate(UUID company) {
    if (
      repository.deliveriesLastHour(company) >= hourlyLimit
    ) throw new ResponseStatusException(
      HttpStatus.TOO_MANY_REQUESTS,
      "This workspace has reached its invitation email limit. Please try again later."
    );
  }

  private String token() {
    byte[] bytes = new byte[32];
    new SecureRandom().nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  @Transactional
  public void invite(String slug, Jwt jwt, String email) {
    var admin = access.lockAdmin(slug, jwt);
    email = IdentityService.email(email);
    if (
      repository.hasMembership(admin.companyId(), email)
    ) throw new ResponseStatusException(
      HttpStatus.CONFLICT,
      "This person is already a company member. You can reactivate an inactive agent instead."
    );
    repository.expirePending(admin.companyId());
    if (
      repository.pending(admin.companyId(), email)
    ) throw new ResponseStatusException(
      HttpStatus.CONFLICT,
      "An active invitation already exists for this email."
    );
    rate(admin.companyId());
    String token = token();
    UUID id = repository.create(
      admin.companyId(),
      admin.accountId(),
      email,
      IdentityService.hash(token)
    );
    mailer.send(email, admin.companyName(), token);
    repository.delivery(admin.companyId(), id, admin.accountId());
  }

  private TeamRepository.Invitation invitation(UUID company, UUID id) {
    return repository
      .byId(company, id)
      .orElseThrow(() ->
        new ResponseStatusException(
          HttpStatus.NOT_FOUND,
          "Invitation not found."
        )
      );
  }

  public static void requirePending(TeamRepository.Invitation i) {
    if ("ACCEPTED".equals(i.status())) throw new ResponseStatusException(
      HttpStatus.CONFLICT,
      "This invitation has already been accepted. Sign in to your workspace."
    );
    if ("REVOKED".equals(i.status())) throw new ResponseStatusException(
      HttpStatus.GONE,
      "This invitation was revoked. Ask your company administrator for a new invitation."
    );
    if (
      !"PENDING".equals(i.status()) || !i.expiresAt().isAfter(Instant.now())
    ) throw new ResponseStatusException(
      HttpStatus.GONE,
      "This invitation has expired. Ask your company administrator for a new invitation."
    );
  }

  @Transactional
  public void resend(String slug, Jwt jwt, UUID id) {
    var admin = access.lockAdmin(slug, jwt);
    var i = invitation(admin.companyId(), id);
    requirePending(i);
    rate(admin.companyId());
    String token = token();
    repository.rotate(admin.companyId(), id, IdentityService.hash(token));
    mailer.send(i.email(), admin.companyName(), token);
    repository.delivery(admin.companyId(), id, admin.accountId());
  }

  @Transactional
  public void revoke(String slug, Jwt jwt, UUID id) {
    var admin = access.lockAdmin(slug, jwt);
    var i = invitation(admin.companyId(), id);
    requirePending(i);
    repository.revoke(admin.companyId(), id);
  }

  @Transactional
  public void status(String slug, Jwt jwt, UUID account, String status) {
    var admin = access.lockAdmin(slug, jwt);
    var member = repository
      .member(admin.companyId(), account)
      .orElseThrow(() ->
        new ResponseStatusException(HttpStatus.NOT_FOUND, "Member not found.")
      );
    if (
      account.equals(admin.accountId()) ||
      !"SUPPORT_AGENT".equals(member.role())
    ) throw new ResponseStatusException(
      HttpStatus.BAD_REQUEST,
      "Only Support Agent memberships can be changed. Company administrators, including yourself, are protected."
    );
    repository.status(admin.companyId(), account, status);
  }
}
