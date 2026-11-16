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
class ConversationTest {

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

  @Test
  void visibilityPublicCursorsAndReadMarkersNeverExposeNotes()
    throws Exception {
    send(customer, false, "Customer public reply", "PUBLIC");
    assertThat(unread(customer, false)).isZero();
    Object updated = db.queryForObject(
      "SELECT updated_at FROM tickets WHERE id=?",
      Object.class,
      ticket
    );
    send(admin, true, "PRIVATE STAFF CONTEXT", "INTERNAL");
    assertThat(unread(customer, false)).isZero();
    assertThat(
      db.queryForObject(
        "SELECT updated_at FROM tickets WHERE id=?",
        Object.class,
        ticket
      )
    ).isEqualTo(updated);
    var publicReply = send(admin, true, "Public answer", "PUBLIC");
    var history = body(
      getJson(path(false), customer).andExpect(status().isOk()).andReturn()
    );
    assertThat(history.toString()).doesNotContain(
      "PRIVATE STAFF CONTEXT",
      "INTERNAL"
    );
    assertThat(
      history.path("items").get(0).path("position").asLong()
    ).isEqualTo(1);
    assertThat(
      history.path("items").get(1).path("position").asLong()
    ).isEqualTo(2);
    assertThat(
      body(getJson(path(true), agent).andReturn())
        .path("items")
        .size()
    ).isEqualTo(3);
    assertThat(unread(customer, false)).isEqualTo(1);
    assertThat(unread(agent, true)).isEqualTo(3);
    assertThat(unread(admin, true)).isEqualTo(1);
    postJson(
      path(false) + "/read",
      Map.of("position", 999999),
      customer
    ).andExpect(status().isBadRequest());
    postJson(path(false) + "/read", Map.of("position", 2), customer).andExpect(
      status().isNoContent()
    );
    postJson(path(false) + "/read", Map.of("position", 1), customer).andExpect(
      status().isNoContent()
    );
    assertThat(unread(customer, false)).isZero();
    getJson("/api/support/alpha/dashboard", customer).andExpect(
      jsonPath("$.counts.unread").value(0)
    );
    getJson("/api/workspaces/alpha", agent).andExpect(
      jsonPath("$.unreadMessages").value(3)
    );
    assertThatThrownBy(() ->
      db.update(
        "UPDATE ticket_messages SET visibility='PUBLIC' WHERE visibility='INTERNAL'"
      )
    ).hasMessageContaining("immutable");
  }

  @Test
  void messageAuthorizationUsesCurrentRoleAssignmentAndTenant()
    throws Exception {
    postJson(
      path(true),
      message(UUID.randomUUID(), "No assignment", "PUBLIC"),
      agent
    ).andExpect(status().isForbidden());
    assign();
    send(agent, true, "Assigned answer", "PUBLIC");
    send(agent, true, "Assigned private note", "INTERNAL");
    postJson(
      path(true),
      message(UUID.randomUUID(), "Other agent reply", "PUBLIC"),
      otherAgent
    ).andExpect(status().isForbidden());
    getJson(path(true), otherAgent).andExpect(status().isOk());
    postJson(
      path(false),
      message(UUID.randomUUID(), "Forbidden note", "INTERNAL"),
      customer
    ).andExpect(status().isForbidden());
    getJson(path(true), customer).andExpect(status().isForbidden());
    getJson(path(false), otherCustomer).andExpect(status().isNotFound());
    for (String suffix : List.of("", "/live", "/read")) {
      postJson(
        path(true) + suffix,
        suffix.equals("/read")
          ? Map.of("position", 0)
          : message(UUID.randomUUID(), "foreign", "PUBLIC"),
        foreignAdmin
      ).andExpect(status().isNotFound());
    }
    getJson(path(true), foreignAdmin).andExpect(status().isNotFound());
    UUID foreignSender = db.queryForObject(
      "SELECT id FROM accounts WHERE full_name='Foreign'",
      UUID.class
    );
    assertThatThrownBy(() ->
      db.update(
        "INSERT INTO ticket_messages(id,company_id,ticket_id,sender_id,sender_name,sender_role,visibility,content,client_id,public_position) VALUES (?,?,?,?,'Foreign','COMPANY_ADMIN','PUBLIC','forbidden',?,200)",
        UUID.randomUUID(),
        company,
        ticket,
        foreignSender,
        UUID.randomUUID()
      )
    ).isInstanceOf(
      org.springframework.dao.DataIntegrityViolationException.class
    );

    db.update(
      "UPDATE memberships SET status='INACTIVE' WHERE company_id=? AND account_id=?",
      company,
      agentId
    );
    getJson(path(true), agent).andExpect(status().isNotFound());
    postJson(
      path(true),
      message(UUID.randomUUID(), "Inactive", "PUBLIC"),
      agent
    ).andExpect(status().isNotFound());
  }

