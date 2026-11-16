package com.helpdesk.team;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.mail.*;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class InvitationMailer {

  private final MailSender mail;
  private final String origin;
  private final String from;

  public InvitationMailer(
    MailSender mail,
    @Value("${app.origin}") String origin,
    @Value("${app.mail-from}") String from
  ) {
    this.mail = mail;
    this.origin = origin;
    this.from = from;
  }

  public void send(String email, String company, String token) {
    var message = new SimpleMailMessage();
    message.setFrom(from);
    message.setTo(email);
    message.setSubject("You're invited to a HelpDesk Pro workspace");
    // Fragments keep the secret out of HTTP access logs and referrers.
    message.setText(
      "You have been invited to join " +
        company +
        " as a Support Agent.\n\nOpen this private link to prove access to this email address and accept the invitation:\n" +
        origin +
        "/invitations/accept#token=" +
        token +
        "\n\nThis invitation expires in 48 hours and can be accepted once. Existing users must sign in to the matching account. If you did not expect this invitation, you can ignore it. Do not forward this link."
    );
    try {
      mail.send(message);
    } catch (MailException ex) {
      throw new ResponseStatusException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "Invitation email could not be sent. Please try again."
      );
    }
  }
}
