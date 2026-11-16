package com.helpdesk;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.*;
import com.helpdesk.identity.IdentityService;
import com.helpdesk.team.InvitationMailer;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.*;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class TeamInvitationTest {

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
    "postgres:17-alpine"
  );

  @Container
  static GenericContainer<?> mailpit = new GenericContainer<>(
    "axllent/mailpit:v1.27"
  ).withExposedPorts(1025, 8025);

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("app.operations-jobs-enabled", () -> false);
    r.add("spring.datasource.url", postgres::getJdbcUrl);
    r.add("spring.datasource.username", postgres::getUsername);
    r.add("spring.datasource.password", postgres::getPassword);
    r.add("app.jwt-secret", () -> "test-only-secret-at-least-32-bytes-long");
    r.add("app.auth-rate-limit", () -> 500);
    r.add("app.invitation-limit-per-hour", () -> 3);
    // Docker Desktop scheduling can delay real SMTP under concurrent build load.
    // Keep bounded test timeouts; production retains its five-second limits.
    r.add("spring.mail.properties.mail.smtp.connectiontimeout", () -> 15000);
    r.add("spring.mail.properties.mail.smtp.timeout", () -> 15000);
    r.add("spring.mail.properties.mail.smtp.writetimeout", () -> 15000);
    r.add("spring.mail.host", mailpit::getHost);
    r.add("spring.mail.port", () -> mailpit.getMappedPort(1025));
  }

  @Autowired
  MockMvc mvc;

  @Autowired
  ObjectMapper json;

  @Autowired
  JdbcTemplate db;

  @Autowired
  InvitationMailer mailer;

  @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
  org.springframework.mail.javamail.JavaMailSender smtp;

  private final HttpClient http = HttpClient.newHttpClient();
  private String owner;
  private static final String PASSWORD = "a-secure-password-123";

  @BeforeEach
  void setup() throws Exception {
    db.execute(
      "TRUNCATE invitation_deliveries,invitations,refresh_sessions,session_families,memberships,tickets,companies,accounts CASCADE"
    );
    http.send(
      HttpRequest.newBuilder(mailUri("/api/v1/messages")).DELETE().build(),
      HttpResponse.BodyHandlers.discarding()
    );
    owner = owner("owner@alpha.test", "alpha");
  }

  URI mailUri(String path) {
    return URI.create(
      "http://" + mailpit.getHost() + ":" + mailpit.getMappedPort(8025) + path
    );
  }

  ResultActions postJson(String path, Object body, String access)
    throws Exception {
    var request = post(path)
      .header("Origin", "http://localhost:3000")
      .contentType("application/json")
      .content(json.writeValueAsBytes(body));
    if (access != null) request.header("Authorization", "Bearer " + access);
    return mvc.perform(request);
  }

  String owner(String email, String slug) throws Exception {
    var result = postJson(
      "/api/auth/register",
      Map.of(
        "fullName",
        "Owner " + slug,
        "email",
        email,
        "password",
        PASSWORD,
        "confirmPassword",
        PASSWORD,
        "companyName",
        slug + " company",
        "slug",
        slug
      ),
      null
    )
      .andExpect(status().isCreated())
      .andReturn();
    return access(result);
  }

  String access(MvcResult result) throws Exception {
    return json
      .readTree(result.getResponse().getContentAsString())
      .path("accessToken")
      .asText();
  }

  ResultActions inviteRequest(String email, String token) throws Exception {
    return postJson(
      "/api/workspaces/alpha/team/invitations",
      Map.of("email", email, "role", "SUPPORT_AGENT"),
      token
    );
  }

  String invitationToken(String email) throws Exception {
    String[] result = { null };
    await()
      .atMost(Duration.ofSeconds(8))
      .untilAsserted(() -> {
        JsonNode messages = json
          .readTree(
            http
              .send(
                HttpRequest.newBuilder(mailUri("/api/v1/messages"))
                  .GET()
                  .build(),
                HttpResponse.BodyHandlers.ofString()
              )
              .body()
          )
          .path("messages");
        for (JsonNode message : messages) {
          JsonNode full = json.readTree(
            http
              .send(
                HttpRequest.newBuilder(
                  mailUri("/api/v1/message/" + message.path("ID").asText())
                )
                  .GET()
                  .build(),
                HttpResponse.BodyHandlers.ofString()
              )
              .body()
          );
          if (full.path("To").toString().contains(email)) {
            var matcher = Pattern.compile("#token=([A-Za-z0-9_-]{43})").matcher(
              full.path("Text").asText()
            );
            if (matcher.find()) {
              result[0] = matcher.group(1);
              break;
            }
          }
        }
        assertThat(result[0])
          .as("Invitation delivered via SMTP to Mailpit")
          .isNotNull();
      });
    return result[0];
  }

  String invite(String email) throws Exception {
    inviteRequest(email, owner).andExpect(status().isCreated());
    return invitationToken(email);
  }

  UUID inviteId(String email) {
    return db.queryForObject(
      "SELECT id FROM invitations WHERE email=? ORDER BY created_at DESC LIMIT 1",
      UUID.class,
      email
    );
  }

  UUID account(String email) {
    return db.queryForObject(
      "SELECT id FROM accounts WHERE email=?",
      UUID.class,
      email
    );
  }

  String join(String token) throws Exception {
    return access(
      postJson(
        "/api/auth/invitations/register",
        Map.of(
          "token",
          token,
          "fullName",
          "Support Agent",
          "password",
          PASSWORD,
          "confirmPassword",
          PASSWORD
        ),
        null
      )
        .andExpect(status().isCreated())
        .andReturn()
    );
  }

  ResultActions preview(String token) throws Exception {
    return postJson("/api/invitations/preview", Map.of("token", token), null);
  }

  @Test
  void deliversEmailStoresOnlyHashAndListsSafeMetadata() throws Exception {
    String token = invite("agent@alpha.test");
    assertThat(
      db.queryForObject("SELECT token_hash FROM invitations", String.class)
    )
      .isEqualTo(IdentityService.hash(token))
      .isNotEqualTo(token);
    preview(token)
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.companyName").value("alpha company"))
      .andExpect(jsonPath("$.email").value("agent@alpha.test"))
      .andExpect(jsonPath("$.role").value("SUPPORT_AGENT"))
      .andExpect(jsonPath("$.accountExists").value(false));
    String list = mvc
      .perform(
        get("/api/workspaces/alpha/team/invitations").header(
          "Authorization",
          "Bearer " + owner
        )
      )
      .andExpect(status().isOk())
      .andExpect(jsonPath("$[0].status").value("PENDING"))
      .andReturn()
      .getResponse()
      .getContentAsString();
    assertThat(list).doesNotContain(token, "tokenHash", "token_hash", "token=");
    long lifetime = db.queryForObject(
      "SELECT extract(epoch from expires_at-created_at)::bigint FROM invitations",
      Long.class
    );
    assertThat(lifetime).isEqualTo(48 * 3600);
  }

  @Test
  void newAgentAcceptanceIsAtomicSingleUseAndDoesNotCreateCompany()
    throws Exception {
    String token = invite("agent@alpha.test");
    String agent = join(token);
    mvc
      .perform(
        get("/api/workspaces/alpha").header("Authorization", "Bearer " + agent)
      )
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.role").value("SUPPORT_AGENT"));
    assertThat(
      db.queryForObject("SELECT count(*) FROM companies", Integer.class)
    ).isEqualTo(1);
    assertThat(
      db.queryForObject("SELECT count(*) FROM memberships", Integer.class)
    ).isEqualTo(2);
    assertThat(
      db.queryForObject(
        "SELECT email_verified_at IS NOT NULL FROM accounts WHERE email='agent@alpha.test'",
        Boolean.class
      )
    ).isTrue();
    postJson(
      "/api/invitations/accept",
      Map.of("token", token),
      agent
    ).andExpect(status().isConflict());
    preview(token).andExpect(status().isConflict());
    postJson(
      "/api/auth/invitations/register",
      Map.of(
        "token",
        token,
        "fullName",
        "Duplicate",
        "password",
        PASSWORD,
        "confirmPassword",
        PASSWORD
      ),
      null
    ).andExpect(status().isConflict());
    assertThat(
      db.queryForObject("SELECT count(*) FROM accounts", Integer.class)
    ).isEqualTo(2);
  }

  @Test
  void existingUnverifiedAccountRequiresEmailLinkAndMatchingIdentity()
    throws Exception {
    String other = owner("existing@bravo.test", "bravo");
    String token = invite("existing@bravo.test");
    assertThat(
      db.queryForObject(
        "SELECT email_verified_at FROM accounts WHERE email='existing@bravo.test'",
        java.sql.Timestamp.class
      )
    ).isNull();
    postJson("/api/invitations/accept", Map.of("token", token), null).andExpect(
      status().isUnauthorized()
    );
    postJson(
      "/api/invitations/accept",
      Map.of("token", token),
      owner
    ).andExpect(status().isForbidden());
    String random = Base64.getUrlEncoder()
      .withoutPadding()
      .encodeToString(new byte[32]);
    postJson(
      "/api/invitations/accept",
      Map.of("token", random),
      other
    ).andExpect(status().isNotFound());
    postJson(
      "/api/auth/invitations/register",
      Map.of(
        "token",
        token,
        "fullName",
        "Replacement",
        "password",
        PASSWORD,
        "confirmPassword",
        PASSWORD
      ),
      null
    ).andExpect(status().isConflict());
    postJson(
      "/api/invitations/accept",
      Map.of("token", token),
      other
    ).andExpect(status().isNoContent());
    mvc
      .perform(get("/api/me").header("Authorization", "Bearer " + other))
      .andExpect(jsonPath("$.workspaces.length()").value(2));
    assertThat(
      db.queryForObject("SELECT count(*) FROM accounts", Integer.class)
    ).isEqualTo(2);
    assertThat(
      db.queryForObject("SELECT count(*) FROM companies", Integer.class)
    ).isEqualTo(2);
  }

  @Test
  void invalidInputDuplicateMembershipAndDuplicateInvitationAreRejected()
    throws Exception {
    inviteRequest("bad-email", owner).andExpect(status().isBadRequest());
    postJson(
      "/api/workspaces/alpha/team/invitations",
      Map.of("email", "new@alpha.test", "role", "COMPANY_ADMIN"),
      owner
    ).andExpect(status().isBadRequest());
    inviteRequest("owner@alpha.test", owner).andExpect(status().isConflict());
    String token = invite("agent@alpha.test");
    inviteRequest("AGENT@alpha.test", owner).andExpect(status().isConflict());
    join(token);
    inviteRequest("agent@alpha.test", owner).andExpect(status().isConflict());
  }

  @Test
  void expiryAndRevocationPreventBothRegistrationAndAcceptance()
    throws Exception {
    String expired = invite("expired@alpha.test");
    db.update("UPDATE invitations SET expires_at=now()-interval '1 minute'");
    preview(expired).andExpect(status().isGone());
    postJson(
      "/api/auth/invitations/register",
      Map.of(
        "token",
        expired,
        "fullName",
        "Expired",
        "password",
        PASSWORD,
        "confirmPassword",
        PASSWORD
      ),
      null
    ).andExpect(status().isGone());
    String revoked = invite("revoked@alpha.test");
    postJson(
      "/api/workspaces/alpha/team/invitations/" +
        inviteId("revoked@alpha.test") +
        "/revoke",
      Map.of(),
      owner
    ).andExpect(status().isNoContent());
    preview(revoked).andExpect(status().isGone());
    mvc
      .perform(
        get("/api/workspaces/alpha/team/invitations").header(
          "Authorization",
          "Bearer " + owner
        )
      )
      .andExpect(jsonPath("$[?(@.status=='EXPIRED')]").isNotEmpty())
      .andExpect(jsonPath("$[?(@.status=='REVOKED')]").isNotEmpty());
    assertThat(
      db.queryForObject("SELECT count(*) FROM accounts", Integer.class)
    ).isEqualTo(1);
  }

  @Test
  void resendRotatesTokenAndUsesWorkspaceRateLimit() throws Exception {
    String old = invite("agent@alpha.test");
    UUID id = inviteId("agent@alpha.test");
    postJson(
      "/api/workspaces/alpha/team/invitations/" + id + "/resend",
      Map.of(),
      owner
    ).andExpect(status().isNoContent());
    String next = invitationToken("agent@alpha.test");
    assertThat(next).isNotEqualTo(old);
    preview(old).andExpect(status().isNotFound());
    preview(next).andExpect(status().isOk());
    postJson(
      "/api/workspaces/alpha/team/invitations/" + id + "/resend",
      Map.of(),
      owner
    ).andExpect(status().isNoContent());
    postJson(
      "/api/workspaces/alpha/team/invitations/" + id + "/resend",
      Map.of(),
      owner
    ).andExpect(status().isTooManyRequests());
    inviteRequest("new@alpha.test", owner).andExpect(
      status().isTooManyRequests()
    );
  }

  @Test
  void agentsCannotReadOrMutateAdministrativeTeamOperations() throws Exception {
    String token = invite("agent@alpha.test"),
      agent = join(token);
    UUID member = account("agent@alpha.test"),
      id = inviteId("agent@alpha.test");
    for (String path : List.of("members", "invitations"))
      mvc
        .perform(
          get("/api/workspaces/alpha/team/" + path).header(
            "Authorization",
            "Bearer " + agent
          )
        )
        .andExpect(status().isForbidden());
    inviteRequest("new@alpha.test", agent).andExpect(status().isForbidden());
    for (String action : List.of("resend", "revoke"))
      postJson(
        "/api/workspaces/alpha/team/invitations/" + id + "/" + action,
        Map.of(),
        agent
      ).andExpect(status().isForbidden());
    postJson(
      "/api/workspaces/alpha/team/members/" + member + "/status",
      Map.of("status", "INACTIVE"),
      agent
    ).andExpect(status().isForbidden());
  }

  @Test
  void tenantIsolationCoversEveryTeamAndInvitationOperation() throws Exception {
    String other = owner("other@bravo.test", "bravo"),
      token = invite("agent@alpha.test");
    UUID id = inviteId("agent@alpha.test");
    for (String path : List.of("members", "invitations"))
      mvc
        .perform(
          get("/api/workspaces/alpha/team/" + path).header(
            "Authorization",
            "Bearer " + other
          )
        )
        .andExpect(status().isNotFound());
    inviteRequest("new@alpha.test", other).andExpect(status().isNotFound());
    for (String action : List.of("resend", "revoke"))
      postJson(
        "/api/workspaces/bravo/team/invitations/" + id + "/" + action,
        Map.of(),
        other
      ).andExpect(status().isNotFound());
    postJson(
      "/api/workspaces/bravo/team/members/" +
        account("owner@alpha.test") +
        "/status",
      Map.of("status", "INACTIVE"),
      other
    ).andExpect(status().isNotFound());
    preview(token).andExpect(status().isOk());
  }

  @Test
  void deactivationIsImmediateScopedAndReversibleWhileAdminsAreProtected()
    throws Exception {
    String other = owner("existing@bravo.test", "bravo"),
      token = invite("existing@bravo.test");
    postJson(
      "/api/invitations/accept",
      Map.of("token", token),
      other
    ).andExpect(status().isNoContent());
    UUID agent = account("existing@bravo.test");
    String path = "/api/workspaces/alpha/team/members/" + agent + "/status";
    postJson(path, Map.of("status", "INACTIVE"), owner).andExpect(
      status().isNoContent()
    );
    mvc
      .perform(
        get("/api/workspaces/alpha").header("Authorization", "Bearer " + other)
      )
      .andExpect(status().isNotFound());
    mvc
      .perform(
        get("/api/workspaces/bravo").header("Authorization", "Bearer " + other)
      )
      .andExpect(status().isOk());
    mvc
      .perform(get("/api/me").header("Authorization", "Bearer " + other))
      .andExpect(jsonPath("$.workspaces.length()").value(1));
    inviteRequest("existing@bravo.test", owner).andExpect(
      status().isConflict()
    );
    postJson(path, Map.of("status", "ACTIVE"), owner).andExpect(
      status().isNoContent()
    );
    mvc
      .perform(
        get("/api/workspaces/alpha").header("Authorization", "Bearer " + other)
      )
      .andExpect(status().isOk());
    postJson(
      "/api/workspaces/alpha/team/members/" +
        account("owner@alpha.test") +
        "/status",
      Map.of("status", "INACTIVE"),
      owner
    ).andExpect(status().isBadRequest());
    assertThat(
      db.queryForObject(
        "SELECT count(*) FROM memberships WHERE role='COMPANY_ADMIN' AND status='ACTIVE'",
        Integer.class
      )
    ).isEqualTo(2);
  }

  @Test
  void searchesAndFiltersDoNotLeakOtherCompanyMembers() throws Exception {
    join(invite("agent@alpha.test"));
    owner("hidden@bravo.test", "bravo");
    mvc
      .perform(
        get("/api/workspaces/alpha/team/members")
          .param("search", "SUPPORT")
          .param("role", "SUPPORT_AGENT")
          .param("status", "ACTIVE")
          .header("Authorization", "Bearer " + owner)
      )
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.length()").value(1))
      .andExpect(jsonPath("$[0].email").value("agent@alpha.test"));
    mvc
      .perform(
        get("/api/workspaces/alpha/team/members")
          .param("search", "hidden")
          .header("Authorization", "Bearer " + owner)
      )
      .andExpect(jsonPath("$.length()").value(0));
    mvc
      .perform(
        get("/api/workspaces/alpha/team/members")
          .param("role", "PLATFORM_ADMIN")
          .header("Authorization", "Bearer " + owner)
      )
      .andExpect(status().isBadRequest());
  }

  @Test
  void failedRegistrationDoesNotConsumeEmailProof() throws Exception {
    String token = invite("agent@alpha.test");
    postJson(
      "/api/auth/invitations/register",
      Map.of(
        "token",
        token,
        "fullName",
        "Agent",
        "password",
        PASSWORD,
        "confirmPassword",
        "different-password"
      ),
      null
    ).andExpect(status().isBadRequest());
    assertThat(
      db.queryForObject("SELECT count(*) FROM accounts", Integer.class)
    ).isEqualTo(1);
    preview(token).andExpect(status().isOk());
    join(token);
  }

  @Test
  void concurrentAcceptsCreateOnlyOneAccountAndMembership() throws Exception {
    String token = invite("agent@alpha.test");
    try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var start = new java.util.concurrent.CountDownLatch(1);
      java.util.concurrent.Callable<Integer> accept = () -> {
        start.await();
        return postJson(
          "/api/auth/invitations/register",
          Map.of(
            "token",
            token,
            "fullName",
            "Agent",
            "password",
            PASSWORD,
            "confirmPassword",
            PASSWORD
          ),
          null
        )
          .andReturn()
          .getResponse()
          .getStatus();
      };
      var one = pool.submit(accept);
      var two = pool.submit(accept);
      start.countDown();
      assertThat(List.of(one.get(), two.get())).containsExactlyInAnyOrder(
        201,
        409
      );
    }
    assertThat(
      db.queryForObject("SELECT count(*) FROM accounts", Integer.class)
    ).isEqualTo(2);
    assertThat(
      db.queryForObject("SELECT count(*) FROM memberships", Integer.class)
    ).isEqualTo(2);
  }

  @Test
  void smtpFailureRollsBackCreationAndPreservesPreviousResendLink()
    throws Exception {
    String old = invite("agent@alpha.test");
    org.mockito.Mockito.doThrow(
      new org.springframework.mail.MailSendException("SMTP unavailable")
    )
      .when(smtp)
      .send(
        org.mockito.ArgumentMatchers.any(
          org.springframework.mail.SimpleMailMessage.class
        )
      );
    postJson(
      "/api/workspaces/alpha/team/invitations/" +
        inviteId("agent@alpha.test") +
        "/resend",
      Map.of(),
      owner
    ).andExpect(status().isServiceUnavailable());
    preview(old).andExpect(status().isOk());
    inviteRequest("new@alpha.test", owner).andExpect(
      status().isServiceUnavailable()
    );
    assertThat(
      db.queryForObject("SELECT count(*) FROM invitations", Integer.class)
    ).isEqualTo(1);
    assertThat(
      db.queryForObject(
        "SELECT count(*) FROM invitation_deliveries",
        Integer.class
      )
    ).isEqualTo(1);
  }
}
