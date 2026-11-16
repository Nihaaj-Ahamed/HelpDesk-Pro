package com.helpdesk.notifications;

import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EmailOutbox {

  private final JdbcTemplate db;
  private final JavaMailSender mail;
  private final String from, origin;

  public EmailOutbox(
    JdbcTemplate db,
    JavaMailSender mail,
    @Value("${app.mail-from}") String from,
    @Value("${app.origin}") String origin
  ) {
    this.db = db;
    this.mail = mail;
    this.from = from;
    this.origin = origin;
  }

  @Transactional
  public boolean deliverOne() {
    var rows = db.queryForList(
      "SELECT o.id,n.id AS notification,n.company_id,n.recipient_id,n.ticket_id,n.title,n.type,a.email,c.name,c.slug,m.role,m.status,p.email_enabled,p.categories,t.customer_id FROM email_outbox o JOIN notifications n ON n.id=o.notification_id JOIN accounts a ON a.id=n.recipient_id JOIN companies c ON c.id=n.company_id JOIN memberships m ON m.company_id=n.company_id AND m.account_id=n.recipient_id JOIN tickets t ON t.company_id=n.company_id AND t.id=n.ticket_id LEFT JOIN notification_preferences p ON p.company_id=n.company_id AND p.account_id=n.recipient_id WHERE c.status='ACTIVE' AND o.state IN ('PENDING','FAILED') AND o.attempts<6 AND o.next_attempt_at<=now() ORDER BY o.next_attempt_at,o.id FOR UPDATE OF o SKIP LOCKED LIMIT 1"
    );
    if (rows.isEmpty()) return false;
    var r = rows.getFirst();
    Object id = r.get("id");
    String role = r.get("role").toString();
    boolean allowed =
      "ACTIVE".equals(r.get("status")) &&
      (!role.equals("CUSTOMER") ||
        r.get("recipient_id").equals(r.get("customer_id")));
    if (
      !allowed ||
      Boolean.FALSE.equals(r.get("email_enabled")) ||
      (r.get("categories") != null &&
        !Arrays.asList(r.get("categories").toString().split(",")).contains(
          r.get("type").toString()
        ))
    ) {
      db.update(
        "UPDATE email_outbox SET state='SUPPRESSED',last_error='Recipient access or email preferences changed' WHERE id=?",
        id
      );
      return true;
    }
    try {
      String reference = db.queryForObject(
        "SELECT reference FROM tickets WHERE company_id=? AND id=?",
        String.class,
        r.get("company_id"),
        r.get("ticket_id")
      );
      var msg = mail.createMimeMessage();
      var helper = new MimeMessageHelper(msg, "UTF-8");
      helper.setFrom(from);
      helper.setTo(r.get("email").toString());
      helper.setSubject(
        r.get("name") + " | " + r.get("title") + " | " + reference
      );
      helper.setText(
        "Hello,\n\n" +
          r.get("name") +
          " support: " +
          r.get("title") +
          ".\nTicket: " +
          reference +
          "\n\nView this ticket securely:\n" +
          origin +
          NotificationService.url(
            r.get("slug").toString(),
            role,
            r.get("ticket_id").toString()
          ) +
          "\n\nSign in with your own account. This email contains no private notes or message content.\nManage optional emails in your notification preferences.\n\nHelpDesk Pro"
      );
      msg.saveChanges();
      msg.setHeader(
        "Message-ID",
        "<notification-" + r.get("notification") + "@helpdesk.local>"
      );
      mail.send(msg);
      db.update(
        "UPDATE email_outbox SET state='SENT',attempts=attempts+1,sent_at=clock_timestamp(),last_error=NULL WHERE id=?",
        id
      );
    } catch (Exception ex) {
      db.update(
        "UPDATE email_outbox SET state='FAILED',attempts=attempts+1,last_error='Email delivery failed',next_attempt_at=now()+(least(3600,30*power(2,attempts))::int*interval '1 second') WHERE id=?",
        id
      );
    }
    return true;
  }
}
