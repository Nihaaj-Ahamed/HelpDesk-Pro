package com.helpdesk.portal;

import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PortalRepository {

  private final JdbcTemplate db;

  public PortalRepository(JdbcTemplate db) {
    this.db = db;
  }

  public record Company(
    UUID id,
    String name,
    String slug,
    String maxPriority
  ) {}

  public record Identity(UUID id, String fullName, String email) {}

  public record Membership(String role, String status) {}

  public record Proof(
    UUID id,
    UUID accountId,
    String status,
    Instant expiresAt
  ) {}

  public Optional<Company> company(String slug) {
    return db
      .query(
        "SELECT id,name,slug,customer_max_priority FROM companies WHERE slug=?",
        (r, n) ->
          new Company(
            r.getObject("id", UUID.class),
            r.getString("name"),
            r.getString("slug"),
            r.getString("customer_max_priority")
          ),
        slug
      )
      .stream()
      .findFirst();
  }

  public Identity identity(UUID account) {
    return db.queryForObject(
      "SELECT id,full_name,email FROM accounts WHERE id=?",
      (r, n) ->
        new Identity(
          r.getObject("id", UUID.class),
          r.getString("full_name"),
          r.getString("email")
        ),
      account
    );
  }

  public Optional<Membership> membership(UUID company, UUID account) {
    return db
      .query(
        "SELECT role,status FROM memberships WHERE company_id=? AND account_id=?",
        (r, n) -> new Membership(r.getString("role"), r.getString("status")),
        company,
        account
      )
      .stream()
      .findFirst();
  }

  public int recentProofs(UUID company, UUID account) {
    return db.queryForObject(
      "SELECT count(*) FROM portal_verifications WHERE company_id=? AND account_id=? AND created_at>now()-interval '1 hour'",
      Integer.class,
      company,
      account
    );
  }

  public void createProof(UUID company, UUID account, String hash) {
    db.update(
      "UPDATE portal_verifications SET status='REPLACED' WHERE company_id=? AND account_id=? AND status='PENDING'",
      company,
      account
    );
    db.update(
      "INSERT INTO portal_verifications(id,company_id,account_id,token_hash) VALUES (?,?,?,?)",
      UUID.randomUUID(),
      company,
      account,
      hash
    );
  }

  public Optional<Proof> proof(UUID company, String hash) {
    return db
      .query(
        "SELECT id,account_id,status,expires_at FROM portal_verifications WHERE company_id=? AND token_hash=?",
        (r, n) ->
          new Proof(
            r.getObject("id", UUID.class),
            r.getObject("account_id", UUID.class),
            r.getString("status"),
            r.getTimestamp("expires_at").toInstant()
          ),
        company,
        hash
      )
      .stream()
      .findFirst();
  }

  public void verify(UUID company, UUID account, UUID proof) {
    db.update(
      "INSERT INTO memberships(account_id,company_id,role) VALUES (?,?,'CUSTOMER') ON CONFLICT(account_id,company_id) DO NOTHING",
      account,
      company
    );
    db.update(
      "UPDATE accounts SET email_verified_at=COALESCE(email_verified_at,now()) WHERE id=?",
      account
    );
    db.update(
      "UPDATE portal_verifications SET status='USED' WHERE id=? AND company_id=? AND account_id=?",
      proof,
      company,
      account
    );
  }
}
