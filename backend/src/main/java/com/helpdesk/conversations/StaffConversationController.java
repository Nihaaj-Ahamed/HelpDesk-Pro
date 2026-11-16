package com.helpdesk.conversations;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@Validated
@RequestMapping("/api/workspaces/{slug}/tickets/{id}/conversation")
public class StaffConversationController {

  private final ConversationService service;
  private final ConversationSocket socket;

  public StaffConversationController(
    ConversationService service,
    ConversationSocket socket
  ) {
    this.service = service;
    this.socket = socket;
  }

  @GetMapping
  public ConversationService.History history(
    @PathVariable String slug,
    @PathVariable UUID id,
    @AuthenticationPrincipal Jwt jwt,
    @RequestParam(defaultValue = "0") @Min(0) long after,
    @RequestParam(defaultValue = "100") @Min(1) @Max(100) int size
  ) {
    return service.history(slug, id, jwt, true, after, size);
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public ConversationRepository.Message send(
    @PathVariable String slug,
    @PathVariable UUID id,
    @AuthenticationPrincipal Jwt jwt,
    @Valid @RequestBody ConversationService.Send request
  ) {
    return service.send(slug, id, jwt, true, request);
  }

  @PostMapping("/read")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void read(
    @PathVariable String slug,
    @PathVariable UUID id,
    @AuthenticationPrincipal Jwt jwt,
    @Valid @RequestBody ConversationService.Read request
  ) {
    service.read(slug, id, jwt, true, request.position());
  }

  @PostMapping("/live")
  public ConversationSocket.Credential live(
    @PathVariable String slug,
    @PathVariable UUID id,
    @AuthenticationPrincipal Jwt jwt
  ) {
    return socket.issue(slug, id, jwt, true);
  }
}
