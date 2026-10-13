package dev.traceback.organization;

public class OrganizationPermissionDeniedException extends RuntimeException {
    public OrganizationPermissionDeniedException() {
        super("You do not have permission to access this organization's incidents.");
    }
}
