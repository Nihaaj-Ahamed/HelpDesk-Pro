package com.helpdesk.portal;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.mail.*;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class PortalMailer {

  private final MailSender mail;
  private final String origin, from;

  public PortalMailer(
    MailSender mail,
    @Value("${app.origin}") String origin,
    @Value("${app.mail-from}") String from
  ) {
    this.mail = mail;
    this.origin = origin;
    this.from = from;
  }

  public void verify(
    String email,
    PortalRepository.Company company,
    String token
  ) {
    var message = new SimpleMailMessage();
    message.setFrom(from);
    message.setTo(email);
    message.setSubject("Verify your customer support access");
    message.setText(
      "Confirm your email address to use " +
        company.name() +
        " support.\n\n" +
        origin +
        "/support/" +
        company.slug() +
        "/verify#token=" +
        token +
        "\n\nThis private link expires in 30 minutes and works once. Sign in with the same email address to confirm. It grants Customer access only, never staff permissions. If you did not request this, ignore the email."
    );
    try {
      mail.send(message);
    } catch (MailException ex) {
      throw new ResponseStatusException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "Verification email could not be sent. Please try again."
      );
    }
  }
}
