package com.helpdesk.conversations;

import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ConversationRepository {

  private final JdbcTemplate db;

  public ConversationRepository(JdbcTemplate db) {
    this.db = db;
  }

  public record Message(
    UUID id,
    long position,
    UUID senderId,
    String senderName,
    String senderRole,
    String visibility,
    String content,
    Instant createdAt
  ) {
    @Override
    public String toString() {
      return "Message[id=" + id + "]";
    }
  }

  private String select(ConversationAccess.Viewer v) {
    return (
      "SELECT id," +
      (v.staff() ? "position" : "public_position") +
      " AS position,sender_id,sender_name,sender_role,visibility,content,created_at FROM ticket_messages "
    );
  }

  private String sequence(ConversationAccess.Viewer v) {
    return v.staff() ? "position" : "public_position";
  }

  private Message map(java.sql.ResultSet r, int n)
    throws java.sql.SQLException {
    return new Message(
      r.getObject("id", UUID.class),
      r.getLong("position"),
      r.getObject("sender_id", UUID.class),
      r.getString("sender_name"),
      r.getString("sender_role"),
      r.getString("visibility"),
      r.getString("content"),
      r.getTimestamp("created_at").toInstant()
    );
  }

  public List<Message> list(ConversationAccess.Viewer v, long after, int size) {
    return db.query(
      select(v) +
        "WHERE company_id=? AND ticket_id=? AND (visibility='PUBLIC' OR ?) AND " +
        sequence(v) +
        ">? ORDER BY " +
        sequence(v) +
        " LIMIT ?",
      this::map,
      v.company(),
      v.ticket().id(),
      v.staff(),
      after,
      size
    );
  }

  public Optional<Message> duplicate(ConversationAccess.Viewer v, UUID key) {
    return db
      .query(
        select(v) +
          "WHERE company_id=? AND ticket_id=? AND sender_id=? AND client_id=?",
        this::map,
        v.company(),
        v.ticket().id(),
        v.account(),
        key
      )
      .stream()
      .findFirst();
  }

  public long rate(ConversationAccess.Viewer v) {
    return db.queryForObject(
      "SELECT count(*) FROM ticket_messages WHERE company_id=? AND sender_id=? AND created_at>now()-interval '1 minute'",
      Long.class,
      v.company(),
      v.account()
    );
  }

  public Message insert(
    ConversationAccess.Viewer v,
    UUID key,
    String visibility,
    String text
  ) {
    UUID id = UUID.randomUUID();
    db.update(
      "INSERT INTO ticket_messages(id,company_id,ticket_id,sender_id,sender_name,sender_role,visibility,content,client_id,public_position) SELECT ?,?,?,id,full_name,?,?,?,?,CASE WHEN ?='PUBLIC' THEN (SELECT count(*)+1 FROM ticket_messages WHERE company_id=? AND ticket_id=? AND visibility='PUBLIC') ELSE NULL END FROM accounts WHERE id=?",
      id,
      v.company(),
      v.ticket().id(),
      v.role(),
      visibility,
      text,
      key,
      visibility,
      v.company(),
      v.ticket().id(),
      v.account()
    );
    if (visibility.equals("PUBLIC")) db.update(
      "UPDATE tickets SET updated_at=clock_timestamp() WHERE company_id=? AND id=?",
      v.company(),
      v.ticket().id()
    );
    return duplicate(v, key).orElseThrow();
  }

  public void read(ConversationAccess.Viewer v, long position) {
    if (
      position > 0 &&
      !Boolean.TRUE.equals(
        db.queryForObject(
          "SELECT EXISTS(SELECT 1 FROM ticket_messages WHERE company_id=? AND ticket_id=? AND " +
            sequence(v) +
            "=? AND (visibility='PUBLIC' OR ?))",
          Boolean.class,
          v.company(),
          v.ticket().id(),
          position,
          v.staff()
        )
      )
    ) throw new org.springframework.web.server.ResponseStatusException(
      org.springframework.http.HttpStatus.BAD_REQUEST,
      "Read marker must refer to a displayed message."
    );
    db.update(
      "INSERT INTO ticket_reads(company_id,ticket_id,account_id,last_position) VALUES (?,?,?,?) ON CONFLICT(company_id,ticket_id,account_id) DO UPDATE SET last_position=greatest(ticket_reads.last_position,excluded.last_position),viewed_at=clock_timestamp()",
      v.company(),
      v.ticket().id(),
      v.account(),
      position
    );
  }
}
