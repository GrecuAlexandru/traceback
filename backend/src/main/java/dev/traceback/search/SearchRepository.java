package dev.traceback.search;

import dev.traceback.security.HumanIdentityPolicy.Identity;
import dev.traceback.indexing.*;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Repository;
import java.util.*;

@Repository
public class SearchRepository {
    private final NamedParameterJdbcTemplate jdbc;
    public SearchRepository(NamedParameterJdbcTemplate jdbc) {this.jdbc=jdbc;}
    private static final String FROM="""
        FROM document_chunks c
        JOIN document_index_heads h ON h.organization_id=c.organization_id AND h.document_id=c.document_id AND h.revision_id=c.revision_id AND h.generation_id=c.generation_id AND h.configuration_id=c.configuration_id
        JOIN index_generations g ON g.organization_id=c.organization_id AND g.document_id=c.document_id AND g.revision_id=c.revision_id AND g.id=c.generation_id AND g.configuration_id=c.configuration_id
        JOIN documents d ON d.organization_id=c.organization_id AND d.id=c.document_id
        JOIN document_revisions r ON r.organization_id=c.organization_id AND r.document_id=c.document_id AND r.id=c.revision_id
        JOIN index_configurations cfg ON cfg.id=c.configuration_id
        """;
    private static final String SCOPE="""
        c.organization_id=:org AND d.status='AVAILABLE' AND r.status='AVAILABLE' AND d.current_revision_id=c.revision_id AND g.status='INDEXED'
        AND EXISTS(SELECT 1 FROM organization_memberships m JOIN app_users u ON u.id=m.user_id
        WHERE m.organization_id=c.organization_id AND u.issuer=:issuer AND u.subject=:subject)
        """;
    private MapSqlParameterSource scope(UUID org,Identity identity,int count) {
        return new MapSqlParameterSource("org",org).addValue("issuer",identity.issuer()).addValue("subject",identity.subject()).addValue("count",count);
    }
    public List<Candidate> keyword(UUID org,Identity identity,String query,int count) {
        return jdbc.query("SELECT c.*,d.title,cfg.model,cfg.dimensions,ts_rank_cd(c.terms,plainto_tsquery('simple',:q)) AS score "+FROM+" WHERE "+SCOPE+"""
            AND (c.terms @@ plainto_tsquery('simple',:q) OR strpos(lower(c.content),lower(:q))>0)
            ORDER BY (strpos(lower(c.content),lower(:q))>0) DESC,score DESC,c.generation_id,c.id LIMIT :count
            """,scope(org,identity,count).addValue("q",query),this::candidate);
    }
    public List<Candidate> semantic(UUID org,Identity identity,float[] vector,String configuration,int count) {
        return jdbc.query("SELECT c.*,d.title,cfg.model,cfg.dimensions,1-(e.embedding <=> :vector::vector) AS score "+FROM+"""
            JOIN chunk_embeddings e ON e.organization_id=c.organization_id AND e.document_id=c.document_id AND e.revision_id=c.revision_id AND e.generation_id=c.generation_id AND e.chunk_id=c.id AND e.configuration_id=c.configuration_id
            WHERE
            """+SCOPE+" AND c.configuration_id=:configuration ORDER BY e.embedding <=> :vector::vector,c.generation_id,c.id LIMIT :count",
                scope(org,identity,count).addValue("vector",EmbeddingProvider.literal(vector)).addValue("configuration",configuration),this::candidate);
    }
    private Candidate candidate(java.sql.ResultSet r,int n)throws java.sql.SQLException {
        String text=r.getString("content");
        return new Candidate(r.getObject("document_id",UUID.class),r.getObject("revision_id",UUID.class),r.getObject("id",UUID.class),r.getObject("generation_id",UUID.class),
                r.getString("title"),StructureChunker.slice(text,0,Math.min(600,StructureChunker.points(text))),r.getString("section"),
                (Integer)r.getObject("page_start"),(Integer)r.getObject("page_end"),r.getInt("ordinal"),r.getString("configuration_id"),r.getString("model"),r.getInt("dimensions"),r.getDouble("score"));
    }
    public IndexStatus status(UUID org,UUID document,UUID revision,Identity identity) {
        var p=scope(org,identity,1).addValue("doc",document).addValue("rev",revision);
        var rows=jdbc.query("""
            SELECT g.status,g.phase,g.attempts,g.error_code,g.processing_ms,g.published_at,g.configuration_id,g.chunk_count,
            EXISTS(SELECT 1 FROM document_index_heads h WHERE h.generation_id=g.id) AS published
            FROM index_generations g JOIN documents d ON d.organization_id=g.organization_id AND d.id=g.document_id
            WHERE g.organization_id=:org AND d.id=:doc AND g.revision_id=:rev AND d.status<>'DELETED'
            AND EXISTS(SELECT 1 FROM organization_memberships m JOIN app_users u ON u.id=m.user_id WHERE m.organization_id=g.organization_id AND u.issuer=:issuer AND u.subject=:subject)
            ORDER BY g.updated_at DESC,g.id LIMIT 1
            """,p,(r,n)->new IndexStatus(r.getString("status"),r.getString("phase"),r.getInt("attempts"),r.getString("error_code"),(Long)r.getObject("processing_ms"),
                r.getTimestamp("published_at")==null?null:r.getTimestamp("published_at").toInstant(),r.getString("configuration_id"),(Integer)r.getObject("chunk_count"),r.getBoolean("published")));
        return rows.isEmpty()?new IndexStatus("NOT_SCHEDULED",null,0,null,null,null,null,null,false):rows.getFirst();
    }
    public record Candidate(UUID documentId,UUID revisionId,UUID chunkId,UUID generationId,String title,String excerpt,String section,Integer pageStart,Integer pageEnd,int ordinal,String configuration,String model,int dimensions,double score) {
        public String key(){return generationId+":"+chunkId;}
    }
    public record IndexStatus(String status,String phase,int attempts,String errorCode,Long processingMs,java.time.Instant publishedAt,String configuration,Integer chunkCount,boolean published) {}
}
