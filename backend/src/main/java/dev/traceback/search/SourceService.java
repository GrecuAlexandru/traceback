package dev.traceback.search;

import dev.traceback.organization.OrganizationAuthorizationService;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

/** Shared exact-source read boundary; the existing M12/M13 resolver owns visibility SQL. */
@Service
public class SourceService {
    private final SearchRepository sources;
    private final OrganizationAuthorizationService authorization;
    public SourceService(SearchRepository sources,OrganizationAuthorizationService authorization) {
        this.sources=sources;this.authorization=authorization;
    }
    @Transactional(readOnly=true,timeout=5)
    public SearchRepository.Candidate resolve(Jwt jwt,UUID organization,UUID document,UUID revision,UUID generation,UUID chunk,String configuration) {
        var identity=authorization.requireAccess(jwt,organization,false);
        return sources.resolve(organization,identity,document,revision,generation,chunk,configuration)
                .orElseThrow(dev.traceback.knowledge.DocumentException::missing);
    }
}
