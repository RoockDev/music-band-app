package com.banda.common;

import com.banda.security.PermissionDeniedException;
import com.banda.publicsite.ConcurrentContentModificationException;
import com.banda.publicsite.ContentNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * Last-resort safety net for uncaught database failures (connection-pool exhaustion,
 * outage, timeout, etc.). Without this, any {@link DataAccessException} that escapes a
 * service method would surface as Spring's default 500 response, which can leak
 * exception class names/messages to the client. Note this only covers exceptions thrown
 * from controller/service code — it runs AFTER the servlet filter chain, so it does
 * NOT catch failures inside {@code JwtAuthFilter} (see that class for its own handling).
 *
 * <p>Also the single, shared translation point for {@link PermissionDeniedException}
 * (Sec.2/Sec.10) so every gated controller across every future PR (groups, sheet music,
 * events, ...) gets identical, generic-message handling without each duplicating it —
 * {@link PermissionDeniedException}'s own Javadoc requires the specific missing
 * {@code Permission} never be echoed back to the client, only logged server-side.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Map<String, String>> handleDataAccessException(DataAccessException e) {
        log.error("Database access failure", e);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("error", "Service temporarily unavailable"));
    }

    @ExceptionHandler(PermissionDeniedException.class)
    public ResponseEntity<Map<String, String>> handlePermissionDenied(PermissionDeniedException e) {
        log.warn("Permission denied: missing {}", e.getPermission());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Forbidden"));
    }

    @ExceptionHandler(ContentNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleContentNotFound(ContentNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(ConcurrentContentModificationException.class)
    public ResponseEntity<Map<String, String>> handleConcurrentContentModification(
            ConcurrentContentModificationException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }
}
