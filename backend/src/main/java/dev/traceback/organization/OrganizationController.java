package dev.traceback.organization;

import dev.traceback.organization.dto.CreateOrganizationRequest;
import dev.traceback.organization.dto.OrganizationResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/organizations")
public class OrganizationController {
    private final OrganizationService service;

    public OrganizationController(OrganizationService service) { this.service = service; }

    @PostMapping
    public ResponseEntity<OrganizationResponse> create(@AuthenticationPrincipal Jwt jwt,
                                                       @Valid @RequestBody CreateOrganizationRequest request) {
        // M9A exposes collection create/list only; no individual-organization GET exists yet.
        return ResponseEntity.status(201).body(service.create(jwt, request));
    }

    @GetMapping
    public List<OrganizationResponse> listMine(@AuthenticationPrincipal Jwt jwt) {
        return service.listMine(jwt);
    }
}
