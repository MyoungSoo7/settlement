package github.lms.lemuel.deposit.application.port.in;

import github.lms.lemuel.deposit.domain.DepositProof;

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
    }
}
