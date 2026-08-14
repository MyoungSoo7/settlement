package github.lms.lemuel.insurance.application.port.in;

import github.lms.lemuel.insurance.domain.ApplicationDocument;

import java.util.Arrays;
import java.util.Objects;

/**
 * 청약서류 첨부 유스케이스 — 업로드 → OCR 추출 → 청약 자동 대사까지 한 트랜잭션 (ADR 0036 확산).
 *
 * <p>멱등: 같은 (applicationId, 파일 해시) 재업로드는 기존 서류를 그대로 반환한다(OCR 재호출 없음).
 */
public interface AttachApplicationDocumentUseCase {

    ApplicationDocument attach(AttachDocumentCommand command);

    /**
     * @param applicationId 청약 자연키
     * @param uploadedBy    업로더 식별자 — JWT 주체에서 파생({@code FcIdentity})
     * @param fileName      원본 파일명
     * @param contentType   콘텐츠 타입 (image/*, application/pdf)
     * @param content       파일 본문
     */
    record AttachDocumentCommand(String applicationId, String uploadedBy, String fileName,
                                 String contentType, byte[] content) {

        // 배열 컴포넌트는 레코드 기본 구현이 참조 동일성으로 비교·해시한다 — 같은 파일 재업로드를
        // 멱등으로 다루는 명령에서 "같은 내용"이 같지 않게 나오는 함정이라 내용 기준으로 맞춘다.
        // toString 은 본문 대신 길이만 남긴다(파일 바이트가 로그로 새지 않게).
        @Override
        public boolean equals(Object o) {
            return o instanceof AttachDocumentCommand c
                    && Objects.equals(applicationId, c.applicationId)
                    && Objects.equals(uploadedBy, c.uploadedBy)
                    && Objects.equals(fileName, c.fileName)
                    && Objects.equals(contentType, c.contentType)
                    && Arrays.equals(content, c.content);
        }

        @Override
        public int hashCode() {
            return 31 * Objects.hash(applicationId, uploadedBy, fileName, contentType) + Arrays.hashCode(content);
        }

        @Override
        public String toString() {
            return "AttachDocumentCommand[applicationId=" + applicationId + ", uploadedBy=" + uploadedBy
                    + ", fileName=" + fileName + ", contentType=" + contentType
                    + ", contentBytes=" + (content == null ? 0 : content.length) + "]";
        }
    }
}
