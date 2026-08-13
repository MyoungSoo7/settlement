package github.lms.lemuel.card.application.port.out;

import github.lms.lemuel.card.domain.ExtractedReceipt;

/**
 * 영수증에서 필드를 읽어내는 아웃바운드 포트 (AI 비전 OCR 구현 교체 지점).
 *
 * <p>무폴백(ADR 0036): 추출 실패는 {@code BusinessException(CARD_RECEIPT_OCR_UNAVAILABLE)}(503) —
 * 부분 결과를 지어내지 않는다. 총액을 못 읽으면 실패고, 상호명·거래일 판독 실패는 null 로 표현된다.
 */
public interface ExtractReceiptFieldsPort {

    /** 호출 가능한 구성인가(API 키 주입 여부). 미구성이면 유스케이스가 503 으로 끊는다. */
    boolean isConfigured();

    /** 감사·재현용 모델 식별자 — 영수증 행에 함께 저장된다. */
    String modelName();

    /**
     * 영수증 바이트에서 상호명·거래일·총액·신뢰도를 추출한다.
     *
     * @throws github.lms.lemuel.common.exception.BusinessException 추출 실패·응답 파손 시
     *         {@code CARD_RECEIPT_OCR_UNAVAILABLE}(503)
     */
    ExtractedReceipt extract(byte[] content, String contentType);
}
