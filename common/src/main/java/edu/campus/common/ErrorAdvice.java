package edu.campus.common;

import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class ErrorAdvice {
  @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
  ResponseEntity<?> missing(Exception e) {
    return ResponseEntity.status(404).body(Map.of("error", "NOT_FOUND", "message", "资源不存在"));
  }

  @ExceptionHandler(ApiException.class)
  ResponseEntity<?> api(ApiException e) {
    return ResponseEntity.status(e.status).body(Map.of("error", e.code, "message", e.getMessage()));
  }

  @ExceptionHandler({
    IllegalArgumentException.class,
    org.springframework.http.converter.HttpMessageNotReadableException.class,
    org.springframework.web.bind.MethodArgumentNotValidException.class,
    org.springframework.web.bind.MissingServletRequestParameterException.class
  })
  ResponseEntity<?> invalid(Exception e) {
    return ResponseEntity.badRequest()
        .body(Map.of("error", "INVALID_INPUT", "message", "输入格式错误或字段缺失"));
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<?> other(Exception e) {
    String id = UUID.randomUUID().toString();
    e.printStackTrace();
    System.err.println("Request failure " + id + " " + e.getClass().getName() + ": " + e.getMessage());
    // Check if this is an IllegalArgumentException from integrity check
    if (e instanceof IllegalArgumentException) {
      System.err.println("=== ILLEGAL ARG TRACE ===");
      e.printStackTrace(System.err);
    }
    return ResponseEntity.status(500)
        .body(Map.of("error", "INTERNAL_ERROR", "message", "操作失败，请联系管理员。编号：" + id));
  }
}
