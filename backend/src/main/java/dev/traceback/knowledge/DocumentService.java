package dev.traceback.knowledge;

import dev.traceback.knowledge.dto.*;
import dev.traceback.knowledge.storage.DocumentStorage;
import dev.traceback.organization.OrganizationAuthorizationService;
import dev.traceback.security.HumanIdentityPolicy.Identity;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.multipart.MultipartFile;

import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.util.UUID;

@Service
@Validated
public class DocumentService {
    private final DocumentRepository repository;
    private final OrganizationAuthorizationService authorization;
    private final DocumentUploadValidator validator;
    private final DocumentStorage storage;
    private final DocumentUploadRecovery recovery;
    private final TransactionTemplate write;
    private final TransactionTemplate read;

    public DocumentService(DocumentRepository repository, OrganizationAuthorizationService authorization,
                           DocumentUploadValidator validator, DocumentStorage storage, DocumentUploadRecovery recovery,
                           PlatformTransactionManager manager) {
        this.repository = repository;
        this.authorization = authorization;
        this.validator = validator;
        this.storage = storage;
        this.recovery = recovery;
        write = new TransactionTemplate(manager);
        write.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        write.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        read = new TransactionTemplate(manager);
        read.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        read.setReadOnly(true);
    }

    public DocumentResponse upload(Jwt jwt, UUID org, @NotBlank @Size(max = 200) String title, @NotNull MultipartFile file) {
        // Authorize before expensive content parsing, then again in the reservation transaction.
        read.execute(tx -> access(jwt, org, true));
        var content = validator.validate(file);
        UUID document = UUID.randomUUID();
        var attempt = write.execute(tx -> {
            Identity identity = access(jwt, org, true);
            repository.insert(org, document, title.strip(), identity);
            return repository.reserve(org, document, UUID.randomUUID(), UUID.randomUUID(), content, identity);
        });
        return storeAndPublish(jwt, org, document, attempt, content);
    }

    public DocumentResponse revise(Jwt jwt, UUID org, UUID document, @PositiveOrZero long version, @NotNull MultipartFile file) {
        read.execute(tx -> {
            Identity identity = access(jwt, org, true);
            active(repository.find(org, document, identity, false));
            return null;
        });
        var content = validator.validate(file);
        var attempt = write.execute(tx -> {
            Identity identity = access(jwt, org, true);
            var current = repository.find(org, document, identity, true);
            active(current);
            mutable(current, org, identity, version);
            return repository.reserve(org, document, UUID.randomUUID(), UUID.randomUUID(), content, identity);
        });
        return storeAndPublish(jwt, org, document, attempt, content);
    }

    private DocumentResponse storeAndPublish(Jwt jwt, UUID org, UUID document, DocumentRepository.RevisionRow attempt,
                                              DocumentUploadValidator.ValidatedUpload content) {
        try {
            storage.put(attempt.storageKey(), content.bytes());
            return write.execute(tx -> {
                Identity identity = access(jwt, org, true);
                active(repository.find(org, document, identity, true));
                repository.publish(org, document, attempt.metadata().id(), identity);
                return response(repository.find(org, document, identity, false), identity);
            });
        } catch (IOException | RuntimeException ex) {
            try {
                // If commit succeeded but acknowledgement failed, preserve AVAILABLE content.
                // If DB is unavailable, retain private objects and the pending record for recovery.
                if (recovery.fail(org, document, attempt.metadata().id())) storage.delete(attempt.storageProvider(), attempt.storageKey());
            } catch (IOException | RuntimeException cleanupFailure) {
                // No paths/content/credentials in the HTTP error. Recovery retries on restart.
                LoggerFactory.getLogger(DocumentService.class).warn("Document upload cleanup requires startup recovery");
            }
            if (ex instanceof dev.traceback.organization.OrganizationAccessDeniedException denied) throw denied;
            if (ex instanceof dev.traceback.organization.OrganizationPermissionDeniedException denied) throw denied;
            throw DocumentException.storage();
        }
    }

    public DocumentResponse metadata(Jwt jwt, UUID org, UUID document) {
        return read.execute(tx -> {
            Identity identity = access(jwt, org, false);
            var row = repository.find(org, document, identity, false);
            active(row);
            return response(row, identity);
        });
    }

