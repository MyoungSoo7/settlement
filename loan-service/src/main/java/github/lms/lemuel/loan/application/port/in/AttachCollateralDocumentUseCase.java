package github.lms.lemuel.loan.application.port.in;

import github.lms.lemuel.loan.domain.CollateralDocument;

/**
 * 담보서류 첨부 유스케이스 — 업로드 → OCR 추출 → 담보 설정값 자동 대사까지 한 트랜잭션 (ADR 0036 확산).
 *
 * <p>멱등: 같은 (loanId, 파일 해시) 재업로드는 기존 서류를 그대로 반환한다(OCR 재호출 없음).
 */
public interface AttachCollateralDocumentUseCase {

    CollateralDocument attach(AttachCollateralDocumentCommand command);

    /**
     * @param loanId         담보대출 식별자
     * @param uploaderUserId 업로더 userId — JWT 주체에서 파생 (컨트롤러가 소유권 대조)
     * @param fileName       원본 파일명
     * @param contentType    콘텐츠 타입 (image/*, application/pdf)
     * @param content        파일 본문
     */
    record AttachCollateralDocumentCommand(Long loanId, Long uploaderUserId, String fileName,
                                           String contentType, byte[] content) {
    }
}
