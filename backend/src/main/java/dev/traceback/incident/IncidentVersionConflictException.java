package dev.traceback.incident;

public class IncidentVersionConflictException extends RuntimeException {
    public IncidentVersionConflictException() {
        super("The incident was modified by another request.");
    }
}
