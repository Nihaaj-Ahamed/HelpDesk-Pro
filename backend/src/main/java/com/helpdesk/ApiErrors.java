package com.helpdesk;

import java.util.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
@org.springframework.core.annotation.Order(-1)
public class ApiErrors {

  @ExceptionHandler(jakarta.validation.ConstraintViolationException.class)
  ResponseEntity<ProblemDetail> invalidQuery() {
    return ResponseEntity.badRequest().body(
      ProblemDetail.forStatusAndDetail(
        HttpStatus.BAD_REQUEST,
        "Invalid search or filter value."
      )
    );
  }

  @ExceptionHandler(
    org.springframework.http.converter.HttpMessageNotReadableException.class
  )
  ResponseEntity<ProblemDetail> malformed() {
    return ResponseEntity.badRequest().body(
      ProblemDetail.forStatusAndDetail(
        HttpStatus.BAD_REQUEST,
        "Invalid JSON request."
      )
    );
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<ProblemDetail> validation(MethodArgumentNotValidException ex) {
    var p = ProblemDetail.forStatusAndDetail(
      HttpStatus.BAD_REQUEST,
      "Please check the highlighted fields."
    );
    Map<String, String> fields = new LinkedHashMap<>();
    ex.getBindingResult()
      .getFieldErrors()
      .forEach(e -> fields.put(e.getField(), e.getDefaultMessage()));
    p.setProperty("fields", fields);
    return ResponseEntity.badRequest().body(p);
  }

  @ExceptionHandler(DataIntegrityViolationException.class)
  ResponseEntity<ProblemDetail> duplicate() {
    return ResponseEntity.status(409).body(
      ProblemDetail.forStatusAndDetail(
        HttpStatus.CONFLICT,
        "Email or workspace slug is already registered."
      )
    );
  }

  @ExceptionHandler(org.springframework.dao.DataAccessException.class)
  ResponseEntity<ProblemDetail> databaseUnavailable() {
    return ResponseEntity.status(503).body(
      ProblemDetail.forStatusAndDetail(
        HttpStatus.SERVICE_UNAVAILABLE,
        "The request could not be saved. Please refresh and try again."
      )
    );
  }

  @ExceptionHandler(ResponseStatusException.class)
  ResponseEntity<ProblemDetail> status(ResponseStatusException ex) {
    return ResponseEntity.status(ex.getStatusCode()).body(
      ProblemDetail.forStatusAndDetail(
        ex.getStatusCode(),
        Objects.requireNonNullElse(ex.getReason(), "Request failed")
      )
    );
  }
}
