package dev.traceback.search;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;
import org.springframework.http.*;
@RestController
@RequestMapping("/api/v1/organizations/{organizationId}")
public class SearchController {
    private final SearchService service;
    public SearchController(SearchService service) {this.service=service;}
    @GetMapping("/search") public ResponseEntity<SearchService.Response> search(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID organizationId,
        @RequestParam String q,@RequestParam(defaultValue="10") int limit,@RequestParam(defaultValue="hybrid") SearchService.Mode mode) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.search(jwt,organizationId,q,limit,mode));
    }
    @GetMapping("/documents/{documentId}/revisions/{revisionId}/indexing") public SearchRepository.IndexStatus status(@AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID organizationId,@PathVariable UUID documentId,@PathVariable UUID revisionId) {return service.status(jwt,organizationId,documentId,revisionId);}
}
