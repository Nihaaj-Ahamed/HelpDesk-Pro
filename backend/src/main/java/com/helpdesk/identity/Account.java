package com.helpdesk.identity;

import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "accounts")
public class Account {

  @Id
  private UUID id;

  @Column(name = "full_name", nullable = false, length = 100)
  private String fullName;

  @Column(nullable = false, unique = true, length = 254)
  private String email;

  @Column(name = "password_hash", nullable = false, length = 100)
  private String passwordHash;

  protected Account() {}

  public Account(UUID id, String fullName, String email, String passwordHash) {
    this.id = id;
    this.fullName = fullName;
    this.email = email;
    this.passwordHash = passwordHash;
  }

  public UUID id() {
    return id;
  }

  public String passwordHash() {
    return passwordHash;
  }
}
