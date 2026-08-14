package github.lms.lemuel.insurance.application.port.out;

import github.lms.lemuel.insurance.domain.ExtractedApplicationForm;

/**
 * 청약서에서 필드를 읽어내는 아웃바운드 포트 (AI 비전 OCR 구현 교체 지점).
 *
 * <p>무폴백(ADR 0036): 추출 실패는
 * {@link github.lms.lemuel.insurance.domain.exception.ApplicationDocumentOcrUnavailableException}(503) —
 * 부분 결과를 지어내지 않는다. 연 보험료를 못 읽으면 실패고, 성명·상품명·보장금액·청약일 판독 실패는
 * null 로 표현된다. 주민등록번호 등 PII 는 추출 대상이 아니다.
 */
public interface ExtractApplicationFormPort {

    /** 호출 가능한 구성인가(API 키 주입 여부). 미구성이면 유스케이스가 503 으로 끊는다. */
    boolean isConfigured();

    /** 감사·재현용 모델 식별자 — 서류 행에 함께 저장된다. */
    String modelName();

    /**
     * 청약서 바이트에서 성명·상품명·청약일·연 보험료·보장금액·신뢰도를 추출한다.
     */
    ExtractedApplicationForm extract(byte[] content, String contentType);
}
