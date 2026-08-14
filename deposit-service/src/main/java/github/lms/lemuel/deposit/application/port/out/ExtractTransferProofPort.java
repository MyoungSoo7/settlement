package github.lms.lemuel.deposit.application.port.out;

import github.lms.lemuel.deposit.domain.ExtractedTransferProof;

/**
 * 이체확인증에서 필드를 읽어내는 아웃바운드 포트 (AI 비전 OCR 구현 교체 지점).
 *
 * <p>무폴백(ADR 0036): 추출 실패는
 * {@link github.lms.lemuel.deposit.domain.exception.DepositProofOcrUnavailableException}(503) —
 * 부분 결과를 지어내지 않는다. 이체금액을 못 읽으면 실패고, 입금자명·이체일 판독 실패는 null 로
 * 표현된다. 수취계좌번호는 추출 대상이 아니다.
 */
public interface ExtractTransferProofPort {

    /** 호출 가능한 구성인가(API 키 주입 여부). 미구성이면 유스케이스가 503 으로 끊는다. */
    boolean isConfigured();

    /** 감사·재현용 모델 식별자 — 증빙 행에 함께 저장된다. */
    String modelName();

    ExtractedTransferProof extract(byte[] content, String contentType);
}
