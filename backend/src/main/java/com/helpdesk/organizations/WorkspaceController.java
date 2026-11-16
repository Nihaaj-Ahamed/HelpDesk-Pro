package com.helpdesk.organizations;

import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
public class WorkspaceController {

  private final WorkspaceRepository repository;
  private final WorkspaceAccess access;

  public WorkspaceController(
    WorkspaceRepository repository,
    WorkspaceAccess access
  ) {
    this.repository = repository;
    this.access = access;
  }

  @GetMapping("/me")
  public Map<String, Object> me(@AuthenticationPrincipal Jwt jwt) {
    UUID id = access.account(jwt);
    var user = repository.profile(id);
    user.put("platformAdmin", repository.platformAdmin(id));
    user.put("workspaces", repository.memberships(id));
    user.put("portals", repository.portals(id));
    return user;
  }

  @GetMapping("/workspaces/{slug}")
  public Map<String, Object> workspace(
    @PathVariable String slug,
    @AuthenticationPrincipal Jwt jwt
  ) {
    UUID user = access.member(slug, jwt).accountId();
    return repository
      .dashboard(slug, user)
      .orElseThrow(() ->
        new ResponseStatusException(
          HttpStatus.NOT_FOUND,
          "Workspace not found."
        )
      );
  }
}
