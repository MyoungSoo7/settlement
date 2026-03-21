package github.lms.lemuel.rag.application.port.out;

import java.util.List;

public interface EmbeddingPort {
    float[] embed(String text);
    List<float[]> embedBatch(List<String> texts);
}
