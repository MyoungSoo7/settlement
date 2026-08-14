package github.lms.lemuel.insurance.domain;

import github.lms.lemuel.insurance.domain.exception.InvalidApplicationDocumentException;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 청약서 OCR 추출 결과 VO (순수 도메인, 불변 — ADR 0036 확산).
 *
 * <p>연 보험료·신뢰도는 필수 — 보험료를 못 읽은 추출은 대사 근거가 될 수 없어 어댑터가 503 으로
 * 끊는다(무폴백). 보장금액·청약일·성명·상품명은 판독 실패를 {@code null} 로 표현한다 — 지어내지
 * 않고, 보장금액·청약일 null 은 대사에서 NEEDS_REVIEW 로 흐른다.
 *
 * <p>주민등록번호 등 PII 는 <b>추출 대상이 아니다</b> — 프롬프트에서 요구하지 않는다(PII 최소화,
 * 가입설계 D-P3 과 같은 원칙: 위험한 값은 검증할 게 아니라 아예 다루지 않는다).
 *
 * @param contractorName  계약자 성명 (참고 정보 — 판정에 쓰지 않는다)
 * @param insuredName     피보험자 성명 (참고 정보)
 * @param productName     상품명 (참고 정보 — 표기 흔들림이 커 판정 축이 아니다)
 * @param applicationDate 청약일 (판독 실패 null)
 * @param annualPremium   연 보험료 (필수·양수, BigDecimal 강제)
 * @param coverageAmount  보장금액 (판독 실패 null, 존재 시 양수)
 * @param confidence      판독 신뢰도 0~1 (필수)
 */
public record ExtractedApplicationForm(String contractorName, String insuredName, String productName,
                                       LocalDate applicationDate, BigDecimal annualPremium,
                                       BigDecimal coverageAmount, BigDecimal confidence) {

    public ExtractedApplicationForm {
        if (annualPremium == null || annualPremium.signum() <= 0) {
            throw new InvalidApplicationDocumentException("연 보험료는 양수여야 합니다: " + annualPremium);
        }
        if (coverageAmount != null && coverageAmount.signum() <= 0) {
            throw new InvalidApplicationDocumentException("보장금액은 양수여야 합니다: " + coverageAmount);
        }
        if (confidence == null || confidence.signum() < 0 || confidence.compareTo(BigDecimal.ONE) > 0) {
            throw new InvalidApplicationDocumentException("판독 신뢰도는 0~1 이어야 합니다: " + confidence);
        }
        contractorName = normalize(contractorName);
        insuredName = normalize(insuredName);
        productName = normalize(productName);
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
