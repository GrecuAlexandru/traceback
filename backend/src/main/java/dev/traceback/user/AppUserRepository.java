package dev.traceback.user;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface AppUserRepository extends JpaRepository<AppUser, UUID> {
    Optional<AppUser> findByIssuerAndSubject(String issuer, String subject);

    // PostgreSQL waits for a concurrent insert of the same identity. At READ_COMMITTED,
    // the subsequent lookup sees the winning row without aborting this transaction.
    @Modifying
    @Query(value = """
            INSERT INTO app_users (id, issuer, subject)
            VALUES (:id, :issuer, :subject)
            ON CONFLICT (issuer, subject) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("id") UUID id, @Param("issuer") String issuer,
                       @Param("subject") String subject);
}
