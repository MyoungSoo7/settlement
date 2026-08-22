package github.lms.lemuel.tax.application.port.out.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * OCR 어댑터가 돌려주는 <b>원시</b> 추출 결과 — 아직 도메인 검증을 거치지 않은 값이다.
 *
 * <p>도메인 VO({@code ExtractedTaxInvoice})가 아니라 별도 DTO 인 이유: 어댑터(벤더 응답)와 도메인
 * 불변식을 분리해, 벤더가 이상한 값을 줘도 <b>도메인 경계에서</b> 판정·거절되게 하려는 것이다.
 *
 * <p>신뢰도는 <b>판정에 쓰이는 축마다</b> 따로 받는다. 하나로 합치면 또렷하게 읽힌 금액의 확신이
 * 뭉개진 승인번호의 불확실성을 덮는다.
 *
 * @param amountConfidence          금액 3종 판독 신뢰도 0~1
 * @param approvalNumberConfidence  승인번호 판독 신뢰도 0~1 — 대사의 탐색 키라 교차검증할 상대가 없다
 */
public record OcrExtraction(String supplierBusinessNo,
                            String buyerBusinessNo,
                            LocalDate writtenDate,
                            BigDecimal supplyAmount,
                            BigDecimal taxAmount,
                            BigDecimal totalAmount,
                            String approvalNumber,
                            BigDecimal amountConfidence,
                            BigDecimal approvalNumberConfidence) {
}
