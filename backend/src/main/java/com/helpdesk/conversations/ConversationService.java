package com.helpdesk.conversations;

import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ConversationService {

  private final com.helpdesk.sla.SlaService sla;
  private final com.helpdesk.notifications.NotificationService notices;
  private final ConversationAccess access;
  private final ConversationRepository repository;
  private final ApplicationEventPublisher events;

  public ConversationService(
    ConversationAccess access,
    ConversationRepository repository,
    ApplicationEventPublisher events,
    com.helpdesk.sla.SlaService sla,
    com.helpdesk.notifications.NotificationService notices
  ) {
    this.sla = sla;
    this.notices = notices;
    this.access = access;
    this.repository = repository;
    this.events = events;
  }

  public record Send(
    @NotNull UUID clientId,
    @NotBlank @Size(max = 5000) String content,
    @NotNull Visibility visibility
  ) {
    @Override
    public String toString() {
      return "SendMessage[REDACTED]";
    }
  }

  public enum Visibility {
    PUBLIC,
    INTERNAL,
  }

  public record Read(@Min(0) long position) {}

  public record History(
    List<ConversationRepository.Message> items,
    boolean hasMore,
    long cursor,
    boolean canReply,
    boolean canNote,
    UUID viewerId
  ) {}

  public record Changed(UUID company, UUID ticket, boolean internal) {}

  public History history(
    String slug,
    UUID ticket,
    Jwt jwt,
    boolean staff,
    long after,
    int size
  ) {
    var v = access.require(slug, ticket, jwt, staff);
    var all = repository.list(v, after, size + 1);
    boolean more = all.size() > size;
    var items = all.subList(0, Math.min(size, all.size()));
    return new History(
      items,
      more,
      items.isEmpty() ? after : items.getLast().position(),
      v.writable(),
      v.staff() && v.writable(),
      v.account()
    );
  }

  @Transactional
  public ConversationRepository.Message send(
    String slug,
    UUID ticket,
    Jwt jwt,
    boolean staff,
    Send request
  ) {
    var v = access.require(slug, ticket, jwt, staff);
    access.lock(v.company());
    v = access.require(slug, ticket, jwt, staff);
    if (
      request.visibility() == Visibility.INTERNAL && !v.staff()
    ) throw new ResponseStatusException(
      HttpStatus.FORBIDDEN,
      "Internal notes are for company staff only."
    );
    var existing = repository.duplicate(v, request.clientId());
    if (existing.isPresent()) {
      var m = existing.get();
      if (
        !m.content().equals(request.content().strip()) ||
        !m.visibility().equals(request.visibility().name())
      ) throw new ResponseStatusException(
        HttpStatus.CONFLICT,
        "This send key was already used for a different message."
      );
      return m;
    }
    if (!v.writable()) throw new ResponseStatusException(
      HttpStatus.FORBIDDEN,
      v.ticket().status().equals("CLOSED")
        ? "Closed tickets are read-only. Reopen the ticket explicitly before replying."
        : "Only the assigned agent or a company administrator can reply or add notes."
    );
    if (repository.rate(v) >= 30) throw new ResponseStatusException(
      HttpStatus.TOO_MANY_REQUESTS,
      "Message limit reached. Please wait a minute."
    );
    var message = repository.insert(
      v,
      request.clientId(),
      request.visibility().name(),
      request.content().strip()
    );
    if (request.visibility() == Visibility.PUBLIC) {
      if (v.staff()) sla.response(
        v.company(),
        ticket,
        v.account(),
        message.createdAt()
      );
      notices.ticket(
        v.company(),
        ticket,
        v.account(),
        com.helpdesk.notifications.NotificationService.Type.REPLY,
        "message:" + message.id(),
        null
      );
    }
    events.publishEvent(
      new Changed(
        v.company(),
        ticket,
        request.visibility() == Visibility.INTERNAL
      )
    );
    return message;
  }

  @Transactional
  public void read(
    String slug,
    UUID ticket,
    Jwt jwt,
    boolean staff,
    long position
  ) {
    var v = access.require(slug, ticket, jwt, staff);
    access.lock(v.company());
    v = access.require(slug, ticket, jwt, staff);
    repository.read(v, position);
  }
}
