package dev.traceback.incident;

import dev.traceback.incident.dto.CreateIncidentRequest;
import dev.traceback.incident.dto.UpdateIncidentRequest;
import dev.traceback.incident.dto.IncidentResponse;

import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class IncidentService {

    private final IncidentRepository repository;

    public IncidentService(IncidentRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public IncidentResponse createIncident(CreateIncidentRequest request) {
        Incident incident = new Incident(
                request.title(),
                request.description()
        );

        Incident saved = repository.save(incident);

        return IncidentResponse.fromEntity(saved);
    }

    public List<IncidentResponse> getAllIncidents() {
        return repository.findAll(Sort.by(Sort.Direction.DESC, "createdAt"))
                .stream()
                .map(IncidentResponse::fromEntity)
                .toList();
    }

    public Optional<IncidentResponse> getIncident(UUID id) {
        return repository.findById(id)
                .map(IncidentResponse::fromEntity);
    }

    @Transactional
    public Optional<IncidentResponse> updateIncident(UUID id, UpdateIncidentRequest request) {

        return repository.findById(id)
                .map(incident -> {

                    incident.update(
                            request.title(),
                            request.description(),
                            request.status()
                    );

                    return IncidentResponse.fromEntity(incident);
                });
    }

    @Transactional
    public boolean deleteIncident(UUID id) {

        return repository.findById(id)
                .map(incident -> {
                    repository.delete(incident);
                    return true;
                })
                .orElse(false);
    }
}
