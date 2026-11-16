package com.helpdesk.identity;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.web.filter.OncePerRequestFilter;

/** Single-node development limiter. Use a shared edge limiter before scaling. */
public final class AuthRateLimitFilter extends OncePerRequestFilter {

  private record Window(long minute, int count) {}

  private final ConcurrentHashMap<String, Window> windows =
    new ConcurrentHashMap<>();
  private final int limit;

  public AuthRateLimitFilter(int limit) {
    this.limit = limit;
  }

  @Override
  protected void doFilterInternal(
    HttpServletRequest req,
    HttpServletResponse res,
    FilterChain chain
  ) throws ServletException, IOException {
    String path = req.getRequestURI();
    if (
      "POST".equals(req.getMethod()) &&
      (path.equals("/api/auth/login") ||
        path.equals("/api/auth/register") ||
        path.equals("/api/auth/invitations/register") ||
        path.startsWith("/api/auth/support/"))
    ) {
      long minute = System.currentTimeMillis() / 60000;
      if (windows.size() > 10000) windows
        .entrySet()
        .removeIf(e -> e.getValue().minute() < minute);
      if (windows.size() > 10000) {
        reject(res);
        return;
      }
      var window = windows.compute(req.getRemoteAddr(), (k, old) ->
        old == null || old.minute() != minute
          ? new Window(minute, 1)
          : new Window(minute, old.count() + 1)
      );
      if (window.count() > limit) {
        reject(res);
        return;
      }
    }
    chain.doFilter(req, res);
  }

  private void reject(HttpServletResponse res) throws IOException {
    res.setStatus(429);
    res.setHeader("Retry-After", "60");
    res.setContentType("application/problem+json");
    res
      .getWriter()
      .write(
        "{\"status\":429,\"title\":\"Too many attempts\",\"detail\":\"Please wait a minute before trying again.\"}"
      );
  }
}
