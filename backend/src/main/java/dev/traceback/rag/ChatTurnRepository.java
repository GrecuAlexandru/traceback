package dev.traceback.rag;

import dev.traceback.security.HumanIdentityPolicy.Identity;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Repository;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import static dev.traceback.rag.ConversationRepository.*;

@Repository
public class ChatTurnRepository {
    private final NamedParameterJdbcTemplate jdbc;
    public ChatTurnRepository(NamedParameterJdbcTemplate jdbc) {this.jdbc=jdbc;}
    private MapSqlParameterSource params(UUID org,UUID conversation,Identity who) {return scope(org,who).addValue("conversation",conversation);}
    private static final String OWNED="SELECT c.id FROM conversations c WHERE c.id=:conversation AND "+ACCESS;
    private static final String TURN_SELECT="""
        SELECT t.*,u.id AS user_message_id,a.id AS assistant_message_id FROM conversation_turns t
        JOIN conversation_messages u ON u.turn_id=t.id AND u.role='USER'
        JOIN conversation_messages a ON a.turn_id=t.id AND a.role='ASSISTANT'
        WHERE t.organization_id=:org AND t.conversation_id IN (
        """+OWNED+") ";
    /** Caller holds only a short conversation row lock. Expiry makes crashed turns terminal, never regenerated. */
    public Optional<Turn> existing(UUID org,UUID conversation,Identity who,UUID key) {
        var p=params(org,conversation,who).addValue("key",key);
        jdbc.update("""
            UPDATE conversation_turns SET status='FAILED',error_code='GENERATION_EXPIRED',completed_at=clock_timestamp()
            WHERE organization_id=:org AND conversation_id IN (
            """+OWNED+") AND status='GENERATING' AND deadline<=clock_timestamp()",p);
        return jdbc.query(TURN_SELECT+" AND t.request_key=:key",p,this::turn).stream().findFirst();
    }
    public Turn reserve(UUID org,UUID conversation,Identity who,UUID key,String hash,String question,int leaseSeconds) {
        var p=params(org,conversation,who).addValue("key",key).addValue("hash",hash).addValue("turn",UUID.randomUUID())
            .addValue("user",UUID.randomUUID()).addValue("assistant",UUID.randomUUID()).addValue("question",question).addValue("lease",leaseSeconds);
        Boolean busy=jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM conversation_turns WHERE conversation_id IN ("+OWNED+") AND status='GENERATING')",p,Boolean.class);
        if(Boolean.TRUE.equals(busy))throw ChatFailure.conflict("TURN_IN_PROGRESS");
        jdbc.update("""
            INSERT INTO conversation_turns(id,organization_id,conversation_id,request_key,request_hash,status,deadline)
            SELECT :turn,:org,c.id,:key,:hash,'GENERATING',clock_timestamp()+make_interval(secs=>:lease)
            FROM conversations c WHERE c.id=:conversation AND
            """+ACCESS,p);
        jdbc.update("""
            INSERT INTO conversation_messages(id,organization_id,conversation_id,turn_id,sequence,role,content)
            SELECT :user,:org,c.id,:turn,c.next_sequence,'USER',:question FROM conversations c WHERE c.id=:conversation AND
            """+ACCESS,p);
        jdbc.update("""
            INSERT INTO conversation_messages(id,organization_id,conversation_id,turn_id,sequence,role)
            SELECT :assistant,:org,c.id,:turn,c.next_sequence+1,'ASSISTANT' FROM conversations c WHERE c.id=:conversation AND
            """+ACCESS,p);
        jdbc.update("UPDATE conversations c SET next_sequence=next_sequence+2,updated_at=clock_timestamp() WHERE c.id=:conversation AND "+ACCESS,p);
        return jdbc.query(TURN_SELECT+" AND t.id=:turn",p,this::turn).getFirst();
    }
    public Turn find(UUID org,UUID conversation,Identity who,UUID turn) {
        var rows=jdbc.query(TURN_SELECT+" AND t.id=:turn",params(org,conversation,who).addValue("turn",turn),this::turn);
        if(rows.isEmpty())throw ChatFailure.missing();return rows.getFirst();
    }
    public List<Message> messages(UUID org,UUID conversation,Identity who,int page,int size,boolean recent) {
        var p=params(org,conversation,who).addValue("size",size).addValue("offset",(long)page*size);
        var rows=jdbc.query("""
            SELECT m.*,t.status,t.error_code,t.metadata::text,t.completed_at FROM conversation_messages m
            JOIN conversation_turns t ON t.id=m.turn_id AND t.organization_id=m.organization_id AND t.conversation_id=m.conversation_id
            WHERE m.organization_id=:org AND m.conversation_id IN (
            """+OWNED+") ORDER BY m.sequence "+(recent?"DESC":"ASC")+" LIMIT :size OFFSET :offset",p,this::message);
        if(recent) {var ordered=new ArrayList<>(rows);Collections.reverse(ordered);return ordered;}
        return rows;
    }
    public Message message(UUID org,UUID conversation,Identity who,UUID id) {
        var rows=jdbc.query("SELECT m.*,t.status,t.error_code,t.metadata::text,t.completed_at FROM conversation_messages m JOIN conversation_turns t ON t.id=m.turn_id WHERE m.id=:id AND m.organization_id=:org AND m.conversation_id IN ("+OWNED+")",
            params(org,conversation,who).addValue("id",id),this::message);
        if(rows.isEmpty())throw ChatFailure.missing();return rows.getFirst();
    }
    public long messageCount(UUID org,UUID conversation,Identity who) {
        return jdbc.queryForObject("SELECT count(*) FROM conversation_messages WHERE organization_id=:org AND conversation_id IN ("+OWNED+")",params(org,conversation,who),Long.class);
    }
    public List<SourceRef> sources(UUID org,UUID conversation,Identity who,UUID message) {
        return jdbc.query("SELECT e.* FROM conversation_evidence e WHERE e.message_id=:message AND e.organization_id=:org AND e.conversation_id IN ("+OWNED+")",
            params(org,conversation,who).addValue("message",message),(r,n)->new SourceRef(r.getString("reference"),r.getObject("document_id",UUID.class),
                r.getObject("revision_id",UUID.class),r.getObject("generation_id",UUID.class),r.getObject("chunk_id",UUID.class),r.getString("configuration_id"),r.getBoolean("cited")));
    }
    public void complete(UUID org,UUID conversation,Identity who,Turn turn,RagContext.Answer answer,List<RagContext.Evidence> dependencies,String metadata) {
        var p=params(org,conversation,who).addValue("turn",turn.id()).addValue("status",answer.status()).addValue("metadata",metadata)
            .addValue("assistant",turn.assistantMessageId()).addValue("content",answer.content());
        int changed=jdbc.update("""
            UPDATE conversation_turns SET status=:status,metadata=:metadata::jsonb,completed_at=clock_timestamp()
            WHERE id=:turn AND organization_id=:org AND conversation_id IN (
            """+OWNED+") AND status='GENERATING' AND deadline>clock_timestamp()",p);
        if(changed!=1)throw ChatFailure.conflict("GENERATION_EXPIRED");
        jdbc.update("UPDATE conversation_messages SET content=:content WHERE id=:assistant AND organization_id=:org AND conversation_id IN ("+OWNED+")",p);
        for(var evidence:dependencies) {
            var source=evidence.source();
            jdbc.update("""
                INSERT INTO conversation_evidence(organization_id,conversation_id,message_id,reference,document_id,revision_id,generation_id,chunk_id,configuration_id,cited)
                SELECT :org,:conversation,:assistant,:ref,:doc,:rev,:gen,:chunk,:config,:cited WHERE EXISTS (
                """+OWNED+")",params(org,conversation,who).addValue("assistant",turn.assistantMessageId()).addValue("ref",evidence.reference())
                .addValue("doc",source.documentId()).addValue("rev",source.revisionId()).addValue("gen",source.generationId()).addValue("chunk",source.chunkId())
                .addValue("config",source.configuration()).addValue("cited",evidence.cited()));
        }
        jdbc.update("UPDATE conversations c SET updated_at=clock_timestamp() WHERE c.id=:conversation AND "+ACCESS,p);
    }
    /** Internal compensation uses only the server-reserved turn identity. May run after membership removal. */
    public void fail(Turn turn,String code) {
        jdbc.update("""
            UPDATE conversation_turns SET status='FAILED',error_code=:code,completed_at=clock_timestamp()
            WHERE id=:id AND organization_id=:org AND conversation_id=:conversation AND status='GENERATING'
            """,new MapSqlParameterSource("id",turn.id()).addValue("org",turn.organizationId()).addValue("conversation",turn.conversationId()).addValue("code",code));
    }
    private Turn turn(ResultSet r,int n) throws SQLException {
        return new Turn(r.getObject("id",UUID.class),r.getObject("organization_id",UUID.class),r.getObject("conversation_id",UUID.class),
            r.getString("request_hash"),r.getString("status"),r.getObject("user_message_id",UUID.class),r.getObject("assistant_message_id",UUID.class),r.getTimestamp("created_at").toInstant());
    }
    private Message message(ResultSet r,int n) throws SQLException {
        return new Message(r.getObject("id",UUID.class),r.getObject("turn_id",UUID.class),r.getLong("sequence"),r.getString("role"),r.getString("content"),
            r.getString("status"),r.getString("error_code"),r.getString("metadata"),r.getTimestamp("created_at").toInstant(),r.getTimestamp("completed_at")==null?null:r.getTimestamp("completed_at").toInstant());
    }
    public record Turn(UUID id,UUID organizationId,UUID conversationId,String hash,String status,UUID userMessageId,UUID assistantMessageId,Instant createdAt) {}
    public record Message(UUID id,UUID turnId,long sequence,String role,String content,String status,String errorCode,String metadata,Instant createdAt,Instant completedAt) {}
    public record SourceRef(String reference,UUID document,UUID revision,UUID generation,UUID chunk,String configuration,boolean cited) {
        public String key() {return generation+":"+chunk;}
    }
}
