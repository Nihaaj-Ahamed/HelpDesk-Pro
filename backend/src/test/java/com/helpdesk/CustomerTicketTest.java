package com.helpdesk;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.*;
import com.helpdesk.identity.IdentityService;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
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
class CustomerTicketTest {

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
    r.add("app.auth-rate-limit", () -> 1000);
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

  @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
  org.springframework.mail.javamail.JavaMailSender smtp;

  static final HttpClient http = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(3))
    .build();
  static final String PASSWORD = "a-secure-password-123";
  String alpha, bravo;

  @BeforeEach
  void setup() throws Exception {
    db.execute("TRUNCATE companies,accounts CASCADE");
    http.send(
      HttpRequest.newBuilder(mailUri("/api/v1/messages")).DELETE().build(),
      HttpResponse.BodyHandlers.discarding()
    );
    alpha = owner("owner@alpha.test", "alpha");
    bravo = owner("owner@bravo.test", "bravo");
  }

  URI mailUri(String path) {
    return URI.create(
      "http://" + mailpit.getHost() + ":" + mailpit.getMappedPort(8025) + path
    );
  }

  ResultActions postJson(String path, Object body, String token)
    throws Exception {
    var request = post(path)
      .header("Origin", "http://localhost:3000")
      .contentType("application/json")
      .content(json.writeValueAsBytes(body));
    if (token != null) request.header("Authorization", "Bearer " + token);
    return mvc.perform(request);
  }

  ResultActions getJson(String path, String token) throws Exception {
    var request = get(path);
    if (token != null) request.header("Authorization", "Bearer " + token);
    return mvc.perform(request);
  }

  JsonNode body(MvcResult result) throws Exception {
    return json.readTree(result.getResponse().getContentAsString());
  }

  String access(MvcResult result) throws Exception {
    return body(result).path("accessToken").asText();
  }

  String owner(String email, String slug) throws Exception {
    return access(
      postJson(
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
          slug + " Support",
          "slug",
          slug
        ),
        null
      )
        .andExpect(status().isCreated())
        .andReturn()
    );
  }

  Map<String, Object> registration(String email) {
    return new HashMap<>(
      Map.of(
        "fullName",
        "Customer Taylor",
        "email",
        email,
        "password",
        PASSWORD,
        "confirmPassword",
        PASSWORD
      )
    );
  }

  String register(String email, String slug) throws Exception {
    return access(
      postJson(
        "/api/auth/support/" + slug + "/register",
        registration(email),
        null
      )
        .andExpect(status().isCreated())
        .andReturn()
    );
  }

  String proof(String email, String slug) throws Exception {
    String[] found = { null };
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
          JsonNode detail = json.readTree(
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
          String text = detail.path("Text").asText();
          if (
            detail.path("To").toString().contains(email) &&
            text.contains("/support/" + slug + "/verify#token=")
          ) {
            var matcher = Pattern.compile("#token=([A-Za-z0-9_-]{43})").matcher(
              text
            );
            if (matcher.find()) {
              found[0] = matcher.group(1);
              break;
            }
          }
        }
        assertThat(found[0])
          .as("Customer verification delivered through Mailpit")
          .isNotNull();
      });
    return found[0];
  }

  String customer(String email, String slug) throws Exception {
    String token = register(email, slug);
    postJson(
      "/api/support/" + slug + "/verify",
      Map.of("token", proof(email, slug)),
      token
    ).andExpect(status().isNoContent());
    return token;
  }

  UUID company(String slug) {
    return db.queryForObject(
      "SELECT id FROM companies WHERE slug=?",
      UUID.class,
      slug
    );
  }

  UUID account(String email) {
    return db.queryForObject(
      "SELECT id FROM accounts WHERE email=?",
      UUID.class,
      email
    );
  }

  UUID category(String slug) {
    return db.queryForObject(
      "SELECT id FROM ticket_categories WHERE company_id=? AND name='General'",
      UUID.class,
      company(slug)
    );
  }

  Map<String, Object> ticket(String subject) {
    return new HashMap<>(
      Map.of(
        "subject",
        subject,
        "description",
        "Detailed private customer issue. Please help resolve the problem.",
        "priority",
        "MEDIUM"
      )
    );
  }

  JsonNode create(String slug, String token, String subject) throws Exception {
    return body(
      postJson("/api/support/" + slug + "/tickets", ticket(subject), token)
        .andExpect(status().isCreated())
        .andReturn()
    );
  }

  @Test
  void publicPortalIsCompanySpecificAndContainsOnlySafeConfiguration()
    throws Exception {
    getJson("/api/support/alpha", null)
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.name").value("alpha Support"))
      .andExpect(jsonPath("$.categories.length()").value(3))
      .andExpect(jsonPath("$.priorities[2]").value("HIGH"))
      .andExpect(jsonPath("$.members").doesNotExist());
    getJson("/api/support/unknown", null).andExpect(status().isNotFound());
    postJson(
      "/api/auth/support/unknown/register",
      registration("new@test.test"),
      null
    ).andExpect(status().isNotFound());
    assertThat(
      db.queryForObject("SELECT count(*) FROM accounts", Integer.class)
    ).isEqualTo(2);
  }

  @Test
  void customerRegistrationRequiresCompanyBoundEmailProofBeforeTicketAccess()
    throws Exception {
    String token = register("CUSTOMER@alpha.test", "alpha"),
      proof = proof("customer@alpha.test", "alpha");
    getJson("/api/support/alpha/session", token).andExpect(
      jsonPath("$.status").value("VERIFY_REQUIRED")
    );
    getJson("/api/support/alpha/tickets", token).andExpect(
      status().isForbidden()
    );
    postJson("/api/support/alpha/tickets", ticket("Blocked"), token).andExpect(
      status().isForbidden()
    );
    assertThat(
      db.queryForObject(
        "SELECT token_hash FROM portal_verifications",
        String.class
      )
    )
      .isEqualTo(IdentityService.hash(proof))
      .isNotEqualTo(proof);
    postJson(
      "/api/support/bravo/verify",
      Map.of("token", proof),
      token
    ).andExpect(status().isNotFound());
    postJson(
      "/api/support/alpha/verify",
      Map.of("token", proof),
      bravo
    ).andExpect(status().isForbidden());
    postJson(
      "/api/support/alpha/verify",
      Map.of("token", proof),
      token
    ).andExpect(status().isNoContent());
    assertThat(
      db.queryForObject(
        "SELECT role FROM memberships WHERE account_id=?",
        String.class,
        account("customer@alpha.test")
      )
    ).isEqualTo("CUSTOMER");
    assertThat(
      db.queryForObject(
        "SELECT email_verified_at IS NOT NULL FROM accounts WHERE email='customer@alpha.test'",
        Boolean.class
      )
    ).isTrue();
    getJson("/api/me", token)
      .andExpect(jsonPath("$.workspaces.length()").value(0))
      .andExpect(jsonPath("$.portals.length()").value(1));
    postJson(
      "/api/support/alpha/verify",
      Map.of("token", proof),
      token
    ).andExpect(status().isConflict());
  }

  @Test
  void existingIdentityCanJoinAnotherCompanyWithoutDuplicationOrRoleEscalation()
    throws Exception {
    postJson(
      "/api/auth/support/bravo/register",
      registration("owner@alpha.test"),
      null
    ).andExpect(status().isConflict());
    postJson("/api/support/bravo/verification", Map.of(), alpha).andExpect(
      status().isNoContent()
    );
    postJson(
      "/api/support/bravo/verify",
      Map.of("token", proof("owner@alpha.test", "bravo")),
      alpha
    ).andExpect(status().isNoContent());
    assertThat(
      db.queryForObject("SELECT count(*) FROM accounts", Integer.class)
    ).isEqualTo(2);
    assertThat(
      db.queryForObject("SELECT count(*) FROM companies", Integer.class)
    ).isEqualTo(2);
    getJson("/api/workspaces/alpha", alpha).andExpect(status().isOk());
    getJson("/api/workspaces/bravo/tickets", alpha).andExpect(
      status().isForbidden()
    );
    getJson("/api/support/bravo/session", alpha).andExpect(
      jsonPath("$.status").value("ACTIVE")
    );
  }

  @Test
  void customerCannotReadAnyStaffTicketOrWorkspaceOrTeamEndpoint()
    throws Exception {
    String customer = customer("customer@alpha.test", "alpha");
    var created = create("alpha", customer, "Private ticket");
    for (String path : List.of(
      "/api/workspaces/alpha",
      "/api/workspaces/alpha/tickets",
      "/api/workspaces/alpha/tickets/" + created.path("id").asText(),
      "/api/workspaces/alpha/ticket-overview",
      "/api/workspaces/alpha/team/members",
      "/api/workspaces/alpha/team/invitations"
    ))
      getJson(path, customer).andExpect(status().isForbidden());
    postJson(
      "/api/workspaces/alpha/team/invitations",
      Map.of("email", "agent@alpha.test", "role", "SUPPORT_AGENT"),
      customer
    ).andExpect(status().isForbidden());
  }

  @Test
  void creationUsesAuthenticatedCustomerAndServerDefaultsDespiteForgedIdentityFields()
    throws Exception {
    String customer = customer("customer@alpha.test", "alpha");
    var request = ticket("  Cannot sign in  ");
    request.put("customerId", account("owner@bravo.test"));
    request.put("companyId", company("bravo"));
    request.put("status", "CLOSED");
    request.put("assignedAgentId", account("owner@alpha.test"));
    var created = body(
      postJson("/api/support/alpha/tickets", request, customer)
        .andExpect(status().isCreated())
        .andReturn()
    );
    assertThat(created.path("reference").asText()).matches("HDP-[0-9]+");
    assertThat(created.path("subject").asText()).isEqualTo("Cannot sign in");
    assertThat(created.path("status").asText()).isEqualTo("OPEN");
    assertThat(created.path("assignedAgent").isNull()).isTrue();
    assertThat(created.path("customer").path("id").asText()).isEqualTo(
      account("customer@alpha.test").toString()
    );
    assertThat(created.path("category").asText()).isEqualTo("General");
    assertThat(created.path("createdAt").asText()).isNotBlank();
    assertThat(
      db.queryForObject("SELECT company_id FROM tickets", UUID.class)
    ).isEqualTo(company("alpha"));
  }

  @Test
  void validatesFieldsCategoryOwnershipAndCompanyPriorityRules()
    throws Exception {
    String customer = customer("customer@alpha.test", "alpha");
    for (Map<String, Object> bad : List.<Map<String, Object>>of(
      new HashMap<>(Map.of("subject", " ", "description", "valid")),
      new HashMap<>(Map.of("subject", "ok", "description", " ")),
      new HashMap<>(Map.of("subject", "x".repeat(201), "description", "valid")),
      new HashMap<>(Map.of("subject", "ok", "description", "x".repeat(10001)))
    ))
      postJson("/api/support/alpha/tickets", bad, customer).andExpect(
        status().isBadRequest()
      );
    var request = ticket("Wrong category");
    request.put("categoryId", category("bravo"));
    postJson("/api/support/alpha/tickets", request, customer).andExpect(
      status().isBadRequest()
    );
    request = ticket("Critical rejected");
    request.put("priority", "CRITICAL");
    postJson("/api/support/alpha/tickets", request, customer).andExpect(
      status().isBadRequest()
    );
    db.update(
      "UPDATE companies SET customer_max_priority='LOW' WHERE slug='alpha'"
    );
    request = ticket("Company rule");
    request.put("priority", "HIGH");
    postJson("/api/support/alpha/tickets", request, customer).andExpect(
      status().isBadRequest()
    );
    request.remove("priority");
    postJson("/api/support/alpha/tickets", request, customer)
      .andExpect(status().isCreated())
      .andExpect(jsonPath("$.priority").value("LOW"));
  }

  @Test
  void customerListsDetailsAndCountsNeverExposeOtherCustomersOrTenants()
    throws Exception {
    String one = customer("one@alpha.test", "alpha"),
      two = customer("two@alpha.test", "alpha"),
      other = customer("other@bravo.test", "bravo");
    JsonNode own = create("alpha", one, "Own request"),
      someone = create("alpha", two, "Someone else"),
      cross = create("bravo", other, "Other company");
    getJson("/api/support/alpha/tickets", one)
      .andExpect(jsonPath("$.total").value(1))
      .andExpect(jsonPath("$.items[0].subject").value("Own request"));
    getJson("/api/support/alpha/dashboard", one)
      .andExpect(jsonPath("$.counts.total").value(1))
      .andExpect(jsonPath("$.recent.length()").value(1));
    getJson(
      "/api/support/alpha/tickets/" + someone.path("id").asText(),
      one
    ).andExpect(status().isNotFound());
    getJson(
      "/api/support/alpha/tickets/" + cross.path("id").asText(),
      one
    ).andExpect(status().isNotFound());
    getJson("/api/support/bravo/tickets", one).andExpect(
      status().isForbidden()
    );
    getJson(
      "/api/workspaces/alpha/tickets/" + cross.path("id").asText(),
      alpha
    ).andExpect(status().isNotFound());
    getJson("/api/support/alpha/tickets/" + own.path("id").asText(), one)
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.description").isString());
  }

  @Test
  void staffReadAllCompanyTicketsButCannotCreateAssignOrChangeStatus()
    throws Exception {
    String one = customer("one@alpha.test", "alpha"),
      two = customer("two@alpha.test", "alpha");
    var ticket = create("alpha", one, "One");
    create("alpha", two, "Two");
    db.update(
      "INSERT INTO memberships(account_id,company_id,role) VALUES (?,?,'SUPPORT_AGENT')",
      account("owner@bravo.test"),
      company("alpha")
    );
    getJson("/api/workspaces/alpha/tickets", bravo)
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.total").value(2));
    getJson(
      "/api/workspaces/alpha/tickets/" + ticket.path("id").asText(),
      bravo
    ).andExpect(jsonPath("$.customer.email").value("one@alpha.test"));
    getJson("/api/workspaces/alpha", alpha)
      .andExpect(jsonPath("$.totalTickets").value(2))
      .andExpect(jsonPath("$.openTickets").value(2));
    postJson(
      "/api/support/alpha/tickets",
      ticket("Staff cannot submit"),
      alpha
    ).andExpect(status().isForbidden());
    postJson(
      "/api/workspaces/alpha/tickets/" + ticket.path("id").asText() + "/status",
      Map.of("status", "CLOSED"),
      bravo
    ).andExpect(status().isNotFound());
    db.update(
      "UPDATE memberships SET status='INACTIVE' WHERE company_id=? AND account_id=?",
      company("alpha"),
      account("owner@bravo.test")
    );
    getJson("/api/workspaces/alpha/tickets", bravo).andExpect(
      status().isNotFound()
    );
    getJson("/api/workspaces/alpha/ticket-overview", bravo).andExpect(
      status().isNotFound()
    );
    getJson("/api/workspaces/bravo", bravo).andExpect(status().isOk());
  }

  @Test
  void filteringPaginationAndDashboardStatusesUsePersistedScopedData()
    throws Exception {
    String customer = customer("customer@alpha.test", "alpha");
    JsonNode first = create("alpha", customer, "Invoice problem"),
      second = create("alpha", customer, "Technical issue");
    create("alpha", customer, "General question");
    db.update(
      "UPDATE tickets SET status='RESOLVED' WHERE id=?",
      UUID.fromString(first.path("id").asText())
    );
    db.update(
      "UPDATE tickets SET status='CLOSED' WHERE id=?",
      UUID.fromString(second.path("id").asText())
    );
    getJson("/api/support/alpha/dashboard", customer)
      .andExpect(jsonPath("$.counts.total").value(3))
      .andExpect(jsonPath("$.counts.open").value(1))
      .andExpect(jsonPath("$.counts.resolved").value(1))
      .andExpect(jsonPath("$.counts.closed").value(1));
    getJson(
      "/api/support/alpha/tickets?search=invoice&status=RESOLVED&priority=MEDIUM",
      customer
    ).andExpect(jsonPath("$.total").value(1));
    JsonNode page1 = body(
        getJson("/api/support/alpha/tickets?size=1&page=0", customer)
          .andExpect(jsonPath("$.total").value(3))
          .andReturn()
      ),
      page2 = body(
        getJson(
          "/api/support/alpha/tickets?size=1&page=1",
          customer
        ).andReturn()
      );
    assertThat(page1.path("items").get(0).path("id").asText()).isNotEqualTo(
      page2.path("items").get(0).path("id").asText()
    );
    getJson(
      "/api/support/alpha/tickets?category=" + category("bravo"),
      customer
    ).andExpect(jsonPath("$.total").value(0));
    getJson("/api/support/alpha/tickets?size=101", customer).andExpect(
      status().isBadRequest()
    );
    getJson("/api/support/alpha/tickets?status=HACKED", customer).andExpect(
      status().isBadRequest()
    );
  }

  @Test
  void expiredReplacedAndMismatchedProofsNeverGrantMembership()
    throws Exception {
    String customer = register("customer@alpha.test", "alpha"),
      old = proof("customer@alpha.test", "alpha");
    db.update(
      "UPDATE portal_verifications SET expires_at=now()-interval '1 minute'"
    );
    postJson(
      "/api/support/alpha/verify",
      Map.of("token", old),
      customer
    ).andExpect(status().isGone());
    postJson("/api/support/alpha/verification", Map.of(), customer).andExpect(
      status().isNoContent()
    );
    String next = proof("customer@alpha.test", "alpha");
    assertThat(next).isNotEqualTo(old);
    postJson(
      "/api/support/alpha/verify",
      Map.of("token", old),
      customer
    ).andExpect(status().isGone());
    postJson(
      "/api/support/alpha/verify",
      Map.of("token", next),
      customer
    ).andExpect(status().isNoContent());
  }

  @Test
  void inactiveCustomerCannotRegainAccessByRequestingNewProof()
    throws Exception {
    String customer = customer("customer@alpha.test", "alpha");
    var t = create("alpha", customer, "Before deactivation");
    db.update(
      "UPDATE memberships SET status='INACTIVE' WHERE account_id=?",
      account("customer@alpha.test")
    );
    for (String endpoint : List.of(
      "tickets",
      "dashboard",
      "tickets/" + t.path("id").asText()
    ))
      getJson("/api/support/alpha/" + endpoint, customer).andExpect(
        status().isForbidden()
      );
    postJson("/api/support/alpha/verification", Map.of(), customer).andExpect(
      status().isConflict()
    );
    postJson(
      "/api/support/alpha/tickets",
      ticket("Denied"),
      customer
    ).andExpect(status().isForbidden());
  }

  @Test
  void anonymousAndForgedSessionsCannotCreateOrReadTickets() throws Exception {
    for (String endpoint : List.of("tickets", "dashboard", "session"))
      getJson("/api/support/alpha/" + endpoint, null).andExpect(
        status().isUnauthorized()
      );
    postJson("/api/support/alpha/tickets", ticket("Denied"), null).andExpect(
      status().isUnauthorized()
    );
    getJson("/api/support/alpha/tickets", "forged").andExpect(
      status().isUnauthorized()
    );
  }

  @Test
  void proofRateLimitAndSmtpFailurePreserveAccountIntegrity() throws Exception {
    org.mockito.Mockito.doThrow(
      new org.springframework.mail.MailSendException("SMTP down")
    )
      .when(smtp)
      .send(
        org.mockito.ArgumentMatchers.any(
          org.springframework.mail.SimpleMailMessage.class
        )
      );
    postJson(
      "/api/auth/support/alpha/register",
      registration("failed@alpha.test"),
      null
    ).andExpect(status().isServiceUnavailable());
    assertThat(
      db.queryForObject("SELECT count(*) FROM accounts", Integer.class)
    ).isEqualTo(2);
    assertThat(
      db.queryForObject(
        "SELECT count(*) FROM portal_verifications",
        Integer.class
      )
    ).isZero();
    org.mockito.Mockito.reset(smtp);
    String customer = register("limited@alpha.test", "alpha");
    for (int i = 0; i < 4; i++) postJson(
      "/api/support/alpha/verification",
      Map.of(),
      customer
    ).andExpect(status().isNoContent());
    postJson("/api/support/alpha/verification", Map.of(), customer).andExpect(
      status().isTooManyRequests()
    );
  }

  @Test
  void concurrentProofAcceptanceAndTicketReferencesRemainUnique()
    throws Exception {
    String customer = register("customer@alpha.test", "alpha"),
      proof = proof("customer@alpha.test", "alpha");
    try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var start = new java.util.concurrent.CountDownLatch(1);
      java.util.concurrent.Callable<Integer> verify = () -> {
        start.await();
        return postJson(
          "/api/support/alpha/verify",
          Map.of("token", proof),
          customer
        )
          .andReturn()
          .getResponse()
          .getStatus();
      };
      var one = pool.submit(verify);
      var two = pool.submit(verify);
      start.countDown();
      assertThat(List.of(one.get(), two.get())).containsExactlyInAnyOrder(
        204,
        409
      );
    }
    JsonNode one = create("alpha", customer, "First reference"),
      two = create("alpha", customer, "Second reference");
    assertThat(one.path("reference").asText()).isNotEqualTo(
      two.path("reference").asText()
    );
    assertThat(
      db.queryForObject(
        "SELECT count(*) FROM memberships WHERE account_id=?",
        Integer.class,
        account("customer@alpha.test")
      )
    ).isEqualTo(1);
  }

  String staffAgent(String email) throws Exception {
    String token = owner(
      email,
      "agent-" + UUID.randomUUID().toString().substring(0, 8)
    );
    db.update(
      "INSERT INTO memberships(account_id,company_id,role) VALUES (?,?,'SUPPORT_AGENT')",
      account(email),
      company("alpha")
    );
    return token;
  }

  JsonNode newWorkflowTicket(String token) throws Exception {
    return body(
      postJson("/api/support/alpha/tickets", ticket("Workflow request"), token)
        .andExpect(status().isCreated())
        .andReturn()
    );
  }

  String actionPath(JsonNode t) {
    return (
      "/api/workspaces/alpha/tickets/" + t.path("id").asText() + "/actions"
    );
  }

  Map<String, Object> change(JsonNode t, String action, String value) {
    var m = new HashMap<String, Object>();
    m.put("version", t.path("version").asLong());
    m.put("action", action);
    if (value != null) m.put("value", value);
    return m;
  }

  @Test
  void workflowAssignmentLifecycleAndCustomerVisibility() throws Exception {
    String c = customer("workflow@alpha.test", "alpha"),
      a = staffAgent("agent@alpha.test");
    JsonNode t = newWorkflowTicket(c);
    String path = actionPath(t);
    var assign = change(t, "ASSIGN", null);
    assign.put("agentId", account("agent@alpha.test"));
    t = body(
      postJson(path, assign, alpha)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ASSIGNED"))
        .andReturn()
    );
    t = body(
      postJson(path, change(t, "STATUS", "IN_PROGRESS"), a)
        .andExpect(status().isOk())
        .andReturn()
    );
    t = body(
      postJson(path, change(t, "PRIORITY", "CRITICAL"), a)
        .andExpect(status().isOk())
        .andReturn()
    );
    t = body(
      postJson(path, change(t, "STATUS", "RESOLVED"), a)
        .andExpect(status().isOk())
        .andReturn()
    );
    String customerPath = "/api/support/alpha/tickets/" + t.path("id").asText();
    t = body(
      postJson(customerPath + "/actions", change(t, "STATUS", "CLOSED"), c)
        .andExpect(status().isOk())
        .andReturn()
    );
    postJson(path, change(t, "PRIORITY", "LOW"), alpha).andExpect(
      status().isForbidden()
    );
    t = body(
      postJson(customerPath + "/actions", change(t, "REOPEN", null), c)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("OPEN"))
        .andExpect(jsonPath("$.assignedAgent").isEmpty())
        .andReturn()
    );
    var history = body(
      getJson(customerPath + "/activity", c)
        .andExpect(status().isOk())
        .andReturn()
    );
    assertThat(history.toString()).doesNotContain(
      "ASSIGNMENT",
      "agent@alpha.test",
      "SUPPORT_AGENT"
    );
    assertThat(history.findValuesAsText("kind")).contains(
      "CREATED",
      "STATUS",
      "PRIORITY",
      "REOPENED"
    );
    assertThat(
      body(
        getJson(path.replace("/actions", "/activity"), alpha).andReturn()
      ).findValuesAsText("kind")
    ).contains("ASSIGNMENT");
    UUID id = UUID.fromString(t.path("id").asText());
    assertThatThrownBy(() ->
      db.update("DELETE FROM ticket_activity WHERE ticket_id=?", id)
    ).hasMessageContaining("immutable");
  }

  @Test
  void workflowRejectsForeignAgentsOtherOwnersCustomersAndStaleUpdates()
    throws Exception {
    String c = customer("workflow@alpha.test", "alpha"),
      a = staffAgent("agent@alpha.test"),
      b = staffAgent("other@alpha.test");
    JsonNode t = newWorkflowTicket(c);
    String path = actionPath(t);
    postJson(path, Map.of("action", "CLAIM"), a).andExpect(
      status().isBadRequest()
    );
    postJson(path, Map.of("action", "CLAIM", "version", -1), a).andExpect(
      status().isBadRequest()
    );
    getJson(path.replace("/actions", "/activity"), bravo).andExpect(
      status().isNotFound()
    );
    getJson(path, b).andExpect(status().isOk());
    var invalid = change(t, "ASSIGN", null);
    invalid.put("agentId", account("owner@bravo.test"));
    postJson(path, invalid, alpha).andExpect(status().isBadRequest());
    db.update(
      "UPDATE memberships SET status='INACTIVE' WHERE account_id=? AND company_id=?",
      account("other@alpha.test"),
      company("alpha")
    );
    getJson(path, b).andExpect(status().isNotFound());
    getJson(path.replace("/actions", "/activity"), b).andExpect(
      status().isNotFound()
    );
    invalid.put("agentId", account("other@alpha.test"));
    postJson(path, invalid, alpha).andExpect(status().isBadRequest());
    postJson(path, change(t, "CLAIM", null), c).andExpect(
      status().isForbidden()
    );
    postJson(path, change(t, "CLAIM", null), bravo).andExpect(
      status().isNotFound()
    );
    postJson(path, change(t, "STATUS", "CLOSED"), alpha).andExpect(
      status().isConflict()
    );
    JsonNode claimed = body(
      postJson(path, change(t, "CLAIM", null), a)
        .andExpect(status().isOk())
        .andReturn()
    );
    postJson(path, change(t, "UNASSIGN", null), alpha).andExpect(
      status().isConflict()
    );
    postJson(path, change(claimed, "PRIORITY", "LOW"), b).andExpect(
      status().isNotFound()
    );
    db.update(
      "UPDATE memberships SET status='ACTIVE' WHERE account_id=? AND company_id=?",
      account("other@alpha.test"),
      company("alpha")
    );
    postJson(path, change(claimed, "PRIORITY", "LOW"), b).andExpect(
      status().isForbidden()
    );
    postJson(path, change(claimed, "UNASSIGN", null), a)
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.status").value("OPEN"));
    getJson("/api/workspaces/alpha/tickets?queue=UNASSIGNED", a).andExpect(
      jsonPath("$.total").value(1)
    );
    getJson("/api/workspaces/alpha/tickets?queue=MINE", a).andExpect(
      jsonPath("$.total").value(0)
    );
    String otherCustomer = customer("another@alpha.test", "alpha");
    postJson(
      "/api/support/alpha/tickets/" + t.path("id").asText() + "/actions",
      change(t, "REOPEN", null),
      otherCustomer
    ).andExpect(status().isNotFound());
  }

  @Test
  void simultaneousClaimsHaveOneWinnerAndAtomicHistory() throws Exception {
    String c = customer("workflow@alpha.test", "alpha"),
      a = staffAgent("agent@alpha.test"),
      b = staffAgent("other@alpha.test");
    JsonNode t = newWorkflowTicket(c);
    String path = actionPath(t);
    try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var start = new java.util.concurrent.CountDownLatch(1);
      var first = pool.submit(() -> {
        start.await();
        return postJson(path, change(t, "CLAIM", null), a)
          .andReturn()
          .getResponse()
          .getStatus();
      });
      var second = pool.submit(() -> {
        start.await();
        return postJson(path, change(t, "CLAIM", null), b)
          .andReturn()
          .getResponse()
          .getStatus();
      });
      start.countDown();
      assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(
        200,
        409
      );
    }
    UUID id = UUID.fromString(t.path("id").asText());
    assertThat(
      db.queryForObject(
        "SELECT count(*) FROM ticket_activity WHERE ticket_id=? AND kind='ASSIGNMENT'",
        Long.class,
        id
      )
    ).isEqualTo(1);
    assertThat(
      db.queryForObject(
        "SELECT version FROM tickets WHERE id=?",
        Long.class,
        id
      )
    ).isEqualTo(1);
  }

  @Test
  void adminReassignmentWorkloadsAndFailureLeaveAtomicState() throws Exception {
    String c = customer("workflow@alpha.test", "alpha"),
      a = staffAgent("agent@alpha.test"),
      b = staffAgent("other@alpha.test");
    JsonNode t = newWorkflowTicket(c);
    String path = actionPath(t);
    var assign = change(t, "ASSIGN", null);
    assign.put("agentId", account("agent@alpha.test"));
    t = body(
      postJson(path, assign, alpha).andExpect(status().isOk()).andReturn()
    );
    var options = body(
      getJson(path, alpha).andExpect(status().isOk()).andReturn()
    );
    assertThat(options.path("agents").toString()).contains("workload\":1");
    var reassign = change(t, "ASSIGN", null);
    reassign.put("agentId", account("other@alpha.test"));
    postJson(path, reassign, a).andExpect(status().isForbidden());
    t = body(
      postJson(path, reassign, alpha)
        .andExpect(status().isOk())
        .andExpect(
          jsonPath("$.assignedAgent.id").value(
            account("other@alpha.test").toString()
          )
        )
        .andReturn()
    );
    postJson(path, change(t, "STATUS", "IN_PROGRESS"), a).andExpect(
      status().isConflict()
    );
    getJson("/api/workspaces/alpha/tickets?queue=MINE", b).andExpect(
      jsonPath("$.total").value(1)
    );
    getJson("/api/workspaces/alpha/tickets?queue=MINE", a).andExpect(
      jsonPath("$.total").value(0)
    );
    UUID id = UUID.fromString(t.path("id").asText());
    long before = t.path("version").asLong();
    // Force a database failure in the activity insert; the corresponding priority update must roll back.
    db.execute(
      "CREATE FUNCTION reject_activity_test() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'test audit failure'; END $$"
    );
    db.execute(
      "CREATE TRIGGER reject_activity_test BEFORE INSERT ON ticket_activity FOR EACH ROW EXECUTE FUNCTION reject_activity_test()"
    );
    try {
      postJson(path, change(t, "PRIORITY", "LOW"), alpha).andExpect(
        status().is5xxServerError()
      );
    } finally {
      db.execute("DROP TRIGGER reject_activity_test ON ticket_activity");
      db.execute("DROP FUNCTION reject_activity_test()");
    }
    assertThat(
      db.queryForObject(
        "SELECT version FROM tickets WHERE id=?",
        Long.class,
        id
      )
    ).isEqualTo(before);
    assertThat(
      db.queryForObject(
        "SELECT priority FROM tickets WHERE id=?",
        String.class,
        id
      )
    ).isEqualTo("MEDIUM");
  }
}
