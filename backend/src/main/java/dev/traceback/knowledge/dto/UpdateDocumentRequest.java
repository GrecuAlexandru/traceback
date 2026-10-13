package dev.traceback.knowledge.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record UpdateDocumentRequest(@NotNull @PositiveOrZero Long version, @NotBlank @Size(max = 200) String title) {}
