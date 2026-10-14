package dev.traceback.knowledge;

import dev.traceback.knowledge.dto.*;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/documents")
public class DocumentController {
    private final DocumentService service;
    public DocumentController(DocumentService service) { this.service = service; }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DocumentResponse> upload(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID organizationId,
                                                   @RequestParam String title, @RequestPart MultipartFile file) {
        var document = service.upload(jwt, organizationId, title, file);
        return ResponseEntity.created(URI.create("/api/v1/organizations/" + organizationId + "/documents/" + document.id())).body(document);
    }
    @GetMapping
    public DocumentPageResponse<DocumentResponse> list(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID organizationId,
            @RequestParam(required = false) DocumentStatus status, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.list(jwt, organizationId, status, page, size);
    }
    @GetMapping("/{id}")
    public DocumentResponse metadata(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID organizationId, @PathVariable UUID id) {
        return service.metadata(jwt, organizationId, id);
    }
    @PatchMapping("/{id}")
    public DocumentResponse update(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID organizationId, @PathVariable UUID id,
                                   @Valid @RequestBody UpdateDocumentRequest request) { return service.update(jwt, organizationId, id, request); }
    @PostMapping(value = "/{id}/revisions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DocumentResponse> revise(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID organizationId,
            @PathVariable UUID id, @RequestParam long version, @RequestPart MultipartFile file) {
        var result = service.revise(jwt, organizationId, id, version, file);
        return ResponseEntity.created(URI.create("/api/v1/organizations/" + organizationId + "/documents/" + id
                + "/revisions/" + result.currentRevision().id() + "/content")).body(result);
    }
    @GetMapping("/{id}/revisions")
    public DocumentPageResponse<RevisionResponse> revisions(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID organizationId,
            @PathVariable UUID id, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return service.revisions(jwt, organizationId, id, page, size);
    }
    @GetMapping("/{id}/content")
    public ResponseEntity<byte[]> download(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID organizationId, @PathVariable UUID id) {
        return content(service.download(jwt, organizationId, id, null));
    }
    @GetMapping("/{id}/revisions/{revisionId}/content")
    public ResponseEntity<byte[]> revisionContent(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID organizationId,
            @PathVariable UUID id, @PathVariable UUID revisionId) { return content(service.download(jwt, organizationId, id, revisionId)); }
    @GetMapping("/{id}/revisions/{revisionId}/ingestion")
    public dev.traceback.ingestion.IngestionRepository.Status ingestion(@AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID organizationId, @PathVariable UUID id, @PathVariable UUID revisionId) {
        return service.ingestionStatus(jwt, organizationId, id, revisionId);
    }
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID organizationId,
            @PathVariable UUID id, @RequestParam long version) {
        service.delete(jwt, organizationId, id, version);
        return ResponseEntity.noContent().build();
    }
    private ResponseEntity<byte[]> content(DocumentService.Download download) {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(download.metadata().mediaType()))
                .contentLength(download.bytes().length)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(download.metadata().originalFilename(), StandardCharsets.UTF_8).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store").header("X-Content-Type-Options", "nosniff")
                .body(download.bytes());
    }
}
