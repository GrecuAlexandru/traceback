package dev.traceback.rag;

import dev.traceback.organization.*;
import jakarta.validation.ConstraintViolationException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice(assignableTypes=ConversationController.class)
public class ChatExceptionHandler extends ResponseEntityExceptionHandler {
    @ExceptionHandler(ChatFailure.class) public ResponseEntity<ProblemDetail> chat(ChatFailure ex) {var problem=ProblemDetail.forStatusAndDetail(ex.status(),"Chat request could not be completed.");problem.setProperty("code",ex.code());return ResponseEntity.status(ex.status()).cacheControl(CacheControl.noStore()).body(problem);}
    @ExceptionHandler({OrganizationAccessDeniedException.class,OrganizationPermissionDeniedException.class}) public ResponseEntity<ProblemDetail> denied(RuntimeException ex) {return problem(HttpStatus.FORBIDDEN,"Organization access denied.");}
    @ExceptionHandler({IllegalArgumentException.class,ConstraintViolationException.class}) public ResponseEntity<ProblemDetail> invalid(RuntimeException ex) {return problem(HttpStatus.BAD_REQUEST,"Invalid chat request.");}
    @ExceptionHandler(DataAccessException.class) public ResponseEntity<ProblemDetail> database(RuntimeException ex) {return problem(HttpStatus.SERVICE_UNAVAILABLE,"Chat persistence unavailable.");}
    private ResponseEntity<ProblemDetail> problem(HttpStatus status,String detail) {return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(ProblemDetail.forStatusAndDetail(status,detail));}
}
