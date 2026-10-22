package dev.comfybatch.api;

import dev.comfybatch.api.ApiModels.ErrorResponse;
import dev.comfybatch.domain.NotFoundException;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ErrorResponse> notFound(NotFoundException exception) {
        return response(404, "NOT_FOUND", exception.getMessage(), List.of());
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ErrorResponse> rejected(ResponseStatusException exception) {
        int status = exception.getStatusCode().value();
        String code =
                switch (status) {
                    case 401 -> "UNAUTHORIZED";
                    case 409 -> "CONFLICT";
                    default -> "INVALID_REQUEST";
                };
        return response(
                status,
                code,
                Objects.toString(exception.getReason(), "Request rejected"),
                List.of());
    }

    @ExceptionHandler({IllegalArgumentException.class, ArithmeticException.class})
    public ResponseEntity<ErrorResponse> invalid(RuntimeException exception) {
        LOG.debug("Invalid request: {}", exception.toString());
        String detail =
                exception instanceof ArithmeticException
                        ? "Numeric value outside supported range"
                        : "Invalid request value or JSON";
        return response(400, "INVALID_REQUEST", "Invalid request", List.of(detail));
    }

    @ExceptionHandler({
        HttpMessageNotReadableException.class,
        MethodArgumentTypeMismatchException.class
    })
    public ResponseEntity<ErrorResponse> malformed(Exception exception) {
        LOG.debug("Request binding failed: {}", exception.toString());
        return response(
                400,
                "INVALID_REQUEST",
                "Invalid request",
                List.of("Malformed JSON or parameter type"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> unexpected(Exception exception) {
        if (exception instanceof org.springframework.web.ErrorResponse frameworkError) {
            return response(
                    frameworkError.getStatusCode().value(),
                    "REQUEST_ERROR",
                    "Request rejected",
                    List.of(exception.getClass().getSimpleName()));
        }
        LOG.error("Request failed", exception);
        String detail =
                exception instanceof DataAccessException
                        ? "Database operation failed"
                        : "Unexpected server error";
        return response(500, "INTERNAL_ERROR", "Request could not be completed", List.of(detail));
    }

    private ResponseEntity<ErrorResponse> response(
            int status, String code, String message, List<String> details) {
        return ResponseEntity.status(status).body(new ErrorResponse(code, message, details));
    }
}
