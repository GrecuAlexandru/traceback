package dev.traceback.search;
import dev.traceback.indexing.EmbeddingFailure;
import dev.traceback.knowledge.DocumentException;
import dev.traceback.organization.*;
import jakarta.validation.ConstraintViolationException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
@RestControllerAdvice(assignableTypes=SearchController.class)
public class SearchExceptionHandler extends ResponseEntityExceptionHandler {
    @ExceptionHandler({OrganizationAccessDeniedException.class,OrganizationPermissionDeniedException.class})
    public ResponseEntity<ProblemDetail> denied(RuntimeException ex){return problem(HttpStatus.FORBIDDEN,"Organization access denied.");}
    @ExceptionHandler({ConstraintViolationException.class,IllegalArgumentException.class})
    public ResponseEntity<ProblemDetail> invalid(RuntimeException ex){return problem(HttpStatus.BAD_REQUEST,"Invalid search request.");}
    @ExceptionHandler({EmbeddingFailure.class,DataAccessException.class})
    public ResponseEntity<ProblemDetail> unavailable(RuntimeException ex){return problem(HttpStatus.SERVICE_UNAVAILABLE,"Search dependency unavailable.");}
    @ExceptionHandler(DocumentException.class)
    public ResponseEntity<ProblemDetail> missing(DocumentException ex){return problem(ex.status(),ex.getMessage());}
    private ResponseEntity<ProblemDetail> problem(HttpStatus status,String detail){return ResponseEntity.status(status).body(ProblemDetail.forStatusAndDetail(status,detail));}
}
