package dev.traceback.incident.dto;

import dev.traceback.incident.IncidentStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record UpdateIncidentRequest (

    @Size(max = 200)
    @Pattern(regexp = "(?s).*\\S.*", message = "Title must not be blank")
    String title,

    @Pattern(regexp = "(?s).*\\S.*", message = "Description must not be blank")
    String description,

    IncidentStatus status,

    @NotNull
    @PositiveOrZero
    Long version
) {}
