package dev.traceback.user;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "app_users")
public class AppUser {
    @Id
    private UUID id;

    @Column(nullable = false, length = 255, updatable = false)
    private String issuer;

    @Column(nullable = false, length = 255, updatable = false)
    private String subject;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected AppUser() {}

    public UUID getId() { return id; }
    public String getIssuer() { return issuer; }
    public String getSubject() { return subject; }
    public Instant getCreatedAt() { return createdAt; }
}
