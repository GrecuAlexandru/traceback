package dev.traceback.incident;

import dev.traceback.incident.dto.CreateIncidentRequest;
import dev.traceback.incident.dto.UpdateIncidentRequest;
import dev.traceback.incident.dto.IncidentResponse;
import dev.traceback.incident.dto.IncidentPageResponse;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    public IncidentPageResponse getIncidents(
            IncidentStatus status,
            int page,
            int size
    ) {

        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));

        Page<Incident> result;

        if (status == null) {
            result = repository.findAll(pageable);
        } else {
            result = repository.findByStatus(status, pageable);
        }

        return IncidentPageResponse.fromPage(result);
    }

    public Optional<IncidentResponse> getIncident(UUID id) {
        return repository.findById(id)
                .map(IncidentResponse::fromEntity);
    }

    @Transactional
    public Optional<IncidentResponse> updateIncident(UUID id, UpdateIncidentRequest request) {

        return repository.findById(id)
                .map(incident -> {

                    if (incident.getVersion() != request.version()) {
                        throw new IncidentVersionConflictException();
                    }

                    incident.update(
                            request.title(),
                            request.description(),
                            request.status()
                    );

                    repository.flush();

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
