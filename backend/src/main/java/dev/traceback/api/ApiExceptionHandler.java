package dev.traceback.api;

import dev.traceback.incident.IncidentVersionConflictException;

import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler({
            IncidentVersionConflictException.class,
            OptimisticLockingFailureException.class
    })
    public ResponseEntity<ProblemDetail> handleVersionConflict(RuntimeException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "This incident has changed. Reload it and try again.");

        problem.setTitle("Incident Version Conflict");

        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }
}
