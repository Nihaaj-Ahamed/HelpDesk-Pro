package com.helpdesk.identity;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.*;
import org.springframework.stereotype.Component;

@Component
@EnableScheduling
public class SessionCleanup {

  private final JdbcTemplate db;

  public SessionCleanup(JdbcTemplate db) {
    this.db = db;
  }

  @Scheduled(fixedDelay = 3600000, initialDelay = 3600000)
  public void expire() {
    db.update("DELETE FROM session_families WHERE expires_at < now()");
  }
}
