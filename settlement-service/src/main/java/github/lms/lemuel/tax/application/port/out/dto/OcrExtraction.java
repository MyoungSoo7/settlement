package github.lms.lemuel.tax.application.port.out.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * OCR 어댑터가 돌려주는 <b>원시</b> 추출 결과 — 아직 도메인 검증을 거치지 않은 값이다.
 *
 * <p>도메인 VO({@code ExtractedTaxInvoice})가 아니라 별도 DTO 인 이유: 어댑터(벤더 응답)와 도메인
 * 불변식을 분리해, 벤더가 이상한 값을 줘도 <b>도메인 경계에서</b> 판정·거절되게 하려는 것이다.
 *
 * @param confidence 0~1. 벤더가 신뢰도를 주지 않으면 어댑터가 자기 성격에 맞는 값을 채운다
 *                   (결정적 텍스트 파서는 1.0, LLM 은 모델이 보고한 값).
 */
public record OcrExtraction(String supplierBusinessNo,
                            String buyerBusinessNo,
                            LocalDate writtenDate,
                            BigDecimal supplyAmount,
                            BigDecimal taxAmount,
                            BigDecimal totalAmount,
                            String approvalNumber,
                            BigDecimal confidence) {
}
