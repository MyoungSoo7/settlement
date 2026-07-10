package github.lms.lemuel.company.adapter.out.persistence;

import github.lms.lemuel.company.domain.CompanyDocument;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 기업 문서 엔티티 — 파일 바이트(BYTEA) 포함. 목록 조회는 이 엔티티 대신
 * {@link CompanyDocumentRepository#findMetaByStockCode} 프로젝션을 써서 BYTEA 를 끌어오지 않는다.
 */
@Entity
@Table(name = "company_documents")
public class CompanyDocumentJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "stock_code", nullable = false, length = 6)
    private String stockCode;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "content", nullable = false)
    private byte[] content;

    @Column(name = "uploaded_at", nullable = false)
    private Instant uploadedAt;

    protected CompanyDocumentJpaEntity() {
    }

    static CompanyDocumentJpaEntity fromDomain(CompanyDocument document, byte[] content) {
        CompanyDocumentJpaEntity e = new CompanyDocumentJpaEntity();
        e.stockCode = document.stockCode();
        e.replaceWith(document, content);
        return e;
    }

    /** 같은 (stockCode, fileName) 재업로드 — 내용·제목·업로드 시각을 교체한다. */
    void replaceWith(CompanyDocument document, byte[] content) {
        this.title = document.title();
        this.fileName = document.fileName();
        this.contentType = document.contentType();
        this.sizeBytes = document.sizeBytes();
        this.content = content;
        this.uploadedAt = document.uploadedAt();
    }

    CompanyDocument toDomain() {
        return CompanyDocument.rehydrate(id, stockCode, title, fileName, contentType, sizeBytes, uploadedAt);
    }

    byte[] content() {
        return content;
    }
}
