package github.lms.lemuel.card.application.port.in;

import github.lms.lemuel.card.domain.ExpenseReceipt;

/**
 * 지출보고서 영수증 첨부 유스케이스 — 업로드 → OCR 추출 → 매입 자동 대사까지 한 트랜잭션.
 *
 * <p>멱등: 같은 (reportId, 파일 해시) 재업로드는 기존 영수증을 그대로 반환한다(OCR 재호출 없음).
 */
public interface AttachExpenseReceiptUseCase {

    ExpenseReceipt attach(AttachReceiptCommand command);

    /**
     * @param reportId       지출보고서 자연키
     * @param uploaderUserId 업로더 userId — 보고서 소지자(holderUserId)와 대조된다 (IDOR 방지)
     * @param fileName       원본 파일명
     * @param contentType    콘텐츠 타입 (image/*, application/pdf)
     * @param content        파일 본문
     */
    record AttachReceiptCommand(String reportId, Long uploaderUserId, String fileName,
                                String contentType, byte[] content) {
    }
}
