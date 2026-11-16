package com.helpdesk.identity;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class IdentityService {

  private final JdbcTemplate db;
  private final PasswordEncoder passwords;
  private final JwtEncoder jwt;
  private final AccountRepository accounts;
  private final com.helpdesk.tickets.CategoryRepository categories;

  public IdentityService(
    JdbcTemplate db,
    PasswordEncoder passwords,
    JwtEncoder jwt,
    AccountRepository accounts,
    com.helpdesk.tickets.CategoryRepository categories
  ) {
    this.db = db;
    this.passwords = passwords;
    this.jwt = jwt;
    this.accounts = accounts;
    this.categories = categories;
  }

  public record Session(String accessToken, String refreshToken) {
    @Override
    public String toString() {
      return "Session[REDACTED]";
    }
  }

  public static String email(String value) {
    return value.strip().toLowerCase(Locale.ROOT);
  }

  @Transactional
  public Session register(AuthController.Registration r) {
    if (
      !r.password().equals(r.confirmPassword())
    ) throw new ResponseStatusException(
      HttpStatus.BAD_REQUEST,
      "Passwords do not match."
    );
    if (
      r.password().getBytes(StandardCharsets.UTF_8).length > 72
    ) throw new ResponseStatusException(
      HttpStatus.BAD_REQUEST,
      "Password must be at most 72 UTF-8 bytes."
    );
    UUID user = UUID.randomUUID(),
      company = UUID.randomUUID();
    accounts.saveAndFlush(
      new Account(
        user,
        r.fullName().strip(),
        email(r.email()),
        passwords.encode(r.password())
      )
    );
    db.update(
      "INSERT INTO companies(id,name,slug) VALUES (?,?,?)",
      company,
      r.companyName().strip(),
      r.slug().strip().toLowerCase(Locale.ROOT)
    );
    categories.initialize(company);
    db.update(
      "INSERT INTO memberships(account_id,company_id,role) VALUES (?,?,'COMPANY_ADMIN')",
      user,
      company
    );
    return issue(user);
  }

  @Transactional
  public Session login(AuthController.Login r) {
    if (
      r.password().getBytes(StandardCharsets.UTF_8).length > 72
    ) throw unauthorized();
    var account = accounts.findByEmail(email(r.email()));
    // Always perform the expensive check, including for unknown accounts.
    String hash = account.map(Account::passwordHash).orElse(DUMMY);
    boolean matches = passwords.matches(r.password(), hash);
    if (account.isEmpty() || !matches) throw unauthorized();
    return issue(account.get().id());
  }

  private static final String DUMMY =
    "$2a$12$R9h/cIPz0gi.URNNX3kh2OPST9/PgBkqquzi.Ss7KIUgO2t0jWMUW";

  @Transactional
  public UUID createInvitedAccount(
    String fullName,
    String email,
    String password,
    String confirmation
  ) {
    return createAccount(fullName, email, password, confirmation);
  }

  @Transactional
  public UUID createAccount(
    String fullName,
    String email,
    String password,
    String confirmation
  ) {
    if (!password.equals(confirmation)) throw new ResponseStatusException(
      HttpStatus.BAD_REQUEST,
      "Passwords do not match."
    );
    if (
      password.getBytes(StandardCharsets.UTF_8).length > 72
    ) throw new ResponseStatusException(
      HttpStatus.BAD_REQUEST,
      "Password must be at most 72 UTF-8 bytes."
    );
    if (
      accounts.findByEmail(email).isPresent()
    ) throw new ResponseStatusException(
      HttpStatus.CONFLICT,
      "An account already exists for this email. Sign in with your existing password."
    );
    UUID id = UUID.randomUUID();
    accounts.saveAndFlush(
      new Account(id, fullName.strip(), email, passwords.encode(password))
    );
    return id;
  }

  @Transactional
  public Session startSession(UUID user) {
    return issue(user);
  }

  @Transactional(noRollbackFor = ResponseStatusException.class)
  public Session refresh(String token) {
    if (token == null) throw unauthorized();
    // Lock the entire family so rotation, replay detection and logout serialize.
    var rows = db.queryForList(
      "SELECT s.id,s.account_id,s.family_id,s.expires_at,s.revoked,f.revoked AS family_revoked FROM refresh_sessions s JOIN session_families f ON f.id=s.family_id WHERE s.token_hash=? FOR UPDATE OF f",
      hash(token)
    );
    if (rows.isEmpty()) throw unauthorized();
    // Read again after acquiring the family lock: the first SELECT may have
    // waited on another rotation and seen the token's pre-rotation snapshot.
    var r = db.queryForMap(
      "SELECT s.id,s.account_id,s.family_id,s.expires_at,s.revoked,f.revoked AS family_revoked FROM refresh_sessions s JOIN session_families f ON f.id=s.family_id WHERE s.token_hash=?",
      hash(token)
    );
    if (
      (Boolean) r.get("revoked") ||
      (Boolean) r.get("family_revoked") ||
      ((java.sql.Timestamp) r.get("expires_at"))
        .toInstant()
        .isBefore(Instant.now())
    ) {
      db.update(
        "UPDATE session_families SET revoked=true WHERE id=?",
        r.get("family_id")
      );
      throw unauthorized();
    }
    db.update(
      "UPDATE refresh_sessions SET revoked=true WHERE id=?",
      r.get("id")
    );
    return issue(
      (UUID) r.get("account_id"),
      (UUID) r.get("family_id"),
      ((java.sql.Timestamp) r.get("expires_at")).toInstant()
    );
  }

  public void logout(String token) {
    if (token != null) db.update(
      "UPDATE session_families SET revoked=true WHERE id IN (SELECT family_id FROM refresh_sessions WHERE token_hash=?)",
      hash(token)
    );
  }

  private Session issue(UUID user) {
    UUID family = UUID.randomUUID();
    Instant expires = Instant.now().plus(Duration.ofDays(7));
    db.update(
      "INSERT INTO session_families(id,account_id,expires_at) VALUES (?,?,?)",
      family,
      user,
      java.sql.Timestamp.from(expires)
    );
    return issue(user, family, expires);
  }

  private Session issue(UUID user, UUID family, Instant expires) {
    UUID session = UUID.randomUUID();
    byte[] bytes = new byte[32];
    new SecureRandom().nextBytes(bytes);
    String refresh = Base64.getUrlEncoder()
      .withoutPadding()
      .encodeToString(bytes);
    db.update(
      "INSERT INTO refresh_sessions(id,account_id,family_id,token_hash,expires_at) VALUES (?,?,?,?,?)",
      session,
      user,
      family,
      hash(refresh),
      java.sql.Timestamp.from(expires)
    );
    var claims = JwtClaimsSet.builder()
      .issuer("helpdesk-pro")
      .audience(List.of("helpdesk-web"))
      .subject(user.toString())
      .id(session.toString())
      .issuedAt(Instant.now())
      .expiresAt(Instant.now().plusSeconds(600))
      .build();
    return new Session(
      jwt
        .encode(
          JwtEncoderParameters.from(
            JwsHeader.with(MacAlgorithm.HS256).build(),
            claims
          )
        )
        .getTokenValue(),
      refresh
    );
  }

  public static String hash(String token) {
    try {
      return HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(
          token.getBytes(StandardCharsets.UTF_8)
        )
      );
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException(ex);
    }
  }

  public static ResponseStatusException unauthorized() {
    return new ResponseStatusException(
      HttpStatus.UNAUTHORIZED,
      "Invalid credentials or expired session."
    );
  }
}
