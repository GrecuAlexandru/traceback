package dev.traceback.incident;

import dev.traceback.incident.dto.CreateIncidentRequest;
import dev.traceback.incident.dto.UpdateIncidentRequest;
import dev.traceback.incident.dto.IncidentResponse;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/incidents")
public class IncidentController {

    private final IncidentService service;

    public IncidentController(IncidentService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<IncidentResponse> create(@Valid @RequestBody CreateIncidentRequest request) {
        IncidentResponse created = service.createIncident(request);

        URI location = URI.create(
            "/api/v1/incidents/" + created.id()
        );

        return ResponseEntity
                .created(location)
                .body(created);
    }

    @GetMapping
    public List<IncidentResponse> getAll() {
        return service.getAllIncidents();
    }

    @GetMapping("/{id}")
    public ResponseEntity<IncidentResponse> getById(@PathVariable UUID id) {
        return service.getIncident(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PatchMapping("/{id}")
    public ResponseEntity<IncidentResponse> update(@PathVariable UUID id, @Valid @RequestBody UpdateIncidentRequest request) {
        return service.updateIncident(id, request)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        boolean deleted = service.deleteIncident(id);

        if (deleted) {
            return ResponseEntity.noContent().build();
        }

        return ResponseEntity.notFound().build();
    }
}
