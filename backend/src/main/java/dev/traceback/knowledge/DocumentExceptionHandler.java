package dev.traceback.knowledge;

import dev.traceback.organization.OrganizationAccessDeniedException;
import dev.traceback.organization.OrganizationPermissionDeniedException;
import jakarta.validation.ConstraintViolationException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice(assignableTypes = DocumentController.class)
public class DocumentExceptionHandler extends ResponseEntityExceptionHandler {
    @ExceptionHandler(DocumentException.class)
    public ResponseEntity<ProblemDetail> document(DocumentException exception) { return problem(exception.status(), exception.getMessage()); }
    @ExceptionHandler({OrganizationAccessDeniedException.class, OrganizationPermissionDeniedException.class})
    public ResponseEntity<ProblemDetail> denied(RuntimeException exception) { return problem(HttpStatus.FORBIDDEN, exception.getMessage()); }
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ProblemDetail> invalid(ConstraintViolationException exception) { return problem(HttpStatus.BAD_REQUEST, "Invalid document request."); }
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ProblemDetail> database(DataAccessException exception) {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "Document metadata is unavailable.");
    }
    private ResponseEntity<ProblemDetail> problem(HttpStatus status, String detail) {
        return ResponseEntity.status(status).body(ProblemDetail.forStatusAndDetail(status, detail));
    }
}
