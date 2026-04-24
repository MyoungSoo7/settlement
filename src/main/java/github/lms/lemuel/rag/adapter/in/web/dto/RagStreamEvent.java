package github.lms.lemuel.rag.adapter.in.web.dto;

public record RagStreamEvent(String token, boolean done) {

    public static RagStreamEvent token(String token) {
        return new RagStreamEvent(token, false);
    }

    public static RagStreamEvent finished() {
        return new RagStreamEvent(null, true);
    }
}
