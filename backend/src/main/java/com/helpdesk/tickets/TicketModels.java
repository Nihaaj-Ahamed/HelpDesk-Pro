package com.helpdesk.tickets;

import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;

public final class TicketModels {

  private TicketModels() {}

  public enum Priority {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL,
  }

  public record Create(
    @NotBlank @Size(max = 200) String subject,
    @NotBlank @Size(max = 10000) String description,
    UUID categoryId,
    Priority priority
  ) {
    @Override
    public String toString() {
      return "CreateTicket[REDACTED]";
    }
  }

  public record Customer(UUID id, String name, String email) {}

  public record Agent(UUID id, String name) {}

  public record Ticket(
    UUID id,
    String reference,
    String subject,
    String description,
    UUID categoryId,
    String category,
    String priority,
    String status,
    Instant createdAt,
    Instant updatedAt,
    Customer customer,
    Agent assignedAgent,
    long version,
    long unreadCount,
    String slaState
  ) {
    public Ticket withUnread(long count) {
      return new Ticket(
        id,
        reference,
        subject,
        description,
        categoryId,
        category,
        priority,
        status,
        createdAt,
        updatedAt,
        customer,
        assignedAgent,
        version,
        count,
        slaState
      );
    }

    @Override
    public String toString() {
      return (
        "Ticket[id=" +
        id +
        ", reference=" +
        reference +
        ", status=" +
        status +
        "]"
      );
    }
  }

  public record Page(List<Ticket> items, long total, int page, int size) {}

  public record Counts(
    long total,
    long open,
    long resolved,
    long closed,
    long unassigned,
    long unread
  ) {}

  public record Dashboard(Counts counts, List<Ticket> recent) {}

  public record Scope(UUID company, UUID customer, String queue, UUID actor) {
    public Scope(UUID company, UUID customer) {
      this(company, customer, "ALL", customer);
    }
  }
}
