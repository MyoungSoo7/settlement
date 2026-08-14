package github.lms.lemuel.deposit.application.port.in;

import github.lms.lemuel.deposit.domain.DepositProof;

import java.util.Arrays;
import java.util.Objects;

/**
 * 예치금 증빙 첨부 유스케이스 — 업로드 → OCR 추출까지 (ADR 0036 확산).
 *
 * <p><b>지연 대사</b>: 수기 기표는 선행 애그리거트가 없어(즉시 반영 구조) 첨부 시점에는 대조할 정본이
 * 없다. 앵커는 수기 기표의 호출자 지정 멱등 키 {@code (sellerId, referenceType, referenceId)} 이고,
 * 값 대사는 기표({@code credit}/{@code debit}) 시점에 {@code DepositProofGate} 가 실행한다.
 * 첨부 시점에는 신뢰도 미달만 즉시 NEEDS_REVIEW 로 보낸다.
 *
 * <p>멱등: 같은 (앵커, 파일 해시) 재업로드는 기존 증빙을 그대로 반환한다(OCR 재호출 없음).
 */
public interface AttachDepositProofUseCase {

    DepositProof attach(AttachProofCommand command);

    /**
     * @param sellerId      셀러 식별자
     * @param referenceType 기표에 쓸 referenceType (예: MANUAL_TOPUP)
     * @param referenceId   기표에 쓸 호출자 지정 멱등 키 — 기표 전 확정
     * @param uploadedBy    업로더 userId — JWT 주체에서 파생 (ADMIN)
     * @param fileName      원본 파일명
     * @param contentType   콘텐츠 타입 (image/*, application/pdf)
     * @param content       파일 본문
     */
    record AttachProofCommand(Long sellerId, String referenceType, String referenceId,
                              Long uploadedBy, String fileName, String contentType, byte[] content) {

        // 배열 컴포넌트는 레코드 기본 구현이 참조 동일성으로 비교·해시한다 — 같은 파일 재업로드를
        // 멱등으로 다루는 명령에서 "같은 내용"이 같지 않게 나오는 함정이라 내용 기준으로 맞춘다.
        // toString 은 본문 대신 길이만 남긴다(파일 바이트가 로그로 새지 않게).
        @Override
        public boolean equals(Object o) {
            return o instanceof AttachProofCommand c
                    && Objects.equals(sellerId, c.sellerId)
                    && Objects.equals(referenceType, c.referenceType)
                    && Objects.equals(referenceId, c.referenceId)
                    && Objects.equals(uploadedBy, c.uploadedBy)
                    && Objects.equals(fileName, c.fileName)
                    && Objects.equals(contentType, c.contentType)
                    && Arrays.equals(content, c.content);
        }

        @Override
        public int hashCode() {
            return 31 * Objects.hash(sellerId, referenceType, referenceId, uploadedBy, fileName, contentType)
                    + Arrays.hashCode(content);
        }

        @Override
        public String toString() {
            return "AttachProofCommand[sellerId=" + sellerId + ", referenceType=" + referenceType
                    + ", referenceId=" + referenceId + ", uploadedBy=" + uploadedBy
                    + ", fileName=" + fileName + ", contentType=" + contentType
                    + ", contentBytes=" + (content == null ? 0 : content.length) + "]";
        }
    }
}
