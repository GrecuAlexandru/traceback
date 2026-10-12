package dev.traceback.incident;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface IncidentRepository extends JpaRepository<Incident, UUID> {

}
