package dev.traceback.indexing;

import dev.traceback.extraction.SourceBlock;
import dev.traceback.knowledge.DocumentUploadValidator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;

/** Internal processing capability; public reads remain in the authorized search repository. */
@Repository
public class IndexRepository {
    private final JdbcTemplate jdbc;private final TransactionTemplate tx;private final IndexSettings settings;
    private final JsonMapper json=JsonMapper.builder().build();
    public IndexRepository(JdbcTemplate jdbc,PlatformTransactionManager manager,IndexSettings settings) {
        this.jdbc=jdbc;this.settings=settings;tx=new TransactionTemplate(manager);tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);tx.setTimeout(10);
    }
    /** Caller holds document lock and verification completion transaction. */
    public void enqueue(UUID org,UUID document,UUID revision) {
        if(!settings.enabled()) return;
        jdbc.update("""
            INSERT INTO index_configurations(id,model,model_digest,dimensions,extractor_version,chunker_version,chunk_size,overlap,max_chunks,max_text,max_pages)
            VALUES (?,?,?,?,'pdfbox-3.0.8-text-v1','structure-v1',?,?,?,?,?) ON CONFLICT (id) DO NOTHING
            """,settings.configuration(),settings.model(),settings.digest(),settings.dimensions(),settings.chunkSize(),settings.overlap(),settings.maxChunks(),settings.maxText(),settings.maxPages());
        if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM index_generations WHERE revision_id=? AND configuration_id=? AND status<>'FAILED' AND status<>'CANCELLED')",Boolean.class,revision,settings.configuration()))) return;
        create(org,document,revision);
    }
    private UUID create(UUID org,UUID document,UUID revision) {
        UUID generation=UUID.randomUUID();
        int rows=jdbc.update("""
            INSERT INTO index_generations(id,organization_id,document_id,revision_id,configuration_id)
            SELECT ?,d.organization_id,d.id,r.id,? FROM documents d JOIN document_revisions r ON r.id=d.current_revision_id AND r.organization_id=d.organization_id AND r.document_id=d.id
            JOIN ingestion_jobs j ON j.revision_id=r.id AND j.organization_id=d.organization_id AND j.document_id=d.id
            WHERE d.organization_id=? AND d.id=? AND r.id=? AND d.status='AVAILABLE' AND r.status='AVAILABLE' AND j.status='VERIFIED'
            AND NOT EXISTS(SELECT 1 FROM index_generations g WHERE g.revision_id=r.id AND g.status IN ('PENDING_EXTRACTION','EXTRACTING','EXTRACTED','INDEXING','RETRY'))
            """,generation,settings.configuration(),org,document,revision);
        if(rows==0)return null;
        jdbc.update("""
            INSERT INTO ingestion_outbox(id,organization_id,document_id,revision_id,job_id,generation,event_type,index_generation_id)
            SELECT ?,organization_id,document_id,revision_id,id,generation,'INDEX',? FROM ingestion_jobs WHERE organization_id=? AND document_id=? AND revision_id=? AND status='VERIFIED'
            """,UUID.randomUUID(),generation,org,document,revision);
        return generation;
    }
    public UUID reindex(UUID org,UUID document,UUID revision) {
        if(!settings.enabled())throw new IllegalStateException("Indexing is disabled");
        return tx.execute(t->{lockDocument(org,document);enqueue(org,document,revision);
            var unfinished=jdbc.query("SELECT id FROM index_generations WHERE organization_id=? AND document_id=? AND revision_id=? AND status IN ('PENDING_EXTRACTION','EXTRACTING','EXTRACTED','INDEXING','RETRY')",(r,n)->uuid(r,"id"),org,document,revision);
            return unfinished.isEmpty()?create(org,document,revision):unfinished.getFirst();});
    }
    public void cancel(UUID org,UUID document,UUID keep) {
        jdbc.update("""
            UPDATE index_generations SET status='CANCELLED',lease_token=NULL,lease_until=NULL,updated_at=clock_timestamp()
            WHERE organization_id=? AND document_id=? AND (?::uuid IS NULL OR revision_id<>?)
            AND status IN ('PENDING_EXTRACTION','EXTRACTING','EXTRACTED','INDEXING','RETRY')
            """,org,document,keep,keep);
    }
    public Claim claim(IndexMessage message) {
        return tx.execute(t->{
            var events=jdbc.query("SELECT id,job_id,organization_id,document_id,revision_id,index_generation_id FROM ingestion_outbox WHERE id=? AND event_type='INDEX'",
                    (r,n)->new IndexMessage(uuid(r,"id"),uuid(r,"job_id"),uuid(r,"organization_id"),uuid(r,"document_id"),uuid(r,"revision_id"),uuid(r,"index_generation_id")),message.event());
            if(events.isEmpty() || !events.getFirst().equals(message))return new Claim("INVALID",null);
            lockDocument(message.organization(),message.document());Job job=find(message.generation(),true);
            if(Set.of("INDEXED","CANCELLED").contains(job.status()))return new Claim("ACK",null);
            if(job.status().equals("FAILED"))return new Claim("FAILED",null);
            if(!eligible(job)){cancelGeneration(job);return new Claim("ACK",null);}
            if(!job.configuration().equals(settings.configuration())){terminal(job,"MODEL_INCOMPATIBLE");return new Claim("FAILED",null);}
            if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT next_attempt_at<=clock_timestamp() AND (lease_until IS NULL OR lease_until<=clock_timestamp()) FROM index_generations WHERE id=?",Boolean.class,job.id())))return new Claim("BUSY",null);
            if(job.attempts()>=settings.maxAttempts()){terminal(job,"RETRIES_EXHAUSTED");return new Claim("FAILED",null);}
            UUID token=UUID.randomUUID();
            jdbc.update("""
                UPDATE index_generations SET status=CASE WHEN EXISTS(SELECT 1 FROM extraction_artifacts WHERE generation_id=?) THEN 'INDEXING' ELSE 'EXTRACTING' END,
                phase=CASE WHEN EXISTS(SELECT 1 FROM extraction_artifacts WHERE generation_id=?) THEN 'INDEXING' ELSE 'EXTRACTION' END,
                attempts=attempts+1,lease_token=?,lease_until=clock_timestamp()+make_interval(secs=>?),started_at=clock_timestamp(),updated_at=clock_timestamp() WHERE id=?
                """,job.id(),job.id(),token,settings.leaseSeconds(),job.id());
            return new Claim("CLAIMED",find(job.id(),false));
        });
    }
    private boolean eligible(Job job) {return Boolean.TRUE.equals(jdbc.queryForObject("""
        SELECT EXISTS(SELECT 1 FROM documents d JOIN document_revisions r ON r.id=d.current_revision_id AND r.organization_id=d.organization_id AND r.document_id=d.id
        JOIN ingestion_jobs j ON j.revision_id=r.id AND j.organization_id=d.organization_id AND j.document_id=d.id
        WHERE d.organization_id=? AND d.id=? AND r.id=? AND d.status='AVAILABLE' AND r.status='AVAILABLE' AND j.status='VERIFIED')
        """,Boolean.class,job.org(),job.document(),job.revision()));}
    private boolean owned(Job job) {return Boolean.TRUE.equals(jdbc.queryForObject("""
        SELECT EXISTS(SELECT 1 FROM index_generations WHERE id=? AND lease_token=? AND lease_until>clock_timestamp()
        AND started_at>clock_timestamp()-make_interval(secs=>?) AND status IN ('EXTRACTING','EXTRACTED','INDEXING'))
        """,Boolean.class,job.id(),job.token(),settings.deadlineSeconds()));}
    public boolean heartbeat(Job job) {
        return tx.execute(t->{lockDocument(job.org(),job.document());find(job.id(),true);
            if(!owned(job) || !eligible(job))return false;
            jdbc.update("UPDATE index_generations SET lease_until=clock_timestamp()+make_interval(secs=>?),updated_at=clock_timestamp() WHERE id=? AND lease_token=?",settings.leaseSeconds(),job.id(),job.token());return true;});
    }
    public List<SourceBlock> artifact(Job job) {
        var rows=jdbc.query("SELECT blocks::text,checksum FROM extraction_artifacts WHERE generation_id=? AND organization_id=? AND document_id=? AND revision_id=?",(r,n)->new String[]{r.getString(1),r.getString(2)},job.id(),job.org(),job.document(),job.revision());
        if(rows.isEmpty())return null;
        var blocks=json.readValue(rows.getFirst()[0],new TypeReference<List<SourceBlock>>(){});
        if(!DocumentUploadValidator.checksum(json.writeValueAsString(blocks).getBytes(StandardCharsets.UTF_8)).equals(rows.getFirst()[1]))
            throw new dev.traceback.extraction.ExtractionFailure("CONTENT_CORRUPT");
        return blocks;
    }
    public boolean extracted(Job job,List<SourceBlock> blocks) {
        return tx.execute(t->{lockDocument(job.org(),job.document());find(job.id(),true);
            if(!owned(job) || !eligible(job))return false;
            long serializationBound=2;
            for(var block:blocks) {
                // Bound duplicated heading metadata before Jackson allocates a JSON buffer.
                serializationBound+=512L+6L*block.text().length()+6L*(block.section()==null?0:block.section().length());
                if(serializationBound>9000000)throw new dev.traceback.extraction.ExtractionFailure("STRUCTURE_LIMIT");
            }
            String content=json.writeValueAsString(blocks);
            jdbc.update("INSERT INTO extraction_artifacts(generation_id,organization_id,document_id,revision_id,blocks,checksum) VALUES (?,?,?,?,?::jsonb,?) ON CONFLICT DO NOTHING",
                    job.id(),job.org(),job.document(),job.revision(),content,DocumentUploadValidator.checksum(content.getBytes(StandardCharsets.UTF_8)));
            jdbc.update("UPDATE index_generations SET status='EXTRACTED',phase='INDEXING',updated_at=clock_timestamp() WHERE id=? AND lease_token=?",job.id(),job.token());return true;});
    }
    public boolean publish(Job job,List<StructureChunker.Chunk> chunks,List<float[]> vectors,long duration) {
        return tx.execute(t->{lockDocument(job.org(),job.document());find(job.id(),true);
            if(!owned(job) || !eligible(job))return false;
            if(chunks.isEmpty() || chunks.size()!=vectors.size() || chunks.size()>settings.maxChunks())throw new EmbeddingFailure("EMBEDDING_BATCH");
            for(int i=0;i<chunks.size();i++) {
                var c=chunks.get(i);EmbeddingProvider.validate(vectors.get(i),settings.dimensions());
                jdbc.update("""
                    INSERT INTO document_chunks(generation_id,id,organization_id,document_id,revision_id,configuration_id,ordinal,content,section,page_start,page_end,source_start,source_end,checksum)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """,job.id(),c.id(),job.org(),job.document(),job.revision(),job.configuration(),c.ordinal(),c.text(),c.section(),c.pageStart(),c.pageEnd(),c.start(),c.end(),c.checksum());
                jdbc.update("INSERT INTO chunk_embeddings(organization_id,document_id,revision_id,generation_id,chunk_id,configuration_id,dimensions,embedding) VALUES (?,?,?,?,?,?,?,?::vector)",
                        job.org(),job.document(),job.revision(),job.id(),c.id(),job.configuration(),settings.dimensions(),EmbeddingProvider.literal(vectors.get(i)));
            }
            // The entire insert set, state and head switch are one transaction. Expiry after insertion also fences completion.
            if(!owned(job))throw new EmbeddingFailure("LEASE_LOST");
            jdbc.update("UPDATE index_generations SET status='INDEXED',chunk_count=?,processing_ms=?,published_at=clock_timestamp(),updated_at=clock_timestamp(),error_code=NULL,lease_token=NULL,lease_until=NULL WHERE id=?",chunks.size(),duration,job.id());
            jdbc.update("""
                INSERT INTO document_index_heads(organization_id,document_id,revision_id,generation_id,configuration_id) VALUES (?,?,?,?,?)
                ON CONFLICT (revision_id) DO UPDATE SET generation_id=EXCLUDED.generation_id,configuration_id=EXCLUDED.configuration_id
                """,job.org(),job.document(),job.revision(),job.id(),job.configuration());return true;
        });
    }
    public boolean indexing(Job job) {
        return tx.execute(t->{lockDocument(job.org(),job.document());find(job.id(),true);
            if(!owned(job) || !eligible(job))return false;
            jdbc.update("UPDATE index_generations SET status='INDEXING',phase='INDEXING',updated_at=clock_timestamp() WHERE id=? AND lease_token=?",job.id(),job.token());return true;});
    }
    public boolean fail(Job job,String code,boolean permanent,long duration) {
        return tx.execute(t->{lockDocument(job.org(),job.document());find(job.id(),true);
            if(!owned(job))return false;
            if(!eligible(job)){cancelGeneration(job);return true;}
            String status=permanent || job.attempts()>=settings.maxAttempts()?"FAILED":"RETRY";
            jdbc.update("""
                UPDATE index_generations SET status=?,error_code=?,processing_ms=?,lease_token=NULL,lease_until=NULL,
                next_attempt_at=clock_timestamp()+make_interval(secs=>?),updated_at=clock_timestamp() WHERE id=? AND lease_token=?
                """,status,code,duration,Math.min(60,1<<Math.min(job.attempts(),5)),job.id(),job.token());return true;});
    }
    private void terminal(Job job,String code) {jdbc.update("UPDATE index_generations SET status='FAILED',error_code=?,lease_token=NULL,lease_until=NULL,updated_at=clock_timestamp() WHERE id=?",code,job.id());}
    private void cancelGeneration(Job job) {jdbc.update("UPDATE index_generations SET status='CANCELLED',lease_token=NULL,lease_until=NULL,updated_at=clock_timestamp() WHERE id=? AND organization_id=? AND document_id=? AND revision_id=?",job.id(),job.org(),job.document(),job.revision());}
    private void lockDocument(UUID org,UUID doc) {jdbc.queryForObject("SELECT id FROM documents WHERE organization_id=? AND id=? FOR UPDATE",UUID.class,org,doc);}
    public Job find(UUID id,boolean lock) {
        return jdbc.queryForObject("""
            SELECT g.*,r.storage_provider,r.storage_key,r.file_size,r.checksum,r.media_type FROM index_generations g
            JOIN document_revisions r ON r.organization_id=g.organization_id AND r.document_id=g.document_id AND r.id=g.revision_id WHERE g.id=?
            """+(lock?" FOR UPDATE OF g":""),(r,n)->new Job(uuid(r,"id"),uuid(r,"organization_id"),uuid(r,"document_id"),uuid(r,"revision_id"),r.getString("configuration_id"),r.getString("status"),r.getString("phase"),r.getInt("attempts"),uuid(r,"lease_token"),r.getString("storage_provider"),uuid(r,"storage_key"),r.getLong("file_size"),r.getString("checksum"),r.getString("media_type")),id);
    }
    public int reconcile() {return tx.execute(t->jdbc.update("""
        UPDATE ingestion_outbox o SET delivered_at=NULL,next_attempt_at=clock_timestamp(),error_code='DELIVERY_RECOVERY'
        FROM index_generations g WHERE o.index_generation_id=g.id AND o.event_type='INDEX' AND o.delivered_at<clock_timestamp()-interval '180 seconds'
        AND o.lease_token IS NULL AND g.status IN ('PENDING_EXTRACTION','EXTRACTING','EXTRACTED','INDEXING','RETRY')
        AND g.updated_at<clock_timestamp()-interval '180 seconds' AND g.next_attempt_at<clock_timestamp()
        AND (g.lease_until IS NULL OR g.lease_until<clock_timestamp())
        """));}
    public long count(String status) {return jdbc.queryForObject("SELECT count(*) FROM index_generations WHERE status=?",Long.class,status);}
    public long expired() {return jdbc.queryForObject("SELECT count(*) FROM index_generations WHERE lease_until<clock_timestamp()",Long.class);}
    public long visibleRevisions() {return jdbc.queryForObject("SELECT count(*) FROM document_index_heads h JOIN documents d ON d.organization_id=h.organization_id AND d.id=h.document_id WHERE d.status='AVAILABLE' AND d.current_revision_id=h.revision_id",Long.class);}
    private static UUID uuid(ResultSet r,String field)throws SQLException{return r.getObject(field,UUID.class);}
    public record Job(UUID id,UUID org,UUID document,UUID revision,String configuration,String status,String phase,int attempts,UUID token,String provider,UUID key,long size,String checksum,String media) {}
    public record Claim(String outcome,Job job) {}
}
