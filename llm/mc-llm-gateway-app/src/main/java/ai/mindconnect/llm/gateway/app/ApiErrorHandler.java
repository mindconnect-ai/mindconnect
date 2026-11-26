package ai.mindconnect.llm.gateway.app;

import ai.mindconnect.common.DomainException;
import ai.mindconnect.common.StaleVersionException;
import ai.mindconnect.llm.runtime.api.domain.LlmTransientException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** The gateway's exceptions as problem details. */
@RestControllerAdvice
class ApiErrorHandler {

    @ExceptionHandler(StaleVersionException.class)
    ProblemDetail stale(StaleVersionException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(DomainException.class)
    ProblemDetail domain(DomainException e) {
        HttpStatus status = "NOT_FOUND".equals(e.code()) ? HttpStatus.NOT_FOUND : HttpStatus.BAD_REQUEST;
        return ProblemDetail.forStatusAndDetail(status, e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail invalid(IllegalArgumentException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    /** The provider is overloaded or rate-limited and the config's retries are spent. */
    @ExceptionHandler(LlmTransientException.class)
    ProblemDetail transientFailure(LlmTransientException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
    }

    /** A provider call that failed, or an alias chain that leads nowhere. */
    @ExceptionHandler(IllegalStateException.class)
    ProblemDetail providerFailure(IllegalStateException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, e.getMessage());
    }
}
