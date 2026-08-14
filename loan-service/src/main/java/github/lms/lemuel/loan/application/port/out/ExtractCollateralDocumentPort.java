package github.lms.lemuel.loan.application.port.out;

import github.lms.lemuel.loan.domain.ExtractedCollateralDocument;

/**
 * 담보서류에서 필드를 읽어내는 아웃바운드 포트 (AI 비전 OCR 구현 교체 지점).
 *
 * <p>무폴백(ADR 0036): 추출 실패는
 * {@link github.lms.lemuel.loan.domain.exception.CollateralDocumentOcrUnavailableException}(503) —
 * 부분 결과를 지어내지 않는다. 감정평가액을 못 읽으면 실패고, 소유자·소재지·선순위·평가기준일
 * 판독 실패는 null 로 표현된다.
 */
public interface ExtractCollateralDocumentPort {

    /** 호출 가능한 구성인가(API 키 주입 여부). 미구성이면 유스케이스가 503 으로 끊는다. */
    boolean isConfigured();

    /** 감사·재현용 모델 식별자 — 서류 행에 함께 저장된다. */
    String modelName();

    ExtractedCollateralDocument extract(byte[] content, String contentType);
}