  @Test
  void closedTicketsRequireExplicitReopeningAndHistorySurvives()
    throws Exception {
    assign();
    send(agent, true, "Before resolution", "PUBLIC");
    db.update("UPDATE tickets SET status='RESOLVED' WHERE id=?", ticket);
    send(customer, false, "Thanks for the resolution", "PUBLIC");
    send(admin, true, "Resolution context", "INTERNAL");
    assertThat(
      db.queryForObject(
        "SELECT status FROM tickets WHERE id=?",
        String.class,
        ticket
      )
    ).isEqualTo("RESOLVED");
    db.update("UPDATE tickets SET status='CLOSED' WHERE id=?", ticket);
    for (String token : List.of(admin, agent))
      postJson(
        path(true),
        message(UUID.randomUUID(), "Closed write", "PUBLIC"),
        token
      ).andExpect(status().isForbidden());
    postJson(
      path(false),
      message(UUID.randomUUID(), "Closed customer write", "PUBLIC"),
      customer
    ).andExpect(status().isForbidden());
    getJson(path(false), customer).andExpect(
      jsonPath("$.canReply").value(false)
    );
    postJson(
      "/api/support/alpha/tickets/" + ticket + "/actions",
      Map.of("version", 0, "action", "REOPEN"),
      customer
    ).andExpect(status().isOk());
    send(customer, false, "Reopened question", "PUBLIC");
    getJson(path(false), customer).andExpect(
      jsonPath("$.items.length()").value(3)
    );
    postJson(
      path(true),
      message(UUID.randomUUID(), "No longer assigned", "PUBLIC"),
      agent
    ).andExpect(status().isForbidden());
  }