    public DocumentPageResponse<DocumentResponse> list(Jwt jwt, UUID org, DocumentStatus status,
                                                      @PositiveOrZero int page, @Min(1) @Max(100) int size) {
        return read.execute(tx -> {
            Identity identity = access(jwt, org, false);
            DocumentStatus filter = status == null ? DocumentStatus.AVAILABLE : status;
            return DocumentPageResponse.of(repository.list(org, identity, filter, page, size).stream()
                    .map(row -> response(row, identity)).toList(), page, size, repository.count(org, identity, filter));
        });
    }

    public DocumentPageResponse<RevisionResponse> revisions(Jwt jwt, UUID org, UUID document,
                                                            @PositiveOrZero int page, @Min(1) @Max(100) int size) {
        return read.execute(tx -> {
            Identity identity = access(jwt, org, false);
            active(repository.find(org, document, identity, false));
            return DocumentPageResponse.of(repository.revisions(org, document, identity, page, size).stream()
                    .map(DocumentRepository.RevisionRow::metadata).toList(), page, size, repository.revisionCount(org, document, identity));
        });
    }

    public Download download(Jwt jwt, UUID org, UUID document, UUID revision) {
        // READ_COMMITTED rechecks persisted membership and ownership in the scoped revision
        // query immediately before file I/O. Materialize bounded bytes here so Spring cannot
        // defer opening a file until after service authorization has completed.
        return write.execute(tx -> {
            Identity identity = access(jwt, org, false);
            var row = repository.find(org, document, identity, true);
            active(row);
            UUID selected = revision == null ? row.currentRevisionId() : revision;
            if (selected == null) throw DocumentException.missing();
            var content = repository.revision(org, document, selected, identity);
            if (!"AVAILABLE".equals(content.metadata().status())) throw DocumentException.missing();
            try {
                byte[] bytes = storage.read(content.storageProvider(), content.storageKey(), validator.maximumBytes());
                if (bytes.length != content.metadata().fileSize() || !DocumentUploadValidator.checksum(bytes).equals(content.metadata().checksum())) {
                    throw DocumentException.storage();
                }
                return new Download(content.metadata(), bytes);
            } catch (IOException ex) { throw DocumentException.storage(); }
        });
    }

    public dev.traceback.ingestion.IngestionRepository.Status ingestionStatus(Jwt jwt, UUID org, UUID document, UUID revision) {
        return read.execute(tx -> {
            Identity identity = access(jwt, org, false);
            active(repository.find(org, document, identity, false));
            repository.revision(org, document, revision, identity);
            return repository.ingestionStatus(org, document, revision);
        });
    }

    public DocumentResponse update(Jwt jwt, UUID org, UUID document, @NotNull @Valid UpdateDocumentRequest request) {
        return write.execute(tx -> {
            Identity identity = access(jwt, org, true);
            var row = repository.find(org, document, identity, true);
            active(row);
            mutable(row, org, identity, request.version());
            if (!row.title().equals(request.title().strip())) repository.touch(org, document, identity, request.title().strip(), request.version(), false);
            return response(repository.find(org, document, identity, false), identity);
        });
    }

    public void delete(Jwt jwt, UUID org, UUID document, @PositiveOrZero long version) {
        write.executeWithoutResult(tx -> {
            Identity identity = access(jwt, org, true);
            var row = repository.find(org, document, identity, true);
            // Repeated DELETE is idempotent after authorization, irrespective of old version.
            if (row.status() == DocumentStatus.DELETED) return;
            mutable(row, org, identity, version);
            repository.touch(org, document, identity, null, version, true);
        });
    }

    private Identity access(Jwt jwt, UUID org, boolean writeAccess) {
        Identity identity = authorization.requireAccess(jwt, org, writeAccess);
        recovery.requireActiveRuntime();
        return identity;
    }

    private void active(DocumentRepository.DocumentRow row) { if (row.status() == DocumentStatus.DELETED) throw DocumentException.missing(); }
    private void mutable(DocumentRepository.DocumentRow row, UUID org, Identity identity, long version) {
        if (row.version() != version || repository.pending(org, row.id(), identity)) throw DocumentException.conflict();
    }
    private DocumentResponse response(DocumentRepository.DocumentRow row, Identity identity) {
        return row.response(row.currentRevisionId() == null || row.status() == DocumentStatus.DELETED ? null
                : repository.revision(row.organizationId(), row.id(), row.currentRevisionId(), identity).metadata());
    }
    public record Download(RevisionResponse metadata, byte[] bytes) {}
}
