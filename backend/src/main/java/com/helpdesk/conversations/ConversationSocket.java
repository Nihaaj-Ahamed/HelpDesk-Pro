package com.helpdesk.conversations;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/** Native Spring WebSocket: no credentials in URLs, no global message destinations. */
@Component
public class ConversationSocket extends TextWebSocketHandler {

  private final ConversationAccess access;
  private final ObjectMapper json;
  private final Map<String, Grant> grants = new ConcurrentHashMap<>();
  private final Map<String, Connection> connections = new ConcurrentHashMap<>();

  private record Grant(
    String slug,
    UUID ticket,
    Jwt jwt,
    boolean staff,
    Instant expires
  ) {
    @Override
    public String toString() {
      return "SocketGrant[REDACTED]";
    }
  }

  private static final class Connection {

    final WebSocketSession session;
    final Instant opened = Instant.now();
    volatile Grant grant;

    Connection(WebSocketSession s) {
      session = s;
    }
  }

  public record Credential(String credential) {
    @Override
    public String toString() {
      return "SocketCredential[REDACTED]";
    }
  }

  public ConversationSocket(ConversationAccess access, ObjectMapper json) {
    this.access = access;
    this.json = json;
  }

  public synchronized Credential issue(
    String slug,
    UUID ticket,
    Jwt jwt,
    boolean staff
  ) {
    access.require(slug, ticket, jwt, staff);
    grants
      .entrySet()
      .removeIf(e -> e.getValue().expires().isBefore(Instant.now()));
    if (
      grants.size() >= 2048 ||
      grants
        .values()
        .stream()
        .filter(g -> g.jwt().getSubject().equals(jwt.getSubject()))
        .count() >= 8
    ) throw new ResponseStatusException(
      HttpStatus.TOO_MANY_REQUESTS,
      "Too many pending live connections. Please try again shortly."
    );
    byte[] bytes = new byte[32];
    new SecureRandom().nextBytes(bytes);
    String secret = Base64.getUrlEncoder()
      .withoutPadding()
      .encodeToString(bytes);
    grants.put(
      hash(secret),
      new Grant(slug, ticket, jwt, staff, Instant.now().plusSeconds(30))
    );
    return new Credential(secret);
  }

  private String hash(String value) {
    return com.helpdesk.identity.IdentityService.hash(value);
  }

  @Override
  public void afterConnectionEstablished(WebSocketSession session)
    throws Exception {
    if (connections.size() >= 128) {
      session.close(CloseStatus.POLICY_VIOLATION);
      return;
    }
    session.setTextMessageSizeLimit(1024);
    connections.put(session.getId(), new Connection(session));
  }

  private ConversationAccess.Viewer authorized(Grant grant) {
    return access.require(
      grant.slug(),
      grant.ticket(),
      grant.jwt(),
      grant.staff()
    );
  }

  @Override
  protected void handleTextMessage(
    WebSocketSession session,
    TextMessage message
  ) throws Exception {
    var connection = connections.get(session.getId());
    if (connection == null) {
      session.close(CloseStatus.POLICY_VIOLATION);
      return;
    }
    try {
      var frame = json.readTree(message.getPayload());
      if (connection.grant == null) {
        String secret = frame.path("credential").asText();
        if (secret.length() != 43) throw new IllegalArgumentException();
        Grant grant = grants.remove(hash(secret));
        if (
          grant == null || !grant.expires().isAfter(Instant.now())
        ) throw new IllegalArgumentException();
        authorized(grant);
        if (
          connections
            .values()
            .stream()
            .filter(
              c ->
                c.grant != null &&
                c.grant.jwt().getSubject().equals(grant.jwt().getSubject())
            )
            .count() >= 8
        ) throw new IllegalArgumentException();
        connection.grant = grant;
        send(connection, "{\"type\":\"ready\"}");
      } else if (frame.path("type").asText().equals("ping")) {
        authorized(connection.grant);
        send(connection, "{\"type\":\"pong\"}");
      } else throw new IllegalArgumentException();
    } catch (Exception ex) {
      close(connection);
    }
  }

  private void send(Connection connection, String payload) throws IOException {
    synchronized (connection.session) {
      if (connection.session.isOpen()) connection.session.sendMessage(
        new TextMessage(payload)
      );
    }
  }

  private void close(Connection c) {
    connections.remove(c.session.getId());
    try {
      c.session.close(CloseStatus.POLICY_VIOLATION);
    } catch (IOException ignored) {}
  }

  @Override
  public void afterConnectionClosed(WebSocketSession s, CloseStatus status) {
    connections.remove(s.getId());
  }

  @Override
  public void handleTransportError(WebSocketSession s, Throwable error) {
    var c = connections.remove(s.getId());
    if (c != null) close(c);
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void changed(ConversationService.Changed event) {
    for (var c : connections.values()) {
      Grant g = c.grant;
      if (g == null || !g.ticket().equals(event.ticket())) continue;
      // Do not send even a note-activity signal to customer sockets.
      if (event.internal() && !g.staff()) continue;
      try {
        var v = authorized(g);
        if (v.company().equals(event.company())) send(
          c,
          "{\"type\":\"conversation.changed\"}"
        );
      } catch (Exception ex) {
        close(c);
      }
    }
  }

  @Scheduled(fixedDelay = 2000)
  public void sweep() {
    Instant now = Instant.now();
    grants.entrySet().removeIf(e -> !e.getValue().expires().isAfter(now));
    for (var c : connections.values())
      try {
        if (c.grant == null) {
          if (c.opened.plusSeconds(5).isBefore(now)) close(c);
        } else authorized(c.grant);
      } catch (Exception ex) {
        close(c);
      }
  }
}
