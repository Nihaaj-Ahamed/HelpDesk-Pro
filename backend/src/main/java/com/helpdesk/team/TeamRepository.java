package com.helpdesk.team;

import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class TeamRepository {

  private final JdbcTemplate db;

  public TeamRepository(JdbcTemplate db) {
    this.db = db;
  }

  public record MemberView(
    UUID id,
    String fullName,
    String email,
    String role,
    String status,
    Instant joinedAt
  ) {}

  public record InvitationView(
    UUID id,
    String email,
    String role,
    String status,
    Instant createdAt,
    Instant expiresAt,
    Instant lastSentAt,
    String inviterName
  ) {}

  public record Invitation(
    UUID id,
    UUID companyId,
    String companyName,
    String slug,
    String email,
    String role,
    String status,
    Instant expiresAt
  ) {}

  public List<MemberView> members(
    UUID company,
    String search,
    String role,
    String status
  ) {
    return db.query(
      "SELECT a.id,a.full_name,a.email,m.role,m.status,m.joined_at FROM memberships m JOIN accounts a ON a.id=m.account_id WHERE m.company_id=? AND m.role IN ('COMPANY_ADMIN','SUPPORT_AGENT') AND (?='' OR position(? in lower(a.full_name))>0 OR position(? in a.email)>0) AND (?='' OR m.role=?) AND (?='' OR m.status=?) ORDER BY m.role,a.full_name,a.id",
      (r, n) ->
        new MemberView(
          r.getObject("id", UUID.class),
          r.getString("full_name"),
          r.getString("email"),
          r.getString("role"),
          r.getString("status"),
          r.getTimestamp("joined_at") == null
            ? null
            : r.getTimestamp("joined_at").toInstant()
        ),
      company,
      search,
      search,
      search,
      role,
      role,
      status,
      status
    );
  }

  public List<InvitationView> invitations(UUID company) {
    return db.query(
      "SELECT i.id,i.email,i.role,CASE WHEN i.status='PENDING' AND i.expires_at<=now() THEN 'EXPIRED' ELSE i.status END AS display_status,i.created_at,i.expires_at,i.last_sent_at,a.full_name FROM invitations i JOIN accounts a ON a.id=i.inviter_id WHERE i.company_id=? ORDER BY i.created_at DESC,i.id",
      (r, n) ->
        new InvitationView(
          r.getObject("id", UUID.class),
          r.getString("email"),
          r.getString("role"),
          r.getString("display_status"),
          r.getTimestamp("created_at").toInstant(),
          r.getTimestamp("expires_at").toInstant(),
          r.getTimestamp("last_sent_at").toInstant(),
          r.getString("full_name")
        ),
      company
    );
  }

  private static final String INVITE_SELECT =
    "SELECT i.id,i.company_id,c.name,c.slug,i.email,i.role,i.status,i.expires_at FROM invitations i JOIN companies c ON c.id=i.company_id ";

  private List<Invitation> query(String where, Object... args) {
    return db.query(
      INVITE_SELECT + where,
      (r, n) ->
        new Invitation(
          r.getObject("id", UUID.class),
          r.getObject("company_id", UUID.class),
          r.getString("name"),
          r.getString("slug"),
          r.getString("email"),
          r.getString("role"),
          r.getString("status"),
          r.getTimestamp("expires_at").toInstant()
        ),
      args
    );
  }

  public Optional<Invitation> byToken(String hash) {
    return query("WHERE i.token_hash=?", hash).stream().findFirst();
  }

  public Optional<Invitation> byId(UUID company, UUID id) {
    return query("WHERE i.company_id=? AND i.id=?", company, id)
      .stream()
      .findFirst();
  }

  public boolean hasMembership(UUID company, String email) {
    return Boolean.TRUE.equals(
      db.queryForObject(
        "SELECT EXISTS(SELECT 1 FROM memberships m JOIN accounts a ON a.id=m.account_id WHERE m.company_id=? AND a.email=?)",
        Boolean.class,
        company,
        email
      )
    );
  }

  public boolean accountExists(String email) {
    return Boolean.TRUE.equals(
      db.queryForObject(
        "SELECT EXISTS(SELECT 1 FROM accounts WHERE email=?)",
        Boolean.class,
        email
      )
    );
  }

  public String accountEmail(UUID account) {
    return db.queryForObject(
      "SELECT email FROM accounts WHERE id=?",
      String.class,
      account
    );
  }

  public void expirePending(UUID company) {
    db.update(
      "UPDATE invitations SET status='EXPIRED' WHERE company_id=? AND status='PENDING' AND expires_at<=now()",
      company
    );
  }

  public boolean pending(UUID company, String email) {
    return Boolean.TRUE.equals(
      db.queryForObject(
        "SELECT EXISTS(SELECT 1 FROM invitations WHERE company_id=? AND email=? AND status='PENDING')",
        Boolean.class,
        company,
        email
      )
    );
  }

  public int deliveriesLastHour(UUID company) {
    return db.queryForObject(
      "SELECT count(*) FROM invitation_deliveries WHERE company_id=? AND delivered_at>now()-interval '1 hour'",
      Integer.class,
      company
    );
  }

  public UUID create(UUID company, UUID inviter, String email, String hash) {
    UUID id = UUID.randomUUID();
    db.update(
      "INSERT INTO invitations(id,company_id,inviter_id,email,token_hash,expires_at) VALUES (?,?,?,?,?,now()+interval '48 hours')",
      id,
      company,
      inviter,
      email,
      hash
    );
    return id;
  }

  public void rotate(UUID company, UUID id, String hash) {
    db.update(
      "UPDATE invitations SET token_hash=?,expires_at=now()+interval '48 hours',last_sent_at=now() WHERE company_id=? AND id=?",
      hash,
      company,
      id
    );
  }

  public void delivery(UUID company, UUID invitation, UUID actor) {
    db.update(
      "INSERT INTO invitation_deliveries(id,invitation_id,company_id,actor_id) VALUES (?,?,?,?)",
      UUID.randomUUID(),
      invitation,
      company,
      actor
    );
  }

  public void revoke(UUID company, UUID id) {
    db.update(
      "UPDATE invitations SET status='REVOKED' WHERE company_id=? AND id=?",
      company,
      id
    );
  }

  public Optional<MemberView> member(UUID company, UUID account) {
    return members(company, "", "", "")
      .stream()
      .filter(m -> m.id().equals(account))
      .findFirst();
  }

  public void status(UUID company, UUID account, String status) {
    db.update(
      "UPDATE memberships SET status=? WHERE company_id=? AND account_id=?",
      status,
      company,
      account
    );
  }

  public void accept(Invitation i, UUID user) {
    db.update(
      "INSERT INTO memberships(account_id,company_id,role) VALUES (?,?,'SUPPORT_AGENT')",
      user,
      i.companyId()
    );
    db.update(
      "UPDATE accounts SET email_verified_at=COALESCE(email_verified_at,now()) WHERE id=? AND email=?",
      user,
      i.email()
    );
    db.update(
      "UPDATE invitations SET status='ACCEPTED',accepted_at=now(),accepted_by=? WHERE id=? AND company_id=?",
      user,
      i.id(),
      i.companyId()
    );
  }
}
