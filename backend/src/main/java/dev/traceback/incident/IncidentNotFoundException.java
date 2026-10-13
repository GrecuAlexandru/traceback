package dev.traceback.incident;

public class IncidentNotFoundException extends RuntimeException {
    public IncidentNotFoundException() {
        super("Incident not found.");
    }
}
