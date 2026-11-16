package com.helpdesk.portal;

import com.helpdesk.identity.IdentityService;
import com.helpdesk.organizations.WorkspaceAccess;
import com.helpdesk.tickets.CategoryRepository;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PortalService {

  private final PortalRepository repository;
  private final WorkspaceAccess access;
  private final IdentityService identity;
  private final PortalMailer mail;
  private final CategoryRepository categories;

  public PortalService(
    PortalRepository repository,
    WorkspaceAccess access,
    IdentityService identity,
    PortalMailer mail,
    CategoryRepository categories
  ) {
    this.repository = repository;
    this.access = access;
    this.identity = identity;
    this.mail = mail;
    this.categories = categories;
  }

  public record Info(
    String name,
    String slug,
    List<CategoryRepository.Category> categories,
    List<String> priorities
  ) {}

  public record Session(String fullName, String email, String status) {}

  public record Customer(UUID companyId, UUID accountId) {}

  public PortalRepository.Company company(String slug) {
    var result = repository
      .company(slug)
      .orElseThrow(() ->
        new ResponseStatusException(
          HttpStatus.NOT_FOUND,
          "This support portal was not found."
        )
      );
    access.requireActiveCompany(result.id());
    return result;
  }

  public List<String> priorities(PortalRepository.Company company) {
    var all = List.of("LOW", "MEDIUM", "HIGH");
    return all.subList(0, all.indexOf(company.maxPriority()) + 1);
  }

  public Info info(String slug) {
    var c = company(slug);
    return new Info(c.name(), c.slug(), categories.list(c.id()), priorities(c));
  }

  public Session session(String slug, Jwt jwt) {
    UUID user = access.account(jwt);
    var c = company(slug);
    var person = repository.identity(user);
    var m = repository.membership(c.id(), user);
    String status = m
      .map(member ->
        !"ACTIVE".equals(member.status())
          ? "INACTIVE"
          : "CUSTOMER".equals(member.role())
            ? "ACTIVE"
            : "STAFF"
      )
      .orElse("VERIFY_REQUIRED");
    return new Session(person.fullName(), person.email(), status);
  }

  public Customer customer(String slug, Jwt jwt) {
    UUID user = access.account(jwt);
    var c = company(slug);
    var m = repository
      .membership(c.id(), user)
      .orElseThrow(() ->
        new ResponseStatusException(
          HttpStatus.FORBIDDEN,
          "Verify your email for this support portal before accessing tickets."
        )
      );
    if (
      !"CUSTOMER".equals(m.role()) || !"ACTIVE".equals(m.status())
    ) throw new ResponseStatusException(
      HttpStatus.FORBIDDEN,
      "Active customer access is required for this support portal."
    );
    return new Customer(c.id(), user);
  }

  @Transactional
  public IdentityService.Session register(
    String slug,
    PortalController.Registration request
  ) {
    var c = company(slug);
    UUID user = identity.createAccount(
      request.fullName(),
      IdentityService.email(request.email()),
      request.password(),
      request.confirmPassword()
    );
    access.lockCompany(c.id());
    send(c, user);
    return identity.startSession(user);
  }

  private void send(PortalRepository.Company c, UUID user) {
    var membership = repository.membership(c.id(), user);
    if (membership.isPresent()) throw new ResponseStatusException(
      HttpStatus.CONFLICT,
      "You already have a membership in this company. Sign in to continue or contact its administrator."
    );
    if (
      repository.recentProofs(c.id(), user) >= 5
    ) throw new ResponseStatusException(
      HttpStatus.TOO_MANY_REQUESTS,
      "Verification email limit reached. Please try again in an hour."
    );
    byte[] bytes = new byte[32];
    new SecureRandom().nextBytes(bytes);
    String token = Base64.getUrlEncoder()
      .withoutPadding()
      .encodeToString(bytes);
    repository.createProof(c.id(), user, IdentityService.hash(token));
    mail.verify(repository.identity(user).email(), c, token);
  }

  @Transactional
  public void requestProof(String slug, Jwt jwt) {
    UUID user = access.account(jwt);
    var c = company(slug);
    access.lockCompany(c.id());
    send(c, user);
  }

  @Transactional
  public void verify(String slug, Jwt jwt, String token) {
    UUID user = access.account(jwt);
    var c = company(slug);
    access.lockCompany(c.id());
    var proof = repository
      .proof(c.id(), IdentityService.hash(token))
      .orElseThrow(() ->
        new ResponseStatusException(
          HttpStatus.NOT_FOUND,
          "This verification link is invalid or belongs to another portal."
        )
      );
    if (!proof.accountId().equals(user)) throw new ResponseStatusException(
      HttpStatus.FORBIDDEN,
      "Sign in with the email address that received this verification link."
    );
    if ("USED".equals(proof.status())) throw new ResponseStatusException(
      HttpStatus.CONFLICT,
      "This verification link has already been used. Open your support dashboard."
    );
    if (
      !"PENDING".equals(proof.status()) ||
      !proof.expiresAt().isAfter(Instant.now())
    ) throw new ResponseStatusException(
      HttpStatus.GONE,
      "This verification link has expired or was replaced. Request a new email from the portal."
    );
    if (
      repository.membership(c.id(), user).isPresent()
    ) throw new ResponseStatusException(
      HttpStatus.CONFLICT,
      "This company membership already exists. Contact your administrator if access is inactive."
    );
    repository.verify(c.id(), user, proof.id());
  }
}
