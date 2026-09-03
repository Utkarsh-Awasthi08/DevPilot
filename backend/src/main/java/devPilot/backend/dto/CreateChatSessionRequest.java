package devPilot.backend.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;

import jakarta.validation.constraints.Size;

public record CreateChatSessionRequest(
        @NotNull UUID repositoryId,
        @Size(max = 200) String title) {
}