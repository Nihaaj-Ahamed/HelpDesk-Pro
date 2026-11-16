package com.helpdesk.platform;

import com.helpdesk.organizations.WorkspaceAccess;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PlatformService {

  private final JdbcTemplate db;
  private final WorkspaceAccess access;

  public PlatformService(JdbcTemplate db, WorkspaceAccess access) {
    this.db = db;
    this.access = access;
  }

  public UUID require(Jwt jwt) {
    UUID actor = access.account(jwt);
    if (
      !Boolean.TRUE.equals(
        db.queryForObject(
          "SELECT EXISTS(SELECT 1 FROM platform_administrators WHERE account_id=?)",
          Boolean.class,
          actor
        )
      )
    ) throw new ResponseStatusException(
      HttpStatus.FORBIDDEN,
      "Platform administrator authority is required."
    );
    return actor;
  }

  public record Change(
    @NotNull @Pattern(regexp = "ACTIVE|SUSPENDED") String status,
    @NotBlank @Size(min = 5, max = 500) String reason,
    @Min(0) long version
  ) {}

  private String companySelect() {
    return "SELECT c.id,c.name,c.slug,c.created_at,c.status,c.version,(SELECT count(*) FROM memberships m WHERE m.company_id=c.id) AS members,(SELECT count(*) FROM tickets t WHERE t.company_id=c.id) AS tickets FROM companies c ";
  }

  public Map<String, Object> directory(
    Jwt jwt,
    String search,
    String status,
    int page
  ) {
    require(jwt);
    var args = new ArrayList<Object>(
      List.of(
        search.strip().toLowerCase(Locale.ROOT),
        search.strip().toLowerCase(Locale.ROOT),
        status,
        status
      )
    );
    String where =
      " WHERE (position(? in lower(c.name))>0 OR position(? in c.slug)>0) AND (?='' OR c.status=?)";
    long total = db.queryForObject(
      "SELECT count(*) FROM companies c" + where,
      Long.class,
      args.toArray()
    );
    args.add(page * 20);
    return Map.of(
      "items",
      db.queryForList(
        companySelect() +
          where +
          " ORDER BY c.created_at DESC NULLS LAST,c.id LIMIT 20 OFFSET ?",
        args.toArray()
      ),
      "total",
      total,
      "page",
      page
    );
  }

  public Map<String, Object> detail(Jwt jwt, UUID company) {
    require(jwt);
    var rows = db.queryForList(companySelect() + " WHERE c.id=?", company);
    if (rows.isEmpty()) throw new ResponseStatusException(
      HttpStatus.NOT_FOUND,
      "Company not found."
    );
    return rows.getFirst();
  }

  @Transactional
  public void change(Jwt jwt, UUID company, Change change) {
    UUID actor = require(jwt);
    db.queryForList("SELECT id FROM companies WHERE id=? FOR UPDATE", company);
    require(jwt);
    if (
      db.update(
        "UPDATE companies SET status=?,version=version+1 WHERE id=? AND version=? AND status<>?",
        change.status(),
        company,
        change.version(),
        change.status()
      ) != 1
    ) throw new ResponseStatusException(
      HttpStatus.CONFLICT,
      "Company changed, is unavailable, or already has this status."
    );
    db.update(
      "INSERT INTO platform_audit(id,actor_id,actor_name,company_id,action,reason) SELECT ?,id,full_name,?,?,? FROM accounts WHERE id=?",
      UUID.randomUUID(),
      company,
      change.status().equals("ACTIVE")
        ? "COMPANY_REACTIVATED"
        : "COMPANY_SUSPENDED",
      change.reason().strip(),
      actor
    );
  }

  public Map<String, Object> overview(
    Jwt jwt,
    java.time.LocalDate from,
    java.time.LocalDate to
  ) {
    require(jwt);
    java.time.LocalDate end =
        to == null ? java.time.LocalDate.now(java.time.ZoneOffset.UTC) : to,
      start = from == null ? end.minusDays(29) : from;
    if (
      end.isBefore(start) ||
      java.time.temporal.ChronoUnit.DAYS.between(start, end) > 365
    ) throw new ResponseStatusException(
      HttpStatus.BAD_REQUEST,
      "Use an ordered date range of at most 366 days."
    );
    var begin = java.sql.Timestamp.from(
      start.atStartOfDay().toInstant(java.time.ZoneOffset.UTC)
    );
    var finish = java.sql.Timestamp.from(
      end.plusDays(1).atStartOfDay().toInstant(java.time.ZoneOffset.UTC)
    );
    return Map.of(
      "counts",
      db.queryForMap(
        "SELECT count(*) AS companies,count(*) FILTER(WHERE status='ACTIVE') AS active,count(*) FILTER(WHERE status='SUSPENDED') AS suspended,count(*) FILTER(WHERE created_at IS NULL) AS registration_date_unknown,(SELECT count(*) FROM accounts) AS users,(SELECT count(*) FROM tickets) AS tickets,(SELECT count(*) FROM memberships WHERE status='ACTIVE') AS active_memberships,(SELECT count(*) FROM email_outbox WHERE state='FAILED' AND attempts>=6) AS failed_deliveries FROM companies"
      ),
      "registrations",
      db.queryForList(
        "SELECT (created_at AT TIME ZONE 'UTC')::date AS day,count(*) AS value FROM companies WHERE created_at>=? AND created_at<? GROUP BY 1 ORDER BY 1",
        begin,
        finish
      ),
      "ticketVolume",
      db.queryForList(
        "SELECT (created_at AT TIME ZONE 'UTC')::date AS day,count(*) AS value FROM tickets WHERE created_at>=? AND created_at<? GROUP BY 1 ORDER BY 1",
        begin,
        finish
      ),
      "recentCompanies",
      db.queryForList(
        companySelect() +
          " WHERE c.created_at>=? AND c.created_at<? ORDER BY c.created_at DESC,c.id LIMIT 10",
        begin,
        finish
      )
    );
  }

  public Map<String, Object> audit(Jwt jwt, int page) {
    require(jwt);
    return Map.of(
      "items",
      db.queryForList(
        "SELECT a.id,a.actor_name,a.action,a.reason,a.created_at,c.name AS company FROM platform_audit a LEFT JOIN companies c ON c.id=a.company_id ORDER BY a.created_at DESC,a.id LIMIT 20 OFFSET ?",
        page * 20
      ),
      "total",
      db.queryForObject("SELECT count(*) FROM platform_audit", Long.class),
      "page",
      page
    );
  }
}
