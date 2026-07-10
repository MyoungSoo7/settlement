package github.lms.lemuel.company.adapter.in.web.dto;

import github.lms.lemuel.company.domain.CompanyDocument;

import java.time.Instant;

public record CompanyDocumentResponse(Long id, String stockCode, String title, String fileName,
                                      String contentType, long sizeBytes, Instant uploadedAt) {

    public static CompanyDocumentResponse from(CompanyDocument d) {
        return new CompanyDocumentResponse(d.id(), d.stockCode(), d.title(), d.fileName(),
                d.contentType(), d.sizeBytes(), d.uploadedAt());
    }
}
