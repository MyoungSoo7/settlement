package github.lms.lemuel.company.domain;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;

/**
 * 기업 문서 — 외부 파이프라인(예: CEO 브리핑 에이전트)이 만든 산출물의 메타데이터.
 *
 * <p>파일 바이트는 도메인 밖(포트 인자)으로 흐르고, 도메인은 식별·검증 규칙만 소유한다.
 * (stockCode, fileName) 이 업무 키 — 같은 이름 재업로드는 교체(최신 브리핑으로 갱신)다.
 * Content-Type 은 클라이언트 선언을 신뢰하지 않고 확장자 허용 목록에서 서버가 파생한다.
 */
public class CompanyDocument {

    public static final long MAX_SIZE_BYTES = 20L * 1024 * 1024;

    private static final Map<String, String> CONTENT_TYPE_BY_EXTENSION = Map.of(
            "docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "pdf", "application/pdf",
            "png", "image/png",
            "md", "text/markdown");

    private final Long id;
    private final String stockCode;
    private final String title;
    private final String fileName;
    private final String contentType;
    private final long sizeBytes;
    private final Instant uploadedAt;

    private CompanyDocument(Long id, String stockCode, String title, String fileName,
                            String contentType, long sizeBytes, Instant uploadedAt) {
        this.id = id;
        this.stockCode = stockCode;
        this.title = title;
        this.fileName = fileName;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.uploadedAt = uploadedAt;
    }

    /** 업로드 요청에서 신규 문서를 만든다. 제목이 비면 파일명으로 대체한다. */
    public static CompanyDocument create(String stockCode, String title, String fileName,
                                         long sizeBytes, Instant uploadedAt) {
        if (stockCode == null || !stockCode.matches("\\d{6}")) {
            throw new IllegalArgumentException("종목코드는 6자리 숫자여야 합니다: " + stockCode);
        }
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException("파일명은 필수입니다");
        }
        String normalizedFileName = fileName.trim();
        if (normalizedFileName.length() > 255 || normalizedFileName.contains("/")
                || normalizedFileName.contains("\\") || normalizedFileName.contains("..")) {
            throw new IllegalArgumentException("허용되지 않는 파일명입니다: " + fileName);
        }
        String contentType = contentTypeFor(normalizedFileName);
        if (sizeBytes <= 0) {
            throw new IllegalArgumentException("빈 파일은 업로드할 수 없습니다");
        }
        if (sizeBytes > MAX_SIZE_BYTES) {
            throw new IllegalArgumentException("파일이 최대 크기(20MB)를 초과합니다: " + sizeBytes + " bytes");
        }
        if (uploadedAt == null) {
            throw new IllegalArgumentException("uploadedAt 은 필수입니다");
        }
        String resolvedTitle = (title == null || title.isBlank()) ? normalizedFileName : title.trim();
        if (resolvedTitle.length() > 200) {
            throw new IllegalArgumentException("제목은 200자 이하여야 합니다");
        }
        return new CompanyDocument(null, stockCode, resolvedTitle, normalizedFileName,
                contentType, sizeBytes, uploadedAt);
    }

    /** 확장자 → Content-Type. 허용 목록(docx·pdf·png·md) 밖이면 예외. */
    static String contentTypeFor(String fileName) {
        int dot = fileName.lastIndexOf('.');
        String extension = dot == -1 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        String contentType = CONTENT_TYPE_BY_EXTENSION.get(extension);
        if (contentType == null) {
            throw new IllegalArgumentException("허용되지 않는 파일 형식입니다 (docx·pdf·png·md): " + fileName);
        }
        return contentType;
    }

    /** 영속 계층 재구성용. */
    public static CompanyDocument rehydrate(Long id, String stockCode, String title, String fileName,
                                            String contentType, long sizeBytes, Instant uploadedAt) {
        return new CompanyDocument(id, stockCode, title, fileName, contentType, sizeBytes, uploadedAt);
    }

    public Long id() {
        return id;
    }

    public String stockCode() {
        return stockCode;
    }

    public String title() {
        return title;
    }

    public String fileName() {
        return fileName;
    }

    public String contentType() {
        return contentType;
    }

    public long sizeBytes() {
        return sizeBytes;
    }

    public Instant uploadedAt() {
        return uploadedAt;
    }
}
