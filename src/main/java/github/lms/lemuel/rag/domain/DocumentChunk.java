package github.lms.lemuel.rag.domain;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class DocumentChunk {
    private final Long id;
    private final EntityType entityType;
    private final Long entityId;
    private final String content;
    private final float[] embedding;
    private final double similarity;
}
