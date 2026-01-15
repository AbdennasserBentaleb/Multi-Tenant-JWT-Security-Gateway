package dev.gateway.exception;

import dev.gateway.tenant.JwtTenantConverter.TenantIdInvalidException;
import dev.gateway.tenant.JwtTenantConverter.TenantIdMissingException;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Global exception handler using Spring's RFC 7807 {@link ProblemDetail}.
 *
 * <p>
 * All error responses follow the Problem Details JSON format:
 * 
 * <pre>{@code
 * {
 *   "type": "https://gateway.dev/errors/validation-error",
 *   "title": "Validation Failed",
 *   "status": 400,
 *   "detail": "...",
 *   "instance": "/api/products",
 *   "timestamp": "2025-09-16T12:00:00Z"
 * }
 * }</pre>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final String ERROR_BASE = "https://gateway.dev/errors/";

    @ExceptionHandler(TenantIdMissingException.class)
    public ProblemDetail handleTenantIdMissing(TenantIdMissingException ex) {
        return problem(HttpStatus.UNAUTHORIZED, "missing-tenant-id",
                "Missing Tenant ID", ex.getMessage());
    }

    @ExceptionHandler(TenantIdInvalidException.class)
    public ProblemDetail handleTenantIdInvalid(TenantIdInvalidException ex) {
        return problem(HttpStatus.UNAUTHORIZED, "invalid-tenant-id",
                "Invalid Tenant ID", ex.getMessage());
    }

    @ExceptionHandler(EntityNotFoundException.class)
    public ProblemDetail handleNotFound(EntityNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, "not-found",
                "Resource Not Found", ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> fieldErrors = ex.getBindingResult().getFieldErrors()
                .stream()
                .collect(Collectors.toMap(
                        FieldError::getField,
                        fe -> fe.getDefaultMessage() != null ? fe.getDefaultMessage() : "invalid",
                        (existing, __) -> existing // keep first on collision
                ));

        ProblemDetail detail = problem(HttpStatus.BAD_REQUEST, "validation-error",
                "Validation Failed", "One or more fields are invalid");
        detail.setProperty("fieldErrors", fieldErrors);
        return detail;
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleGeneric(Exception ex) {
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "internal-error",
                "Internal Server Error",
                "An unexpected error occurred. Please try again later.");
    }

    private ProblemDetail problem(HttpStatus status, String errorCode,
            String title, String detail) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setType(URI.create(ERROR_BASE + errorCode));
        pd.setTitle(title);
        pd.setProperty("timestamp", Instant.now());
        return pd;
    }
}
