package github.lms.lemuel.company.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CompanyDocumentTest {

    private static final Instant NOW = Instant.parse("2026-07-11T00:00:00Z");

    private static CompanyDocument doc(String fileName) {
        return CompanyDocument.create("005930", "브리핑", fileName, 1024, NOW);
    }

    @Test
    @DisplayName("create — docx 파일이면 Content-Type 을 확장자에서 파생한다")
    void createDerivesContentType() {
        CompanyDocument d = doc("briefing.docx");
        assertEquals("application/vnd.openxmlformats-officedocument.wordprocessingml.document", d.contentType());
        assertEquals("005930", d.stockCode());
        assertEquals("브리핑", d.title());
        assertEquals("briefing.docx", d.fileName());
        assertEquals(1024, d.sizeBytes());
        assertEquals(NOW, d.uploadedAt());
        assertNull(d.id());
    }

    @Test
    @DisplayName("create — pdf·png·md 도 허용한다 (대문자 확장자 포함)")
    void createAllowsOtherExtensions() {
        assertEquals("application/pdf", doc("report.pdf").contentType());
        assertEquals("image/png", doc("summary.PNG").contentType());
        assertEquals("text/markdown", doc("briefing.md").contentType());
    }

    @Test
    @DisplayName("create — 허용 목록 밖 확장자·무확장자는 거부한다")
    void createRejectsDisallowedExtension() {
        assertThrows(IllegalArgumentException.class, () -> doc("malware.exe"));
        assertThrows(IllegalArgumentException.class, () -> doc("noextension"));
    }

    @Test
    @DisplayName("create — 종목코드는 6자리 숫자여야 한다")
    void createRejectsBadStockCode() {
        assertThrows(IllegalArgumentException.class,
                () -> CompanyDocument.create("93", "t", "a.docx", 10, NOW));
        assertThrows(IllegalArgumentException.class,
                () -> CompanyDocument.create("00593A", "t", "a.docx", 10, NOW));
        assertThrows(IllegalArgumentException.class,
                () -> CompanyDocument.create(null, "t", "a.docx", 10, NOW));
    }

    @Test
    @DisplayName("create — 경로 문자·상위 디렉터리가 든 파일명은 거부한다")
    void createRejectsPathTraversal() {
        assertThrows(IllegalArgumentException.class, () -> doc("../briefing.docx"));
        assertThrows(IllegalArgumentException.class, () -> doc("a/b.docx"));
        assertThrows(IllegalArgumentException.class, () -> doc("a\\b.docx"));
        assertThrows(IllegalArgumentException.class, () -> doc(" "));
        assertThrows(IllegalArgumentException.class, () -> doc(null));
    }

    @Test
    @DisplayName("create — 빈 파일·20MB 초과는 거부한다")
    void createRejectsBadSize() {
        assertThrows(IllegalArgumentException.class,
                () -> CompanyDocument.create("005930", "t", "a.docx", 0, NOW));
        assertThrows(IllegalArgumentException.class,
                () -> CompanyDocument.create("005930", "t", "a.docx", CompanyDocument.MAX_SIZE_BYTES + 1, NOW));
    }

    @Test
    @DisplayName("create — 상한 경계(딱 20MB)는 허용한다")
    void createAllowsMaxBoundary() {
        assertEquals(CompanyDocument.MAX_SIZE_BYTES,
                CompanyDocument.create("005930", "t", "a.docx", CompanyDocument.MAX_SIZE_BYTES, NOW).sizeBytes());
    }

    @Test
    @DisplayName("create — 제목이 비면 파일명으로 대체하고, 200자 초과는 거부한다")
    void createResolvesTitle() {
        assertEquals("briefing.docx",
                CompanyDocument.create("005930", "  ", "briefing.docx", 10, NOW).title());
        assertEquals("briefing.docx",
                CompanyDocument.create("005930", null, "briefing.docx", 10, NOW).title());
        assertThrows(IllegalArgumentException.class,
                () -> CompanyDocument.create("005930", "가".repeat(201), "a.docx", 10, NOW));
    }

    @Test
    @DisplayName("create — uploadedAt 은 필수다")
    void createRejectsNullUploadedAt() {
        assertThrows(IllegalArgumentException.class,
                () -> CompanyDocument.create("005930", "t", "a.docx", 10, null));
    }

    @Test
    @DisplayName("rehydrate — 저장된 값을 그대로 복원한다")
    void rehydrateKeepsValues() {
        CompanyDocument d = CompanyDocument.rehydrate(7L, "005930", "제목", "b.pdf",
                "application/pdf", 2048, NOW);
        assertEquals(7L, d.id());
        assertEquals("005930", d.stockCode());
        assertEquals("제목", d.title());
        assertEquals("b.pdf", d.fileName());
        assertEquals("application/pdf", d.contentType());
        assertEquals(2048, d.sizeBytes());
        assertEquals(NOW, d.uploadedAt());
    }
}
