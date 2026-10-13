package dev.traceback.knowledge;

import dev.traceback.knowledge.dto.*;
import dev.traceback.security.HumanIdentityPolicy.Identity;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

@Repository
public class DocumentRepository {
    private final NamedParameterJdbcTemplate jdbc;
    public DocumentRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    private static final String READ = """
            d.organization_id = :org AND EXISTS (SELECT 1 FROM organization_memberships m
            JOIN app_users u ON u.id = m.user_id WHERE m.organization_id = d.organization_id
            AND u.issuer = :issuer AND u.subject = :subject)
            """;
    private static final String WRITE = """
            EXISTS (SELECT 1 FROM organization_memberships m JOIN app_users u ON u.id = m.user_id
            WHERE m.organization_id = :org AND u.issuer = :issuer AND u.subject = :subject
            AND m.role IN ('ADMIN', 'ENGINEER'))
            """;
    private MapSqlParameterSource scope(UUID org, Identity identity) {
        return new MapSqlParameterSource("org", org).addValue("issuer", identity.issuer()).addValue("subject", identity.subject());
    }

    public DocumentRow find(UUID org, UUID id, Identity identity, boolean lock) {
        var rows = jdbc.query("SELECT d.* FROM documents d WHERE d.id = :id AND " + READ + (lock ? " FOR UPDATE OF d" : ""),
                scope(org, identity).addValue("id", id), this::document);
        if (rows.isEmpty()) throw DocumentException.missing();
        return rows.getFirst();
    }
    public List<DocumentRow> list(UUID org, Identity identity, DocumentStatus status, int page, int size) {
        return jdbc.query("SELECT d.* FROM documents d WHERE " + READ
                        + " AND d.status = :status ORDER BY d.created_at DESC, d.id DESC LIMIT :size OFFSET :offset",
                scope(org, identity).addValue("status", status.name()).addValue("size", size).addValue("offset", (long)page * size), this::document);
    }
    public long count(UUID org, Identity identity, DocumentStatus status) {
        return jdbc.queryForObject("SELECT count(*) FROM documents d WHERE " + READ + " AND d.status = :status",
                scope(org, identity).addValue("status", status.name()), Long.class);
    }
    public RevisionRow revision(UUID org, UUID document, UUID id, Identity identity) {
        var rows = jdbc.query("SELECT r.* FROM document_revisions r JOIN documents d ON d.id = r.document_id AND d.organization_id = r.organization_id"
                        + " WHERE r.id = :id AND d.id = :document AND d.status <> 'DELETED' AND " + READ,
                scope(org, identity).addValue("id", id).addValue("document", document), this::revision);
        if (rows.isEmpty()) throw DocumentException.missing();
        return rows.getFirst();
    }
    public List<RevisionRow> revisions(UUID org, UUID document, Identity identity, int page, int size) {
        return jdbc.query("SELECT r.* FROM document_revisions r JOIN documents d ON d.id = r.document_id AND d.organization_id = r.organization_id"
                        + " WHERE d.id = :document AND d.status <> 'DELETED' AND " + READ
                        + " ORDER BY r.revision_number DESC LIMIT :size OFFSET :offset",
                scope(org, identity).addValue("document", document).addValue("size", size).addValue("offset", (long)page * size), this::revision);
    }
    public long revisionCount(UUID org, UUID document, Identity identity) {
        return jdbc.queryForObject("SELECT count(*) FROM document_revisions r JOIN documents d ON d.id = r.document_id AND d.organization_id = r.organization_id"
                        + " WHERE d.id = :document AND d.status <> 'DELETED' AND " + READ,
                scope(org, identity).addValue("document", document), Long.class);
    }
    public void insert(UUID org, UUID id, String title, Identity identity) {
        int changed = jdbc.update("""
                INSERT INTO documents (id, organization_id, title, created_by, status)
                SELECT :id, :org, :title, u.id, 'UPLOADING' FROM app_users u
                WHERE u.issuer = :issuer AND u.subject = :subject AND
                """ + WRITE, scope(org, identity).addValue("id", id).addValue("title", title));
        requireWrite(changed);
    }
    public RevisionRow reserve(UUID org, UUID document, UUID revision, UUID key,
                               DocumentUploadValidator.ValidatedUpload upload, Identity identity) {
        int changed = jdbc.update("""
                INSERT INTO document_revisions (id, organization_id, document_id, revision_number,
                  original_filename, media_type, file_size, checksum, storage_key, uploaded_by, status)
                SELECT :revision, :org, d.id,
                  COALESCE((SELECT max(r.revision_number) FROM document_revisions r WHERE r.document_id = d.id AND r.organization_id = :org), 0) + 1,
                  :filename, :media, :size, :checksum, :key, u.id, 'UPLOADING'
                FROM documents d JOIN app_users u ON u.issuer = :issuer AND u.subject = :subject
                WHERE d.id = :document AND d.organization_id = :org AND d.status <> 'DELETED' AND
                """ + WRITE, scope(org, identity).addValue("document", document).addValue("revision", revision).addValue("key", key)
                .addValue("filename", upload.filename()).addValue("media", upload.mediaType())
                .addValue("size", upload.bytes().length).addValue("checksum", upload.checksum()));
        requireWrite(changed);
        touch(org, document, identity, null, null, false);
        return revision(org, document, revision, identity);
    }
    public boolean pending(UUID org, UUID document, Identity identity) {
        return jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM document_revisions r JOIN documents d ON d.id = r.document_id AND d.organization_id = r.organization_id"
                        + " WHERE d.id = :document AND r.status = 'UPLOADING' AND " + READ + ")",
                scope(org, identity).addValue("document", document), Boolean.class);
    }
    public void publish(UUID org, UUID document, UUID revision, Identity identity) {
        requireWrite(jdbc.update("UPDATE document_revisions SET status = 'AVAILABLE' WHERE organization_id = :org"
                        + " AND document_id = :document AND id = :revision AND status = 'UPLOADING' AND " + WRITE,
                scope(org, identity).addValue("document", document).addValue("revision", revision)));
        requireWrite(jdbc.update("UPDATE documents SET status = 'AVAILABLE', current_revision_id = :revision,"
                        + " version = version + 1, modified_at = clock_timestamp() WHERE organization_id = :org AND id = :document AND status <> 'DELETED' AND " + WRITE,
                scope(org, identity).addValue("document", document).addValue("revision", revision)));
    }
    public void touch(UUID org, UUID id, Identity identity, String title, Long version, boolean delete) {
        requireWrite(jdbc.update("UPDATE documents SET version = version + 1, modified_at = clock_timestamp()"
                        + (title != null ? ", title = :title" : "") + (delete ? ", status = 'DELETED'" : "")
                        + " WHERE organization_id = :org AND id = :id" + (version != null ? " AND version = :version" : "") + " AND " + WRITE,
                scope(org, identity).addValue("id", id).addValue("title", title).addValue("version", version)));
    }
    private void requireWrite(int changed) {
        if (changed != 1) throw new dev.traceback.organization.OrganizationPermissionDeniedException();
    }
    private DocumentRow document(ResultSet r, int row) throws SQLException {
        return new DocumentRow(r.getObject("id", UUID.class), r.getObject("organization_id", UUID.class), r.getString("title"),
                r.getObject("created_by", UUID.class), DocumentStatus.valueOf(r.getString("status")), r.getLong("version"),
                r.getObject("current_revision_id", UUID.class), r.getTimestamp("created_at").toInstant(), r.getTimestamp("modified_at").toInstant());
    }
    private RevisionRow revision(ResultSet r, int row) throws SQLException {
        return new RevisionRow(r.getObject("id", UUID.class), r.getObject("organization_id", UUID.class),
                r.getObject("document_id", UUID.class), r.getInt("revision_number"), r.getString("original_filename"),
                r.getString("media_type"), r.getLong("file_size"), r.getString("checksum"), r.getObject("uploaded_by", UUID.class),
                r.getString("status"), r.getTimestamp("created_at").toInstant(), r.getObject("storage_key", UUID.class));
    }
    public record DocumentRow(UUID id, UUID organizationId, String title, UUID createdBy, DocumentStatus status,
                              long version, UUID currentRevisionId, java.time.Instant createdAt, java.time.Instant modifiedAt) {
        public DocumentResponse response(RevisionResponse current) {
            return new DocumentResponse(id, organizationId, title, createdBy, status, version, createdAt, modifiedAt, current);
        }
    }
    public record RevisionRow(UUID id, UUID organizationId, UUID documentId, int revisionNumber,
                              String originalFilename, String mediaType, long fileSize, String checksum,
                              UUID uploadedBy, String status, java.time.Instant createdAt, UUID storageKey) {
        public RevisionResponse metadata() {
            return new RevisionResponse(id, organizationId, documentId, revisionNumber, originalFilename,
                    mediaType, fileSize, checksum, uploadedBy, status, createdAt);
        }
    }
}
