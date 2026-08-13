package github.lms.lemuel.company.application.port.in;

import github.lms.lemuel.company.domain.CompanyDocument;

public interface UploadCompanyDocumentUseCase {

    /** 같은 (stockCode, fileName) 이 이미 있으면 내용을 교체한다. */
    CompanyDocument upload(UploadCommand command);

    /**
     * 업로드 명령. {@code content} 가 배열이라 record 기본 구현은 <b>참조 동일성</b>으로 비교하고
     * {@code toString()} 은 {@code [B@1a2b3c} 를 찍는다 — 둘 다 놀라운 동작이라 재정의한다.
     * 특히 {@code toString()} 은 파일 바이트를 로그로 흘릴 수 있어(문서함은 기업 기밀) 길이만 남긴다.
     */
    record UploadCommand(String stockCode, String title, String fileName, byte[] content) {

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof UploadCommand other)) {
                return false;
            }
            return java.util.Objects.equals(stockCode, other.stockCode)
                    && java.util.Objects.equals(title, other.title)
                    && java.util.Objects.equals(fileName, other.fileName)
                    && java.util.Arrays.equals(content, other.content);
        }

        @Override
        public int hashCode() {
            return 31 * java.util.Objects.hash(stockCode, title, fileName) + java.util.Arrays.hashCode(content);
        }

        @Override
        public String toString() {
            return "UploadCommand[stockCode=" + stockCode + ", title=" + title + ", fileName=" + fileName
                    + ", content=" + (content == null ? "null" : content.length + "B") + "]";
        }
    }
}
