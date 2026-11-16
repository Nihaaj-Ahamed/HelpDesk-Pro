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
class NotificationSlaTest {

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

  @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
  org.springframework.mail.javamail.JavaMailSender mail;

  String notices() {
    return "/api/companies/alpha/notifications";
  }

  @Test
  void notificationsAreRecipientSpecificAndPrivateNotesStayPrivate()
    throws Exception {
    getJson(notices(), customer).andExpect(jsonPath("$.total").value(1));
    getJson(notices(), agent).andExpect(jsonPath("$.total").value(0));
    getJson(notices(), foreignAdmin).andExpect(status().isNotFound());
    var first = body(getJson(notices(), customer).andReturn())
      .path("items")
      .get(0)
      .path("id")
      .asText();
    postJson(notices() + "/" + first + "/read", Map.of(), agent).andExpect(
      status().isNotFound()
    );
    assign();
    long before = db.queryForObject(
      "SELECT count(*) FROM notifications",
      Long.class
    );
    postJson(
      path(true),
      message(UUID.randomUUID(), "PRIVATE NOTE MUST NEVER NOTIFY", "INTERNAL"),
      agent
    ).andExpect(status().isCreated());
    assertThat(
      db.queryForObject("SELECT count(*) FROM notifications", Long.class)
    ).isEqualTo(before);
    postJson(
      path(true),
      message(UUID.randomUUID(), "Public answer", "PUBLIC"),
      agent
    ).andExpect(status().isCreated());
    getJson(notices() + "?type=REPLY", customer).andExpect(
      jsonPath("$.total").value(1)
    );
    assertThat(
      body(getJson(notices(), customer).andReturn()).toString()
    ).doesNotContain("PRIVATE NOTE", "Public answer");
    postJson(notices() + "/read-all", Map.of(), customer).andExpect(
      status().isOk()
    );
    getJson(notices() + "?unread=true", customer).andExpect(
      jsonPath("$.total").value(0)
    );
    getJson(notices(), admin).andExpect(jsonPath("$.unread").value(2));
  }

  @Test
  void durableEmailFailuresDoNotRollBackTicketsAndPreferencesApply()
    throws Exception {
    org.mockito.Mockito.doThrow(
      new org.springframework.mail.MailSendException("test-only failure")
    )
      .when(mail)
      .send(
        org.mockito.ArgumentMatchers.any(
          jakarta.mail.internet.MimeMessage.class
        )
      );
    postJson(
      notices() + "/preferences",
      Map.of("emailEnabled", false, "categories", List.of()),
      customer
    ).andExpect(status().isOk());
    while (outbox.deliverOne()) {}
    assertThat(
      db.queryForObject(
        "SELECT count(*) FROM email_outbox WHERE state='SUPPRESSED'",
        Long.class
      )
    ).isEqualTo(1);
    assertThat(
      db.queryForObject(
        "SELECT count(*) FROM email_outbox WHERE state='FAILED' AND attempts=1",
        Long.class
      )
    ).isEqualTo(1);
    postJson(
      path(false),
      message(
        UUID.randomUUID(),
        "Still persisted despite SMTP outage",
        "PUBLIC"
      ),
      customer
    ).andExpect(status().isCreated());
    assertThat(
      db.queryForObject("SELECT count(*) FROM ticket_messages", Long.class)
    ).isEqualTo(1);
    org.mockito.Mockito.doNothing()
      .when(mail)
      .send(
        org.mockito.ArgumentMatchers.any(
          jakarta.mail.internet.MimeMessage.class
        )
      );
    db.update(
      "UPDATE email_outbox SET next_attempt_at=now() WHERE state='FAILED'"
    );
    while (outbox.deliverOne()) {}
    assertThat(
      db.queryForObject(
        "SELECT count(*) FROM email_outbox WHERE state='SENT'",
        Long.class
      )
    ).isEqualTo(2);
    assertThat(outbox.deliverOne()).isFalse();
  }

