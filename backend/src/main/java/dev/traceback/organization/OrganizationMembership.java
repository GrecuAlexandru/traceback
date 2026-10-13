package dev.traceback.organization;

import dev.traceback.user.AppUser;
import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "organization_memberships")
public class OrganizationMembership {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organization_id", nullable = false, updatable = false)
    private Organization organization;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private AppUser user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private MembershipRole role;

    protected OrganizationMembership() {}

    public OrganizationMembership(Organization organization, AppUser user, MembershipRole role) {
        this.organization = organization;
        this.user = user;
        this.role = role;
    }

    public UUID getId() { return id; }
    public Organization getOrganization() { return organization; }
    public AppUser getUser() { return user; }
    public MembershipRole getRole() { return role; }
}
