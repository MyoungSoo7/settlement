package github.lms.lemuel.rag.adapter.in.web.dto;

import jakarta.validation.constraints.NotBlank;

public record RagQueryRequest(
        @NotBlank String sessionId,
        @NotBlank String question
) {}
