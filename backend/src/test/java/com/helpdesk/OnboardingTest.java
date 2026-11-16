package com.helpdesk;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class OnboardingTest {

  @Autowired
  com.helpdesk.identity.IdentityService identity;

  @Autowired
  org.springframework.security.oauth2.jwt.JwtEncoder encoder;

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
    "postgres:17-alpine"
  );

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("app.operations-jobs-enabled", () -> false);
    r.add("spring.datasource.url", postgres::getJdbcUrl);
    r.add("spring.datasource.username", postgres::getUsername);
    r.add("spring.datasource.password", postgres::getPassword);
    r.add("app.jwt-secret", () -> "test-only-secret-at-least-32-bytes-long");
    r.add("app.auth-rate-limit", () -> 200);
  }

  @Autowired
  MockMvc mvc;

  @Autowired
  ObjectMapper json;

  @Autowired
  JdbcTemplate db;

  @BeforeEach
  void clear() {
    db.execute(
      "TRUNCATE refresh_sessions,session_families,memberships,tickets,companies,accounts CASCADE"
    );
  }

  Map<String, String> registration(String email, String slug) {
    return new HashMap<>(
      Map.of(
        "fullName",
        "Alex Morgan",
        "email",
        email,
        "password",
        "a-secure-password-123",
        "confirmPassword",
        "a-secure-password-123",
        "companyName",
        "Acme Studio",
        "slug",
        slug
      )
    );
  }

  ResultActions postJson(String path, Object body) throws Exception {
    return mvc.perform(
      post(path)
        .header("Origin", "http://localhost:3000")
        .contentType("application/json")
        .content(json.writeValueAsBytes(body))
    );
  }

  MvcResult register(String email, String slug) throws Exception {
    return postJson("/api/auth/register", registration(email, slug))
      .andExpect(status().isCreated())
      .andReturn();
  }

  String token(MvcResult r) throws Exception {
    return json
      .readTree(r.getResponse().getContentAsString())
      .get("accessToken")
      .asText();
  }

  String cookie(MvcResult r) {
    return r
      .getResponse()
      .getHeader("Set-Cookie")
      .split(";", 2)[0]
      .split("=", 2)[1];
  }

  @Test
  void registrationCreatesSecureTenantAndRealDashboard() throws Exception {
    var r = register("OWNER@acme.test", "acme");
    assertThat(
      db.queryForObject("SELECT email FROM accounts", String.class)
    ).isEqualTo("owner@acme.test");
    assertThat(
      db.queryForObject("SELECT password_hash FROM accounts", String.class)
    )
      .startsWith("$2a$")
      .doesNotContain("a-secure-password");
    assertThat(
      db.queryForObject("SELECT role FROM memberships", String.class)
    ).isEqualTo("COMPANY_ADMIN");
    mvc
      .perform(
        get("/api/workspaces/acme").header(
          "Authorization",
          "Bearer " + token(r)
        )
      )
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.totalTickets").value(0))
      .andExpect(jsonPath("$.agents").value(0))
      .andExpect(jsonPath("$.admins").value(1));
    assertThat(r.getResponse().getHeader("Set-Cookie")).contains(
      "HttpOnly",
      "SameSite=Strict"
    );
  }

  @Test
  void duplicateEmailAndSlugRollbackWholeRegistration() throws Exception {
    register("owner@acme.test", "acme");
    postJson(
      "/api/auth/register",
      registration("OWNER@acme.test", "other")
    ).andExpect(status().isConflict());
    postJson(
      "/api/auth/register",
      registration("other@acme.test", "acme")
    ).andExpect(status().isConflict());
    assertThat(
      db.queryForObject("SELECT count(*) FROM accounts", Integer.class)
    ).isEqualTo(1);
    assertThat(
      db.queryForObject("SELECT count(*) FROM companies", Integer.class)
    ).isEqualTo(1);
  }

  @Test
  void invalidInputAndMismatchCreateNothing() throws Exception {
    var body = registration("invalid", "Bad Slug");
    body.put("password", "short");
    postJson("/api/auth/register", body).andExpect(status().isBadRequest());
    body = registration("owner@acme.test", "acme");
    body.put("confirmPassword", "different");
    postJson("/api/auth/register", body).andExpect(status().isBadRequest());
    assertThat(
      db.queryForObject("SELECT count(*) FROM accounts", Integer.class)
    ).isZero();
  }

  @Test
  void loginRejectsInvalidCredentials() throws Exception {
    register("owner@acme.test", "acme");
    postJson(
      "/api/auth/login",
      Map.of("email", "owner@acme.test", "password", "wrong-password")
    ).andExpect(status().isUnauthorized());
    postJson(
      "/api/auth/login",
      Map.of("email", "missing@acme.test", "password", "wrong-password")
    ).andExpect(status().isUnauthorized());
    postJson(
      "/api/auth/login",
      Map.of("email", "OWNER@acme.test", "password", "a-secure-password-123")
    )
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.accessToken").isString());
  }

  @Test
  void protectedAndCrossTenantRoutes() throws Exception {
    var a = register("a@acme.test", "alpha");
    register("b@acme.test", "bravo");
    mvc.perform(get("/api/me")).andExpect(status().isUnauthorized());
    mvc
      .perform(get("/api/me").header("Authorization", "Bearer forged"))
      .andExpect(status().isUnauthorized());
    mvc
      .perform(
        get("/api/workspaces/bravo").header(
          "Authorization",
          "Bearer " + token(a)
        )
      )
      .andExpect(status().isNotFound());
    mvc
      .perform(get("/api/me").header("Authorization", "Bearer " + token(a)))
      .andExpect(jsonPath("$.workspaces.length()").value(1));
  }

  @Test
  void refreshRotatesAndLogoutRevokesAccess() throws Exception {
    var a = register("a@acme.test", "alpha");
    var next = mvc
      .perform(
        post("/api/auth/refresh")
          .header("Origin", "http://localhost:3000")
          .cookie(new jakarta.servlet.http.Cookie("hd_refresh", cookie(a)))
      )
      .andExpect(status().isOk())
      .andReturn();
    mvc
      .perform(get("/api/me").header("Authorization", "Bearer " + token(a)))
      .andExpect(status().isUnauthorized());
    mvc
      .perform(
        post("/api/auth/logout")
          .header("Origin", "http://localhost:3000")
          .cookie(new jakarta.servlet.http.Cookie("hd_refresh", cookie(next)))
      )
      .andExpect(status().isNoContent());
    mvc
      .perform(get("/api/me").header("Authorization", "Bearer " + token(next)))
      .andExpect(status().isUnauthorized());
  }

  @Test
  void replayRevokesRotatedFamily() throws Exception {
    var a = register("a@acme.test", "alpha");
    var next = mvc
      .perform(
        post("/api/auth/refresh")
          .header("Origin", "http://localhost:3000")
          .cookie(new jakarta.servlet.http.Cookie("hd_refresh", cookie(a)))
      )
      .andExpect(status().isOk())
      .andReturn();
    mvc
      .perform(
        post("/api/auth/refresh")
          .header("Origin", "http://localhost:3000")
          .cookie(new jakarta.servlet.http.Cookie("hd_refresh", cookie(a)))
      )
      .andExpect(status().isUnauthorized());
    mvc
      .perform(get("/api/me").header("Authorization", "Bearer " + token(next)))
      .andExpect(status().isUnauthorized());
  }

  @Test
  void cookieEndpointsRejectCrossSiteOrigin() throws Exception {
    mvc
      .perform(post("/api/auth/refresh").header("Origin", "https://evil.test"))
      .andExpect(status().isForbidden());
    mvc.perform(post("/api/auth/logout")).andExpect(status().isForbidden());
  }

  @Test
  void expiredAccessAndRefreshSessionsAreRejected() throws Exception {
    var a = register("a@acme.test", "alpha");
    var claims = org.springframework.security.oauth2.jwt.JwtClaimsSet.builder()
      .issuer("helpdesk-pro")
      .audience(List.of("helpdesk-web"))
      .subject(UUID.randomUUID().toString())
      .id(UUID.randomUUID().toString())
      .issuedAt(java.time.Instant.now().minusSeconds(300))
      .expiresAt(java.time.Instant.now().minusSeconds(120))
      .build();
    var expired = encoder
      .encode(
        org.springframework.security.oauth2.jwt.JwtEncoderParameters.from(
          org.springframework.security.oauth2.jwt.JwsHeader.with(
            org.springframework.security.oauth2.jose.jws.MacAlgorithm.HS256
          ).build(),
          claims
        )
      )
      .getTokenValue();
    mvc
      .perform(get("/api/me").header("Authorization", "Bearer " + expired))
      .andExpect(status().isUnauthorized());
    db.update(
      "UPDATE refresh_sessions SET expires_at=now()-interval '1 minute'"
    );
    mvc
      .perform(
        post("/api/auth/refresh")
          .header("Origin", "http://localhost:3000")
          .cookie(new jakarta.servlet.http.Cookie("hd_refresh", cookie(a)))
      )
      .andExpect(status().isUnauthorized());
  }

  @Test
  void aggregatesStayWithinTenantAndRemovedMembershipCannotRead()
    throws Exception {
    var a = register("a@acme.test", "alpha");
    var b = register("b@acme.test", "bravo");
    db.update(
      "INSERT INTO tickets(id,company_id,subject) SELECT ?,id,'Private customer issue' FROM companies WHERE slug='bravo'",
      UUID.randomUUID()
    );
    mvc
      .perform(
        get("/api/workspaces/alpha").header(
          "Authorization",
          "Bearer " + token(a)
        )
      )
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.totalTickets").value(0));
    mvc
      .perform(
        get("/api/workspaces/bravo").header(
          "Authorization",
          "Bearer " + token(b)
        )
      )
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.totalTickets").value(1));
    db.update(
      "DELETE FROM memberships WHERE company_id IN (SELECT id FROM companies WHERE slug='alpha')"
    );
    mvc
      .perform(
        get("/api/workspaces/alpha").header(
          "Authorization",
          "Bearer " + token(a)
        )
      )
      .andExpect(status().isNotFound());
  }

  @Test
  void credentialRecordsRedactSecretsAndOnlyDigestIsStored() throws Exception {
    var body = registration("a@acme.test", "alpha");
    var a = register("a@acme.test", "alpha");
    assertThat(
      new com.helpdesk.identity.AuthController.Login(
        "a@acme.test",
        body.get("password")
      ).toString()
    ).doesNotContain(body.get("password"));
    assertThat(
      new com.helpdesk.identity.AuthController.Access(token(a)).toString()
    ).doesNotContain(token(a));
    assertThat(
      db.queryForObject("SELECT token_hash FROM refresh_sessions", String.class)
    )
      .isEqualTo(com.helpdesk.identity.IdentityService.hash(cookie(a)))
      .isNotEqualTo(cookie(a));
  }

  @Test
  void concurrentRefreshCannotIssueTwoSuccessors() throws Exception {
    var registration = register("a@acme.test", "alpha");
    String raw = cookie(registration);
    try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var start = new java.util.concurrent.CountDownLatch(1);
      java.util.concurrent.Callable<Boolean> rotate = () -> {
        start.await();
        try {
          identity.refresh(raw);
          return true;
        } catch (org.springframework.web.server.ResponseStatusException ex) {
          return false;
        }
      };
      var first = pool.submit(rotate);
      var second = pool.submit(rotate);
      start.countDown();
      assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(
        true,
        false
      );
      assertThat(
        db.queryForObject(
          "SELECT count(*) FROM refresh_sessions",
          Integer.class
        )
      ).isEqualTo(2);
      assertThat(
        db.queryForObject("SELECT revoked FROM session_families", Boolean.class)
      ).isTrue();
    }
  }
}
