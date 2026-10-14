package dev.traceback.ingestion;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Internal maintenance capability. Never exposed directly through HTTP. */
@Repository
public class IngestionRepository {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final IngestionSettings settings;
    public IngestionRepository(JdbcTemplate jdbc, PlatformTransactionManager manager, IngestionSettings settings) {
        this.jdbc = jdbc; this.settings = settings;
        tx = new TransactionTemplate(manager);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setTimeout(10);
    }

    /** Caller owns publication transaction and document lock. */
    public void enqueue(UUID org, UUID document, UUID revision) {
        cancel(org, document, revision);
        UUID job = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO ingestion_jobs(id, organization_id, document_id, revision_id)
            SELECT ?, r.organization_id, r.document_id, r.id FROM document_revisions r
            JOIN documents d ON d.organization_id=r.organization_id AND d.id=r.document_id
            WHERE r.organization_id=? AND r.document_id=? AND r.id=? AND r.status='AVAILABLE'
            AND d.status='AVAILABLE' AND d.current_revision_id=r.id
            """, job, org, document, revision);
        jdbc.update("""
            INSERT INTO ingestion_outbox(id,organization_id,document_id,revision_id,job_id,generation)
            SELECT ?,organization_id,document_id,revision_id,id,generation FROM ingestion_jobs WHERE id=?
            """, UUID.randomUUID(), job);
    }
    /** Caller owns deletion/publication transaction; document lock precedes job locks. */
    public void cancel(UUID org, UUID document, UUID keepRevision) {
        jdbc.update("""
            UPDATE ingestion_jobs SET status='CANCELLED', lease_token=NULL, lease_until=NULL, updated_at=clock_timestamp()
            WHERE organization_id=? AND document_id=? AND (?::uuid IS NULL OR revision_id<>?)
            AND status<>'CANCELLED'
            """, org, document, keepRevision, keepRevision);
    }

    public Event claimEvent() {
        return tx.execute(t -> {
            var events = jdbc.query("""
                SELECT * FROM ingestion_outbox WHERE delivered_at IS NULL AND next_attempt_at<=clock_timestamp()
                AND (lease_until IS NULL OR lease_until<=clock_timestamp())
                ORDER BY next_attempt_at, id LIMIT 1 FOR UPDATE SKIP LOCKED
                """, this::event);
            if (events.isEmpty()) return null;
            Event event = events.getFirst(); UUID token = UUID.randomUUID();
            jdbc.update("""
                UPDATE ingestion_outbox SET lease_token=?, lease_until=clock_timestamp()+make_interval(secs=>?),
                publish_attempts=publish_attempts+1 WHERE id=?
                """, token, settings.leaseSeconds(), event.message().event());
            return new Event(event.message(), event.generation(), event.attempts()+1, token);
        });
    }
    public boolean delivered(Event event) {
        return tx.execute(t -> jdbc.update("""
            UPDATE ingestion_outbox SET delivered_at=clock_timestamp(),lease_token=NULL,lease_until=NULL,error_code=NULL
            WHERE id=? AND lease_token=? AND lease_until>clock_timestamp()
            """, event.message().event(), event.token()) == 1);
    }
    public void publishFailed(Event event, int backoff) {
        tx.executeWithoutResult(t -> jdbc.update("""
            UPDATE ingestion_outbox SET lease_token=NULL,lease_until=NULL,error_code='QUEUE_UNAVAILABLE',
            next_attempt_at=clock_timestamp()+make_interval(secs=>?) WHERE id=? AND lease_token=?
            """, backoff, event.message().event(), event.token()));
    }
    private Event event(ResultSet r, int n) throws SQLException {
        return new Event(new IngestionMessage(uuid(r,"id"),uuid(r,"job_id"),uuid(r,"organization_id"),uuid(r,"document_id"),uuid(r,"revision_id")),
                r.getInt("generation"),r.getInt("publish_attempts"),uuid(r,"lease_token"));
    }

    public Claim claim(IngestionMessage message) {
        return tx.execute(t -> {
            // Match EVERY reference before accessing content or mutating a job. Payload org is never authority.
            var events = jdbc.query("SELECT * FROM ingestion_outbox WHERE id=?", this::event, message.event());
            if (events.isEmpty() || !events.getFirst().message().equals(message)) return new Claim("INVALID", null);
            Event event = events.getFirst();
            jdbc.queryForObject("SELECT id FROM documents WHERE organization_id=? AND id=? FOR UPDATE", UUID.class,
                    event.message().organization(), event.message().document());
            Job job = find(event.message().organization(), event.message().document(), event.message().revision(), true);
            if (job.generation()!=event.generation()) return new Claim("OBSOLETE", null);
            if (job.status().equals("VERIFIED") || job.status().equals("CANCELLED")) return new Claim("ACK", null);
            if (job.status().equals("FAILED")) return new Claim("FAILED", null);
            if (!eligible(job)) {
                cancel(job.organization(), job.document(), null);
                return new Claim("ACK", null);
            }
            boolean due = jdbc.queryForObject("""
                SELECT next_attempt_at<=clock_timestamp() AND (lease_until IS NULL OR lease_until<=clock_timestamp())
                FROM ingestion_jobs WHERE id=?
                """, Boolean.class, job.id());
            if (!due) return new Claim("BUSY", null);
            if (job.attempts() >= settings.maxAttempts()) {
                terminal(job, "RETRIES_EXHAUSTED");
                return new Claim("FAILED", null);
            }
            UUID token = UUID.randomUUID();
            jdbc.update("""
                UPDATE ingestion_jobs SET status='RUNNING',attempts=attempts+1,lease_token=?,
                lease_until=clock_timestamp()+make_interval(secs=>?),updated_at=clock_timestamp() WHERE id=?
                """, token, settings.leaseSeconds(), job.id());
            return new Claim("CLAIMED", find(job.organization(),job.document(),job.revision(),false));
        });
    }
    private boolean eligible(Job job) {
        return jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM documents d JOIN document_revisions r
            ON r.organization_id=d.organization_id AND r.document_id=d.id
            WHERE d.organization_id=? AND d.id=? AND d.status='AVAILABLE' AND d.current_revision_id=?
            AND r.id=? AND r.status='AVAILABLE')
            """, Boolean.class, job.organization(),job.document(),job.revision(),job.revision());
    }
    /** Fenced completion after bounded I/O; deletion/supersession always wins. */
    public boolean finish(Job claimed, String failure, boolean permanent, long duration, int backoff) {
        return tx.execute(t -> {
            jdbc.queryForObject("SELECT id FROM documents WHERE organization_id=? AND id=? FOR UPDATE", UUID.class,
                    claimed.organization(),claimed.document());
            Job current = find(claimed.organization(),claimed.document(),claimed.revision(),true);
            if (current.status().equals("CANCELLED")) return true;
            if (!eligible(current)) { cancel(current.organization(),current.document(),null); return true; }
            if (!current.status().equals("RUNNING") || !claimed.token().equals(current.token())) return false;
            boolean live = jdbc.queryForObject("SELECT lease_until>clock_timestamp() FROM ingestion_jobs WHERE id=?",Boolean.class,current.id());
            if (!live) return false;
            if (failure == null) {
                jdbc.update("""
                    UPDATE ingestion_jobs SET status='VERIFIED',verified_at=clock_timestamp(),processing_ms=?,error_code=NULL,
                    lease_token=NULL,lease_until=NULL,updated_at=clock_timestamp() WHERE id=?
                    """, duration, current.id());
            } else if (permanent || current.attempts()>=settings.maxAttempts()) {
                terminal(current, permanent ? failure : "RETRIES_EXHAUSTED");
            } else {
                jdbc.update("""
                    UPDATE ingestion_jobs SET status='RETRY',error_code=?,processing_ms=?,lease_token=NULL,lease_until=NULL,
                    next_attempt_at=clock_timestamp()+make_interval(secs=>?),updated_at=clock_timestamp() WHERE id=?
                    """, failure, duration, backoff, current.id());
            }
            return true;
        });
    }
    private void terminal(Job job, String code) {
        jdbc.update("""
            UPDATE ingestion_jobs SET status='FAILED',error_code=?,lease_token=NULL,lease_until=NULL,
            updated_at=clock_timestamp() WHERE id=?
            """,code,job.id());
    }
    public Job find(UUID org, UUID document, UUID revision, boolean lock) {
        var rows = jdbc.query("""
            SELECT j.*,r.storage_provider,r.storage_key,r.file_size,r.checksum FROM ingestion_jobs j
            JOIN document_revisions r ON r.organization_id=j.organization_id AND r.document_id=j.document_id AND r.id=j.revision_id
            WHERE j.organization_id=? AND j.document_id=? AND j.revision_id=?
            """ + (lock ? " FOR UPDATE OF j" : ""), this::job,org,document,revision);
        return rows.isEmpty() ? null : rows.getFirst();
    }
    private Job job(ResultSet r,int n) throws SQLException {
        return new Job(uuid(r,"id"),uuid(r,"organization_id"),uuid(r,"document_id"),uuid(r,"revision_id"),r.getInt("generation"),
                r.getString("status"),r.getInt("attempts"),uuid(r,"lease_token"),r.getString("storage_provider"),uuid(r,"storage_key"),
                r.getLong("file_size"),r.getString("checksum"),r.getString("error_code"),
                r.getTimestamp("verified_at")==null ? null : r.getTimestamp("verified_at").toInstant(), (Long)r.getObject("processing_ms"));
    }
    private static UUID uuid(ResultSet r,String column) throws SQLException { return r.getObject(column,UUID.class); }

    /** Rearm lost/expired deliveries; never reset a live lease, terminal job, or prior generation. */
    public int reconcile() {
        return tx.execute(t -> jdbc.update("""
            UPDATE ingestion_outbox o SET delivered_at=NULL,next_attempt_at=clock_timestamp(),error_code='DELIVERY_RECOVERY'
            FROM ingestion_jobs j WHERE j.id=o.job_id AND j.generation=o.generation AND o.delivered_at IS NOT NULL
            AND o.lease_token IS NULL AND o.delivered_at<clock_timestamp()-make_interval(secs=>?) AND j.status IN ('PENDING','RETRY','RUNNING')
            AND j.next_attempt_at<clock_timestamp()-make_interval(secs=>?)
            AND j.updated_at<clock_timestamp()-make_interval(secs=>?)
            AND (j.lease_until IS NULL OR j.lease_until<clock_timestamp())
            """,settings.recoverySeconds(),settings.recoverySeconds(),settings.recoverySeconds()));
    }
    public long pendingEvents() { return jdbc.queryForObject("SELECT count(*) FROM ingestion_outbox WHERE delivered_at IS NULL",Long.class); }
    public long expiredLeases() { return jdbc.queryForObject("SELECT count(*) FROM ingestion_jobs WHERE status='RUNNING' AND lease_until<clock_timestamp()",Long.class); }
    public long failedJobs() { return jdbc.queryForObject("SELECT count(*) FROM ingestion_jobs WHERE status='FAILED'",Long.class); }
    public long publicationFailures() { return jdbc.queryForObject("SELECT count(*) FROM ingestion_outbox WHERE error_code='QUEUE_UNAVAILABLE'",Long.class); }
    public List<Event> events() { return jdbc.query("SELECT * FROM ingestion_outbox ORDER BY created_at,id",this::event); }
    public record Event(IngestionMessage message,int generation,int attempts,UUID token) {}
    public record Claim(String outcome,Job job) {}
    public record Job(UUID id,UUID organization,UUID document,UUID revision,int generation,String status,int attempts,
                      UUID token,String provider,UUID key,long size,String checksum,String errorCode,Instant verifiedAt,Long processingMs) {
        public Status response() { return new Status(status,attempts,errorCode,verifiedAt,processingMs); }
    }
    public record Status(String status,int attempts,String errorCode,Instant verifiedAt,Long processingMs) {}
}
