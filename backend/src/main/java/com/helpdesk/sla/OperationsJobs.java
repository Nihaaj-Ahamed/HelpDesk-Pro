package com.helpdesk.sla;

import com.helpdesk.notifications.EmailOutbox;
import java.time.Instant;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
  name = "app.operations-jobs-enabled",
  havingValue = "true",
  matchIfMissing = true
)
public class OperationsJobs {

  private final SlaService sla;
  private final EmailOutbox email;

  public OperationsJobs(SlaService sla, EmailOutbox email) {
    this.sla = sla;
    this.email = email;
  }

  @Scheduled(fixedDelay = 30000, initialDelay = 10000)
  public void sla() {
    for (var company : sla.pending())
      try {
        sla.evaluate(company, Instant.now());
      } catch (org.springframework.dao.DataAccessException ignored) {}
  }

  @Scheduled(fixedDelay = 5000, initialDelay = 5000)
  public void email() {
    for (int i = 0; i < 10; i++) try {
      if (!email.deliverOne()) break;
    } catch (org.springframework.dao.DataAccessException ex) {
      break;
    }
  }
}
