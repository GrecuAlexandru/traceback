package dev.traceback.rag;

import dev.traceback.security.HumanIdentityPolicy.Identity;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Repository;
import java.sql.*;
import java.time.Instant;
import java.util.*;

@Repository
public class ConversationRepository {
    private final NamedParameterJdbcTemplate jdbc;
    public ConversationRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }
    // Owner identity AND live membership are mandatory on every user-facing SQL path.
    static final String ACCESS = """
        c.organization_id=:org AND c.deleted_at IS NULL AND EXISTS (
          SELECT 1 FROM app_users u JOIN organization_memberships m ON m.user_id=u.id
          WHERE u.id=c.owner_id AND m.organization_id=c.organization_id
          AND u.issuer=:issuer AND u.subject=:subject)
        """;
    static MapSqlParameterSource scope(UUID org, Identity who) {
        return new MapSqlParameterSource("org",org).addValue("issuer",who.issuer()).addValue("subject",who.subject());
    }
    public Conversation create(UUID org, Identity who, String title) {
        UUID id=UUID.randomUUID();
        int count=jdbc.update("""
            INSERT INTO conversations(id,organization_id,owner_id,title)
            SELECT :id,:org,u.id,:title FROM app_users u JOIN organization_memberships m ON m.user_id=u.id
            WHERE m.organization_id=:org AND u.issuer=:issuer AND u.subject=:subject
            """,scope(org,who).addValue("id",id).addValue("title",title));
        if(count!=1) throw ChatFailure.missing();
        return find(org,id,who,false);
    }
    public Conversation find(UUID org, UUID id, Identity who, boolean lock) {
        var rows=jdbc.query("SELECT c.* FROM conversations c WHERE c.id=:id AND "+ACCESS+(lock?" FOR UPDATE OF c":""),
            scope(org,who).addValue("id",id),this::conversation);
        if(rows.isEmpty()) throw ChatFailure.missing();
        return rows.getFirst();
    }
    public List<Conversation> list(UUID org,Identity who,int page,int size) {
        return jdbc.query("SELECT c.* FROM conversations c WHERE "+ACCESS+" ORDER BY c.created_at DESC,c.id DESC LIMIT :size OFFSET :offset",
            scope(org,who).addValue("size",size).addValue("offset",(long)page*size),this::conversation);
    }
    public long count(UUID org,Identity who) {
        return jdbc.queryForObject("SELECT count(*) FROM conversations c WHERE "+ACCESS,scope(org,who),Long.class);
    }
    public Conversation rename(UUID org,UUID id,Identity who,String title) {
        int changed=jdbc.update("UPDATE conversations c SET title=:title,updated_at=clock_timestamp() WHERE c.id=:id AND "+ACCESS,
            scope(org,who).addValue("id",id).addValue("title",title));
        if(changed!=1) throw ChatFailure.missing();
        return find(org,id,who,false);
    }
    public void delete(UUID org,UUID id,Identity who) {
        find(org,id,who,true);
        jdbc.update("UPDATE conversations c SET deleted_at=clock_timestamp(),updated_at=clock_timestamp() WHERE c.id=:id AND "+ACCESS,
            scope(org,who).addValue("id",id));
        // Turn finalization also checks the tombstone. No content is physically purged.
        jdbc.update("""
            UPDATE conversation_turns SET status='FAILED',error_code='CONVERSATION_DELETED',completed_at=clock_timestamp()
            WHERE organization_id=:org AND conversation_id=:id AND status='GENERATING'
            """,scope(org,who).addValue("id",id));
    }
    private Conversation conversation(ResultSet r,int n) throws SQLException {
        return new Conversation(r.getObject("id",UUID.class),r.getObject("organization_id",UUID.class),r.getString("title"),
            r.getTimestamp("created_at").toInstant(),r.getTimestamp("updated_at").toInstant());
    }
    public record Conversation(UUID id,UUID organizationId,String title,Instant createdAt,Instant updatedAt) {}
}
