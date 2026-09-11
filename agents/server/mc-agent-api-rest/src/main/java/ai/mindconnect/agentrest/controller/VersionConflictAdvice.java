package ai.mindconnect.agentrest.controller;

import ai.mindconnect.common.StaleVersionException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * A save that carried a stale version — an agent, an LLM config or a vector-store
 * template saved by someone else since the client read it — answers 409 Conflict,
 * with the version now stored so the client can re-read and decide.
 */
@RestControllerAdvice(basePackages = "ai.mindconnect.agentrest")
public class VersionConflictAdvice {

    @ExceptionHandler(StaleVersionException.class)
    public ResponseEntity<Map<String, Object>> conflict(StaleVersionException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "error", e.getMessage(),
                "code", e.code(),
                "expectedVersion", e.expectedVersion(),
                "storedVersion", e.storedVersion()));
    }
}
