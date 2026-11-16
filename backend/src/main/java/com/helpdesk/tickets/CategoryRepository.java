package com.helpdesk.tickets;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CategoryRepository {

  private final JdbcTemplate db;

  public CategoryRepository(JdbcTemplate db) {
    this.db = db;
  }

  public record Category(UUID id, String name) {}

  public void initialize(UUID company) {
    for (String name : List.of("General", "Technical support", "Billing"))
      db.update(
        "INSERT INTO ticket_categories(id,company_id,name) VALUES (?,?,?)",
        UUID.randomUUID(),
        company,
        name
      );
  }

  public List<Category> list(UUID company) {
    return db.query(
      "SELECT id,name FROM ticket_categories WHERE company_id=? AND active=true ORDER BY name",
      (r, n) ->
        new Category(r.getObject("id", UUID.class), r.getString("name")),
      company
    );
  }
}
