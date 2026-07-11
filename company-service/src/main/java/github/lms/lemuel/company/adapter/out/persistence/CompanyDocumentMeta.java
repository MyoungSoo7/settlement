package github.lms.lemuel.company.adapter.out.persistence;

import github.lms.lemuel.company.domain.CompanyDocument;

import java.time.Instant;

/** 목록 조회용 메타데이터 프로젝션 — BYTEA content 를 제외하고 select 한다. */
public record CompanyDocumentMeta(Long id, String stockCode, String title, String fileName,
                                  String contentType, long sizeBytes, Instant uploadedAt) {

    CompanyDocument toDomain() {
        return CompanyDocument.rehydrate(id, stockCode, title, fileName, contentType, sizeBytes, uploadedAt);
    }
}
