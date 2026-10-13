package dev.traceback.incident;

import dev.traceback.organization.OrganizationAccessDeniedException;
import dev.traceback.organization.OrganizationPermissionDeniedException;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice(assignableTypes = IncidentController.class)
public class IncidentExceptionHandler extends ResponseEntityExceptionHandler {
    @ExceptionHandler({OrganizationAccessDeniedException.class, OrganizationPermissionDeniedException.class})
    public ResponseEntity<ProblemDetail> denied(RuntimeException exception) {
        return problem(HttpStatus.FORBIDDEN, exception.getMessage());
    }

    @ExceptionHandler(IncidentNotFoundException.class)
    public ResponseEntity<ProblemDetail> notFound(IncidentNotFoundException exception) {
        return problem(HttpStatus.NOT_FOUND, exception.getMessage());
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ProblemDetail> invalidRequest(ConstraintViolationException exception) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid incident request.");
    }

    private ResponseEntity<ProblemDetail> problem(HttpStatus status, String detail) {
        return ResponseEntity.status(status).body(ProblemDetail.forStatusAndDetail(status, detail));
    }
}