  @Test
  void retriesConcurrencyValidationAndRateLimits() throws Exception {
    UUID key = UUID.randomUUID();
    var payload = message(key, "Retry safely", "PUBLIC");
    try (var pool = Executors.newFixedThreadPool(2)) {
      var start = new CountDownLatch(1);
      Callable<JsonNode> task = () -> {
        start.await();
        return body(
          postJson(path(false), payload, customer)
            .andExpect(status().isCreated())
            .andReturn()
        );
      };
      var a = pool.submit(task);
      var b = pool.submit(task);
      start.countDown();
      assertThat(a.get().path("id").asText()).isEqualTo(
        b.get().path("id").asText()
      );
    }
    assertThat(
      db.queryForObject("SELECT count(*) FROM ticket_messages", Long.class)
    ).isEqualTo(1);
    postJson(
      path(false),
      message(key, "Different content", "PUBLIC"),
      customer
    ).andExpect(status().isConflict());
    UUID privateKey = UUID.randomUUID();
    postJson(
      path(true),
      message(privateKey, "Private forever", "INTERNAL"),
      admin
    ).andExpect(status().isCreated());
    postJson(
      path(true),
      message(privateKey, "Private forever", "PUBLIC"),
      admin
    ).andExpect(status().isConflict());
    for (String value : List.of("   ", "x".repeat(5001)))
      postJson(
        path(false),
        message(UUID.randomUUID(), value, "PUBLIC"),
        customer
      ).andExpect(status().isBadRequest());
    for (int i = 1; i < 30; i++) send(
      customer,
      false,
      "Rate fixture " + i,
      "PUBLIC"
    );
    postJson(
      path(false),
      message(UUID.randomUUID(), "Too fast", "PUBLIC"),
      customer
    ).andExpect(status().isTooManyRequests());
    postJson(path(false), payload, customer).andExpect(status().isCreated());
    getJson(path(false) + "?size=10", customer)
      .andExpect(jsonPath("$.items.length()").value(10))
      .andExpect(jsonPath("$.hasMore").value(true));
    getJson(path(false) + "?after=10&size=100", customer).andExpect(
      jsonPath("$.items.length()").value(20)
    );
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

  @Test
  void webSocketAuthenticatesAndFiltersPrivateEventsAfterCommit()
    throws Exception {
    Listener cl = new Listener(),
      sl = new Listener();
    var cs = authenticated(cl, customer, false);
    var ss = authenticated(sl, admin, true);
    try {
      send(admin, true, "Internal secret not on wire", "INTERNAL");
      assertThat(sl.messages.poll(5, TimeUnit.SECONDS))
        .contains("conversation.changed")
        .doesNotContain("Internal secret");
      assertThat(cl.messages.poll(400, TimeUnit.MILLISECONDS)).isNull();
      send(customer, false, "Public websocket delivery", "PUBLIC");
      assertThat(cl.messages.poll(5, TimeUnit.SECONDS)).contains(
        "conversation.changed"
      );
      assertThat(sl.messages.poll(5, TimeUnit.SECONDS)).contains(
        "conversation.changed"
      );
      getJson(path(false), customer).andExpect(
        jsonPath("$.items.length()").value(1)
      );
      db.execute(
        "CREATE FUNCTION reject_message_test() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'test message failure'; END $$"
      );
      db.execute(
        "CREATE TRIGGER reject_message_test BEFORE INSERT ON ticket_messages FOR EACH ROW EXECUTE FUNCTION reject_message_test()"
      );
      try {
        postJson(
          path(false),
          message(UUID.randomUUID(), "Must roll back", "PUBLIC"),
          customer
        ).andExpect(status().isServiceUnavailable());
      } finally {
        db.execute("DROP TRIGGER reject_message_test ON ticket_messages");
        db.execute("DROP FUNCTION reject_message_test()");
      }
      assertThat(cl.messages.poll(400, TimeUnit.MILLISECONDS)).isNull();
      assertThat(
        db.queryForObject(
          "SELECT count(*) FROM ticket_messages WHERE content='Must roll back'",
          Long.class
        )
      ).isZero();
    } finally {
      cs.abort();
      ss.abort();
    }
  }

  @Test
  void webSocketRejectsForgedOriginsReplaySubscriptionChangesAndRevocation()
    throws Exception {
    assertThatThrownBy(() ->
      open(new Listener(), "https://evil.example")
    ).isInstanceOf(ExecutionException.class);
    Listener invalid = new Listener();
    var bad = open(invalid, "http://localhost:3000");
    bad.sendText("{\"credential\":\"forged\"}", true).join();
    assertThat(invalid.closed.get(5, TimeUnit.SECONDS)).isEqualTo(1008);
    String secret = credential(customer, false);
    Listener first = new Listener(),
      second = new Listener();
    var one = open(first, "http://localhost:3000");
    one
      .sendText(json.writeValueAsString(Map.of("credential", secret)), true)
      .join();
    assertThat(first.messages.poll(5, TimeUnit.SECONDS)).contains("ready");
    var two = open(second, "http://localhost:3000");
    two
      .sendText(json.writeValueAsString(Map.of("credential", secret)), true)
      .join();
    assertThat(second.closed.get(5, TimeUnit.SECONDS)).isEqualTo(1008);
    one
      .sendText("{\"type\":\"subscribe\",\"ticketId\":\"other-ticket\"}", true)
      .join();
    assertThat(first.closed.get(5, TimeUnit.SECONDS)).isEqualTo(1008);
    Listener revoked = new Listener();
    var socket = authenticated(revoked, customer, false);
    db.update(
      "UPDATE session_families SET revoked=true WHERE account_id=?",
      customerId
    );
    assertThat(revoked.closed.get(6, TimeUnit.SECONDS)).isEqualTo(1008);
    getJson(path(false), customer).andExpect(status().isUnauthorized());
    socket.abort();
    assign();
    Listener deactivated = new Listener();
    var agentSocket = authenticated(deactivated, agent, true);
    db.update(
      "UPDATE memberships SET status='INACTIVE' WHERE account_id=? AND company_id=?",
      agentId,
      company
    );
    assertThat(deactivated.closed.get(6, TimeUnit.SECONDS)).isEqualTo(1008);
    agentSocket.abort();
  }

  @Test
  void websocketPendingAuthenticationAndOldGrantsCannotBypassMembership()
    throws Exception {
    Listener anonymous = new Listener();
    var socket = open(anonymous, "http://localhost:3000");
    assertThat(anonymous.closed.get(9, TimeUnit.SECONDS)).isEqualTo(1008);
    socket.abort();
    String secret = credential(agent, true);
    db.update(
      "UPDATE memberships SET status='INACTIVE' WHERE account_id=? AND company_id=?",
      agentId,
      company
    );
    Listener listener = new Listener();
    var denied = open(listener, "http://localhost:3000");
    denied
      .sendText(json.writeValueAsString(Map.of("credential", secret)), true)
      .join();
    assertThat(listener.closed.get(5, TimeUnit.SECONDS)).isEqualTo(1008);
  }
}
