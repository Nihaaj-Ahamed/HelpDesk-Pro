package com.helpdesk;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.*;
import com.helpdesk.identity.IdentityService;
import java.net.URI;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ReportingPlatformTest {

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
    r.add(
      "app.jwt-secret",
      () -> "test-only-secret-with-at-least-thirty-two-bytes"
    );
    r.add("app.auth-rate-limit", () -> 1000);
  }

  @Autowired
  MockMvc mvc;

  @Autowired
  ObjectMapper json;

  @Autowired
  JdbcTemplate db;

  @Autowired
  IdentityService identity;

  @LocalServerPort
  int port;

  UUID company, foreign, adminId, agentId, otherAgentId, customerId, otherCustomerId, ticket;
  String admin, agent, otherAgent, customer, otherCustomer, foreignAdmin;

  @BeforeEach
  void setup() throws Exception {
    db.execute("TRUNCATE companies,accounts CASCADE");
    company = company("alpha");
    foreign = company("bravo");
    adminId = person("Admin", company, "COMPANY_ADMIN");
    agentId = person("Agent", company, "SUPPORT_AGENT");
    otherAgentId = person("Other Agent", company, "SUPPORT_AGENT");
    customerId = person("Customer", company, "CUSTOMER");
    otherCustomerId = person("Other Customer", company, "CUSTOMER");
    admin = identity.startSession(adminId).accessToken();
    agent = identity.startSession(agentId).accessToken();
    otherAgent = identity.startSession(otherAgentId).accessToken();
    customer = identity.startSession(customerId).accessToken();
    otherCustomer = identity.startSession(otherCustomerId).accessToken();
    foreignAdmin = identity
      .startSession(person("Foreign", foreign, "COMPANY_ADMIN"))
      .accessToken();
    ticket = UUID.fromString(
      body(
        postJson(
          "/api/support/alpha/tickets",
          Map.of(
            "subject",
            "Conversation request",
            "description",
            "Private support context"
          ),
          customer
        )
          .andExpect(status().isCreated())
          .andReturn()
      )
        .path("id")
        .asText()
    );
  }

  UUID company(String slug) {
    UUID id = UUID.randomUUID();
    db.update(
      "INSERT INTO companies(id,name,slug) VALUES (?,?,?)",
      id,
      slug,
      slug
    );
    db.update(
      "INSERT INTO ticket_categories(id,company_id,name) VALUES (?,?,'General')",
      UUID.randomUUID(),
      id
    );
    return id;
  }

  UUID person(String name, UUID company, String role) {
    UUID id = UUID.randomUUID();
    db.update(
      "INSERT INTO accounts(id,full_name,email,password_hash,email_verified_at) VALUES (?,?,?,?,now())",
      id,
      name,
      id + "@example.test",
      "unused-fixture-hash"
    );
    db.update(
      "INSERT INTO memberships(account_id,company_id,role) VALUES (?,?,?)",
      id,
      company,
      role
    );
    return id;
  }

  ResultActions postJson(String path, Object value, String token)
    throws Exception {
    var request = post(path)
      .header("Origin", "http://localhost:3000")
      .contentType("application/json")
      .content(json.writeValueAsBytes(value));
    if (token != null) request.header("Authorization", "Bearer " + token);
    return mvc.perform(request);
  }

  ResultActions getJson(String path, String token) throws Exception {
    return mvc.perform(get(path).header("Authorization", "Bearer " + token));
  }

  JsonNode body(MvcResult result) throws Exception {
    return json.readTree(result.getResponse().getContentAsString());
  }

  String path(boolean staff) {
    return (
      "/api/" +
      (staff ? "workspaces" : "support") +
      "/alpha/tickets/" +
      ticket +
      "/conversation"
    );
  }

  Map<String, Object> message(UUID key, String content, String visibility) {
    return Map.of(
      "clientId",
      key,
      "content",
      content,
      "visibility",
      visibility
    );
  }

  JsonNode send(String token, boolean staff, String content, String visibility)
    throws Exception {
    return body(
      postJson(
        path(staff),
        message(UUID.randomUUID(), content, visibility),
        token
      )
        .andExpect(status().isCreated())
        .andReturn()
    );
  }

  void assign() {
    db.update(
      "UPDATE tickets SET assigned_agent_id=?,status='ASSIGNED' WHERE company_id=? AND id=?",
      agentId,
      company,
      ticket
    );
  }

  long unread(String token, boolean staff) throws Exception {
    return body(
      getJson(
        "/api/" +
          (staff ? "workspaces" : "support") +
          "/alpha/tickets/" +
          ticket,
        token
      )
        .andExpect(status().isOk())
        .andReturn()
    )
      .path("unreadCount")
      .asLong();
  }

  @Autowired
  com.helpdesk.sla.SlaService sla;

  @Autowired
  com.helpdesk.notifications.EmailOutbox outbox;

  String analytics() {
    return "/api/workspaces/alpha/analytics";
  }

  String export() {
    return "/api/workspaces/alpha/reports/export";
  }

  String platform() {
    UUID id = person("Platform operator", foreign, "COMPANY_ADMIN");
    db.update("INSERT INTO platform_administrators(account_id) VALUES (?)", id);
    return identity.startSession(id).accessToken();
  }

  @Test
  void analyticsAggregatesOnlyTheAuthorizedCompanyAndValidatesFilters()
    throws Exception {
    UUID betaCustomer = person("Beta customer", foreign, "CUSTOMER");
    String beta = identity.startSession(betaCustomer).accessToken();
    postJson(
      "/api/support/bravo/tickets",
      Map.of(
        "subject",
        "Foreign confidential subject",
        "description",
        "Foreign secret description"
      ),
      beta
    ).andExpect(status().isCreated());
    getJson(analytics(), admin)
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.counts.total").value(1))
      .andExpect(jsonPath("$.counts.open").value(1))
      .andExpect(jsonPath("$.counts.unassigned").value(1));
    assertThat(
      body(getJson(analytics(), admin).andReturn()).toString()
    ).doesNotContain("Foreign confidential", "Foreign secret", "Beta customer");
    getJson(analytics() + "?status=CLOSED", admin).andExpect(
      jsonPath("$.counts.total").value(0)
    );
    getJson(analytics() + "?from=2030-01-01&to=2030-01-02", admin).andExpect(
      jsonPath("$.counts.total").value(0)
    );
    getJson(analytics() + "?from=2026-12-31&to=2026-01-01", admin).andExpect(
      status().isBadRequest()
    );
    getJson(analytics() + "?from=2020-01-01&to=2026-12-31", admin).andExpect(
      status().isBadRequest()
    );
    getJson(
      analytics() +
        "?category=" +
        db.queryForObject(
          "SELECT id FROM ticket_categories WHERE company_id=?",
          UUID.class,
          foreign
        ),
      admin
    ).andExpect(status().isBadRequest());
    getJson(analytics(), agent).andExpect(status().isForbidden());
    getJson(analytics(), customer).andExpect(status().isForbidden());
    getJson(analytics(), foreignAdmin).andExpect(status().isNotFound());
  }

  @Test
  void performanceUsesActualFirstResponderAndResolverNotCurrentAssignee()
    throws Exception {
    assign();
    var started = db
      .queryForObject(
        "SELECT created_at FROM tickets WHERE id=?",
        java.sql.Timestamp.class,
        ticket
      )
      .toInstant();
    db.update(
      "UPDATE ticket_sla SET first_response_at=?,first_responder_id=?,resolved_at=?,resolver_id=? WHERE ticket_id=?",
      java.sql.Timestamp.from(started.plusSeconds(60)),
      adminId,
      java.sql.Timestamp.from(started.plusSeconds(120)),
      agentId,
      ticket
    );
    db.update("UPDATE tickets SET status='RESOLVED' WHERE id=?", ticket);
    var report = body(
      getJson(analytics(), admin).andExpect(status().isOk()).andReturn()
    );
    assertThat(
      report.path("counts").path("average_response_minutes").asDouble()
    ).isCloseTo(1.0, within(0.001));
    assertThat(
      report.path("counts").path("average_resolution_minutes").asDouble()
    ).isCloseTo(2.0, within(0.001));
    assertThat(report.path("counts").path("met_targets").asInt()).isEqualTo(2);
    var personal = body(
      getJson("/api/workspaces/alpha/my-performance", agent)
        .andExpect(status().isOk())
        .andReturn()
    );
    assertThat(personal.path("agents").size()).isEqualTo(1);
    assertThat(
      personal.path("agents").get(0).path("average_response_minutes").isNull()
    ).isTrue();
    assertThat(
      personal.path("agents").get(0).path("resolved").asInt()
    ).isEqualTo(1);
    assertThat(personal.toString()).doesNotContain("Other Agent");
    getJson(
      "/api/workspaces/alpha/my-performance?agent=" + otherAgentId,
      agent
    ).andExpect(status().isForbidden());
  }

  @Test
  void csvIsFormulaSafeBoundedAndTenantAuthorized() throws Exception {
    db.update(
      "UPDATE tickets SET subject=? WHERE id=?",
      "  =HYPERLINK(bad)",
      ticket
    );
    String csv = getJson(export(), admin)
      .andExpect(status().isOk())
      .andExpect(content().contentTypeCompatibleWith("text/csv"))
      .andReturn()
      .getResponse()
      .getContentAsString();
    assertThat(csv)
      .contains("'  =HYPERLINK")
      .doesNotContain("Private support context");
    assertThat(
      com.helpdesk.reporting.ReportingService.csv("\t@SUM(1)")
    ).contains("'\t@");
    getJson(export(), customer).andExpect(status().isForbidden());
    getJson(export(), agent).andExpect(status().isForbidden());
    getJson(export(), foreignAdmin).andExpect(status().isNotFound());
    getJson(export() + "?report=STATUS", admin).andExpect(status().isOk());
    getJson(export() + "?from=2030-01-01&to=2030-01-02", admin).andExpect(
      content().string(
        org.hamcrest.Matchers.containsString("No matching records")
      )
    );
    db.update(
      "INSERT INTO tickets(id,company_id,subject) SELECT gen_random_uuid(),?,'Bounded export fixture' FROM generate_series(1,10001)",
      company
    );
    getJson(export(), admin).andExpect(status().isPayloadTooLarge());
  }

  @Test
  void platformAuthorityCannotBeSelfGrantedAndDoesNotGrantPrivateTenantAccess()
    throws Exception {
    for (String token : List.of(admin, agent, customer))
      getJson("/api/platform/overview", token).andExpect(
        status().isForbidden()
      );
    var registration = new HashMap<String, Object>(
      Map.of(
        "fullName",
        "Cannot self grant",
        "email",
        "self@example.test",
        "password",
        "a-secure-password-123",
        "confirmPassword",
        "a-secure-password-123",
        "companyName",
        "Test",
        "slug",
        "self-escalation"
      )
    );
    registration.put("platformAdmin", true);
    registration.put("role", "PLATFORM_ADMIN");
    String ordinary = body(
      postJson("/api/auth/register", registration, null)
        .andExpect(status().isCreated())
        .andReturn()
    )
      .path("accessToken")
      .asText();
    getJson("/api/me", ordinary).andExpect(
      jsonPath("$.platformAdmin").value(false)
    );
    getJson("/api/platform/overview", ordinary).andExpect(
      status().isForbidden()
    );
    String operator = platform();
    var overview = body(
      getJson("/api/platform/overview", operator)
        .andExpect(status().isOk())
        .andReturn()
    );
    assertThat(overview.toString()).doesNotContain(
      "Private support context",
      "Conversation request",
      "unused-fixture-hash"
    );
    getJson("/api/platform/companies?search=alpha", operator).andExpect(
      jsonPath("$.total").value(1)
    );
    getJson("/api/platform/companies/" + company, operator).andExpect(
      status().isOk()
    );
    getJson("/api/workspaces/alpha/tickets/" + ticket, operator).andExpect(
      status().isNotFound()
    );
  }

  @Test
  void suspensionBlocksExistingSessionsSocketsNotificationsAndJobsThenReactivationRestoresAccess()
    throws Exception {
    String operator = platform();
    Listener listener = new Listener();
    var socket = authenticated(listener, customer, false);
    postJson(
      "/api/platform/companies/" + company + "/status",
      Map.of(
        "status",
        "SUSPENDED",
        "reason",
        "Review requested by operator",
        "version",
        0
      ),
      operator
    ).andExpect(status().isOk());
    assertThat(listener.closed.get(6, TimeUnit.SECONDS)).isEqualTo(1008);
    socket.abort();
    for (String endpoint : List.of(
      "/api/workspaces/alpha",
      "/api/workspaces/alpha/tickets/" + ticket,
      "/api/workspaces/alpha/team/members",
      analytics(),
      export(),
      "/api/companies/alpha/notifications"
    ))
      getJson(endpoint, admin).andExpect(status().isLocked());
    getJson(path(false), customer).andExpect(status().isLocked());
    postJson(
      path(false),
      message(UUID.randomUUID(), "Suspended write", "PUBLIC"),
      customer
    ).andExpect(status().isLocked());
    postJson(path(false) + "/live", Map.of(), customer).andExpect(
      status().isLocked()
    );
    getJson("/api/workspaces/bravo", operator).andExpect(status().isOk());
    db.update(
      "UPDATE ticket_sla SET response_due=now()-interval '1 hour' WHERE ticket_id=?",
      ticket
    );
    sla.evaluate(company, java.time.Instant.now());
    assertThat(
      db.queryForObject("SELECT count(*) FROM sla_events", Long.class)
    ).isZero();
    assertThat(outbox.deliverOne()).isFalse();
    postJson(
      "/api/platform/companies/" + company + "/status",
      Map.of(
        "status",
        "ACTIVE",
        "reason",
        "Review complete access restored",
        "version",
        1
      ),
      operator
    ).andExpect(status().isOk());
    getJson(path(false), customer).andExpect(status().isOk());
    getJson("/api/workspaces/alpha", admin).andExpect(status().isOk());
    getJson("/api/companies/alpha/notifications", customer).andExpect(
      status().isOk()
    );
    assertThat(
      db.queryForObject(
        "SELECT count(*) FROM tickets WHERE company_id=?",
        Long.class,
        company
      )
    ).isEqualTo(1);
    assertThat(
      db.queryForObject(
        "SELECT count(*) FROM platform_audit WHERE company_id=?",
        Long.class,
        company
      )
    ).isEqualTo(2);
    assertThatThrownBy(() ->
      db.update("DELETE FROM platform_audit")
    ).hasMessageContaining("immutable");
  }

  static class Listener implements WebSocket.Listener {

    final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
    final CompletableFuture<Integer> closed = new CompletableFuture<>();
    final StringBuilder buffer = new StringBuilder();

    public void onOpen(WebSocket s) {
      s.request(1);
    }

    public CompletionStage<?> onText(
      WebSocket s,
      CharSequence text,
      boolean last
    ) {
      buffer.append(text);
      if (last) {
        messages.add(buffer.toString());
        buffer.setLength(0);
      }
      s.request(1);
      return null;
    }

    public CompletionStage<?> onClose(WebSocket s, int code, String reason) {
      closed.complete(code);
      return null;
    }

    public void onError(WebSocket s, Throwable ex) {
      closed.completeExceptionally(ex);
    }
  }

  WebSocket open(Listener listener, String origin) throws Exception {
    return HttpClient.newHttpClient()
      .newWebSocketBuilder()
      .header("Origin", origin)
      .buildAsync(
        URI.create("ws://localhost:" + port + "/ws/conversations"),
        listener
      )
      .get(5, TimeUnit.SECONDS);
  }

  String credential(String token, boolean staff) throws Exception {
    return body(
      postJson(path(staff) + "/live", Map.of(), token)
        .andExpect(status().isOk())
        .andReturn()
    )
      .path("credential")
      .asText();
  }

  WebSocket authenticated(Listener listener, String token, boolean staff)
    throws Exception {
    String secret = credential(token, staff);
    var socket = open(listener, "http://localhost:3000");
    socket
      .sendText(json.writeValueAsString(Map.of("credential", secret)), true)
      .join();
    assertThat(listener.messages.poll(5, TimeUnit.SECONDS)).contains("ready");
    return socket;
  }
}
