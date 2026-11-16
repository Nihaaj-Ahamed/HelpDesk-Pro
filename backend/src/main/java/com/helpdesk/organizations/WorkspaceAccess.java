package com.helpdesk.organizations;

import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Shared, database-backed session and tenant policy; roles never come from the client. */
@Service
public class WorkspaceAccess {

  private final JdbcTemplate db;
  private final WorkspaceRepository workspaces;

  public WorkspaceAccess(JdbcTemplate db, WorkspaceRepository workspaces) {
    this.db = db;
    this.workspaces = workspaces;
  }

  public record Member(
    UUID companyId,
    UUID accountId,
    String companyName,
    String slug,
    String role
  ) {}

  public UUID account(Jwt jwt) {
    UUID id = UUID.fromString(jwt.getSubject());
    if (
      !workspaces.activeSession(UUID.fromString(jwt.getId()), id)
    ) throw new ResponseStatusException(
      HttpStatus.UNAUTHORIZED,
      "Session expired. Please sign in again."
    );
    return id;
  }

  public Member member(String slug, Jwt jwt) {
    UUID id = account(jwt);
    var rows = db.queryForList(
      "SELECT c.id,c.name,c.slug,m.role FROM companies c JOIN memberships m ON m.company_id=c.id WHERE c.slug=? AND m.account_id=? AND m.status='ACTIVE'",
      slug,
      id
    );
    if (rows.isEmpty()) throw new ResponseStatusException(
      HttpStatus.NOT_FOUND,
      "Workspace not found or membership is inactive."
    );
    var r = rows.getFirst();
    if (
      !Set.of("COMPANY_ADMIN", "SUPPORT_AGENT").contains(
        r.get("role").toString()
      )
    ) throw new ResponseStatusException(
      HttpStatus.FORBIDDEN,
      "Staff access is required for this workspace."
    );
    requireActiveCompany((UUID) r.get("id"));
    return new Member(
      (UUID) r.get("id"),
      id,
      r.get("name").toString(),
      r.get("slug").toString(),
      r.get("role").toString()
    );
  }

  public Member admin(String slug, Jwt jwt) {
    var member = member(slug, jwt);
    if (
      !"COMPANY_ADMIN".equals(member.role())
    ) throw new ResponseStatusException(
      HttpStatus.FORBIDDEN,
      "Company administrator access is required."
    );
    return member;
  }

  /** Explicit policy for every staff ticket read; CUSTOMER is never a staff role. */
  public Member staff(String slug, Jwt jwt) {
    return member(slug, jwt);
  }

  public Member lockAdmin(String slug, Jwt jwt) {
    var member = admin(slug, jwt);
    lockCompany(member.companyId());
    return admin(slug, jwt);
  }

  public void requireActiveCompany(UUID company) {
    String status = db.queryForObject(
      "SELECT status FROM companies WHERE id=?",
      String.class,
      company
    );
    if (!"ACTIVE".equals(status)) throw new ResponseStatusException(
      HttpStatus.LOCKED,
      "This company is suspended. Contact your administrator."
    );
  }

  public void lockCompany(UUID company) {
    db.queryForObject(
      "SELECT id FROM companies WHERE id=? FOR UPDATE",
      UUID.class,
      company
    );
    requireActiveCompany(company);
  }
}
