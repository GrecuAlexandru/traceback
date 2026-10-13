package dev.traceback.organization;

public class OrganizationAccessDeniedException extends RuntimeException {
    public OrganizationAccessDeniedException() {
        super("Organization access requires an explicitly authorized human identity.");
    }
}
