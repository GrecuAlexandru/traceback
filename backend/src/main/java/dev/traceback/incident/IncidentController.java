package dev.traceback.incident;

import dev.traceback.incident.dto.CreateIncidentRequest;
import dev.traceback.incident.dto.UpdateIncidentRequest;
import dev.traceback.incident.dto.IncidentResponse;
import dev.traceback.incident.dto.IncidentPageResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/incidents")
public class IncidentController {
    private final IncidentService service;

    public IncidentController(IncidentService service) { this.service = service; }

    @PostMapping
    public ResponseEntity<IncidentResponse> create(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID organizationId,
                                                  @Valid @RequestBody CreateIncidentRequest request) {
        IncidentResponse created = service.createIncident(jwt, organizationId, request);
        return ResponseEntity.created(URI.create("/api/v1/organizations/" + organizationId + "/incidents/" + created.id()))
                .body(created);
    }

    @GetMapping
    public IncidentPageResponse getAll(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID organizationId,
                                       @RequestParam(defaultValue = "0") int page,
                                       @RequestParam(defaultValue = "20") int size,
                                       @RequestParam(required = false) IncidentStatus status) {
        if (page < 0 || size < 1 || size > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Page must be >= 0 and size must be between 1 and 100");
        }
        return service.getIncidents(jwt, organizationId, status, page, size);
    }

    @GetMapping("/{id}")
    public IncidentResponse getById(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID organizationId,
                                    @PathVariable UUID id) {
        return service.getIncident(jwt, organizationId, id);
    }

    @PatchMapping("/{id}")
    public IncidentResponse update(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID organizationId,
                                    @PathVariable UUID id, @Valid @RequestBody UpdateIncidentRequest request) {
        return service.updateIncident(jwt, organizationId, id, request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID organizationId,
                                       @PathVariable UUID id, @RequestParam long version) {
        if (version < 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Version must be >= 0");
        service.deleteIncident(jwt, organizationId, id, version);
        return ResponseEntity.noContent().build();
    }
}