  @Test
  void firstStaffPublicReplyAndFirstResolutionFulfillImmutableSnapshot()
    throws Exception {
    var before = db.queryForMap(
      "SELECT * FROM ticket_sla WHERE ticket_id=?",
      ticket
    );
    assertThat(before.get("response_minutes")).isEqualTo(480);
    assertThat(
      java.time.Duration.between(
        ((java.sql.Timestamp) before.get("started_at")).toInstant(),
        ((java.sql.Timestamp) before.get("response_due")).toInstant()
      ).toMinutes()
    ).isEqualTo(480);
    postJson(
      path(false),
      message(UUID.randomUUID(), "Customer reply", "PUBLIC"),
      customer
    ).andExpect(status().isCreated());
    postJson(
      path(true),
      message(UUID.randomUUID(), "Private admin note", "INTERNAL"),
      admin
    ).andExpect(status().isCreated());
    assertThat(
      db
        .queryForMap("SELECT * FROM ticket_sla WHERE ticket_id=?", ticket)
        .get("first_response_at")
    ).isNull();
    postJson(
      path(true),
      message(UUID.randomUUID(), "Qualifying response", "PUBLIC"),
      admin
    ).andExpect(status().isCreated());
    assertThat(
      db.queryForObject(
        "SELECT first_responder_id FROM ticket_sla WHERE ticket_id=?",
        UUID.class,
        ticket
      )
    ).isEqualTo(adminId);
    postJson(
      "/api/workspaces/alpha/sla/policies",
      Map.of(
        "priority",
        "MEDIUM",
        "responseMinutes",
        2,
        "resolutionMinutes",
        5,
        "warningPercent",
        20,
        "enabled",
        true,
        "version",
        0
      ),
      admin
    ).andExpect(status().isOk());
    assign();
    postJson(
      "/api/workspaces/alpha/tickets/" + ticket + "/actions",
      Map.of("action", "PRIORITY", "value", "CRITICAL", "version", 0),
      admin
    ).andExpect(status().isOk());
    postJson(
      "/api/workspaces/alpha/tickets/" + ticket + "/actions",
      Map.of("action", "STATUS", "value", "RESOLVED", "version", 1),
      admin
    ).andExpect(status().isOk());
    var resolved = db.queryForMap(
      "SELECT * FROM ticket_sla WHERE ticket_id=?",
      ticket
    );
    assertThat(resolved.get("resolved_at")).isNotNull();
    assertThat(resolved.get("response_due")).isEqualTo(
      before.get("response_due")
    );
    postJson(
      "/api/support/alpha/tickets/" + ticket + "/actions",
      Map.of("action", "REOPEN", "version", 2),
      customer
    ).andExpect(status().isOk());
    assertThat(
      db
        .queryForMap("SELECT * FROM ticket_sla WHERE ticket_id=?", ticket)
        .get("resolved_at")
    ).isEqualTo(resolved.get("resolved_at"));
  }

  @Test
  void warningAndEscalationAreRetrySafeAndDoNotReassignOrChangeStatus()
    throws Exception {
    assign();
    var now = java.time.Instant.now();
    db.update(
      "UPDATE ticket_sla SET response_minutes=10,warning_percent=20,response_due=?,resolution_due=? WHERE ticket_id=?",
      java.sql.Timestamp.from(now.plusSeconds(60)),
      java.sql.Timestamp.from(now.plusSeconds(180000)),
      ticket
    );
    sla.evaluate(company, now);
    sla.evaluate(company, now);
    assertThat(
      db.queryForObject(
        "SELECT count(*) FROM sla_events WHERE kind='WARNING'",
        Long.class
      )
    ).isEqualTo(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var a = pool.submit(() -> sla.evaluate(company, now.plusSeconds(120)));
      var b = pool.submit(() -> sla.evaluate(company, now.plusSeconds(120)));
      a.get();
      b.get();
    }
    assertThat(
      db.queryForObject(
        "SELECT count(*) FROM sla_events WHERE kind='BREACH'",
        Long.class
      )
    ).isEqualTo(1);
    assertThat(
      db.queryForObject(
        "SELECT count(*) FROM ticket_activity WHERE kind='SLA_ESCALATED'",
        Long.class
      )
    ).isEqualTo(1);
    assertThat(
      db.queryForObject(
        "SELECT status FROM tickets WHERE id=?",
        String.class,
        ticket
      )
    ).isEqualTo("ASSIGNED");
    assertThat(
      db.queryForObject(
        "SELECT assigned_agent_id FROM tickets WHERE id=?",
        UUID.class,
        ticket
      )
    ).isEqualTo(agentId);
    getJson(
      "/api/support/alpha/tickets/" + ticket + "/sla",
      customer
    ).andExpect(jsonPath("$.events.length()").value(0));
  }

  @Test
  void policiesAndEmailFailureAdministrationRequireCompanyAdmin()
    throws Exception {
    getJson("/api/workspaces/alpha/sla/policies", admin).andExpect(
      jsonPath("$.length()").value(4)
    );
    for (String token : List.of(agent, customer))
      getJson("/api/workspaces/alpha/sla/policies", token).andExpect(
        status().isForbidden()
      );
    getJson("/api/workspaces/alpha/sla/policies", foreignAdmin).andExpect(
      status().isNotFound()
    );
    getJson(notices() + "/deliveries", agent).andExpect(status().isForbidden());
    postJson(
      "/api/workspaces/alpha/sla/policies",
      Map.of(
        "priority",
        "MEDIUM",
        "responseMinutes",
        1,
        "resolutionMinutes",
        10,
        "warningPercent",
        20,
        "enabled",
        false,
        "version",
        99
      ),
      admin
    ).andExpect(status().isConflict());
  }
}
