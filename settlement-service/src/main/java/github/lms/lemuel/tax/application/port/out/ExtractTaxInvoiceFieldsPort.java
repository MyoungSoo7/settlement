package github.lms.lemuel.tax.application.port.out;

import github.lms.lemuel.tax.application.port.out.dto.OcrExtraction;

/**
 * 세금계산서 스캔본에서 필드를 읽어내는 아웃바운드 포트 (AI OCR / 텍스트 레이어 파서 등 구현 교체 지점).
 *
 * <p>구현체는 정확히 하나만 등록된다({@code app.tax.ocr.provider} 스위치) — 벤더 교체가 도메인·유스케이스를
 * 건드리지 않게 하려는 것이 이 포트의 존재 이유다.
 */
public interface ExtractTaxInvoiceFieldsPort {

    /** 호출 가능한 구성인가(예: API 키 주입 여부). 미구성이면 유스케이스가 503 으로 끊는다. */
    boolean isConfigured();

    /** 감사·재현용 모델 식별자 — 스캔 행에 함께 저장된다. */
    String modelName();

    /**
     * 스캔본 바이트에서 세금계산서 필드를 추출한다.
     *
     * @throws github.lms.lemuel.common.exception.BusinessException 추출 실패·응답 파손 시
     *         {@code TAX_OCR_UNAVAILABLE}(503) — 부분 결과를 지어내지 않는다
     */
    OcrExtraction extract(byte[] content, String contentType);
}
