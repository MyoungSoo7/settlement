package github.lms.lemuel.company.application.port.in;

import github.lms.lemuel.company.domain.CompanyDocument;

import java.util.List;
import java.util.Optional;

public interface GetCompanyDocumentsUseCase {

    /** 기업별 문서 메타데이터 목록 (최신 업로드 순, 파일 바이트 미포함). */
    List<CompanyDocument> byCompany(String stockCode);

    Optional<DocumentDownload> download(Long id);

    /** 배열 필드 때문에 record 기본 equals/hashCode/toString 이 놀랍게 동작한다 — 내용 기준으로 재정의한다. */
    record DocumentDownload(CompanyDocument document, byte[] content) {

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof DocumentDownload other)) {
                return false;
            }
            return java.util.Objects.equals(document, other.document)
                    && java.util.Arrays.equals(content, other.content);
        }

        @Override
        public int hashCode() {
            return 31 * java.util.Objects.hashCode(document) + java.util.Arrays.hashCode(content);
        }

        /** 파일 바이트는 로그로 흘리지 않는다 — 길이만 남긴다. */
        @Override
        public String toString() {
            return "DocumentDownload[document=" + document
                    + ", content=" + (content == null ? "null" : content.length + "B") + "]";
        }
    }
}
