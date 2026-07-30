package github.lms.lemuel.loan.domain;

import github.lms.lemuel.loan.domain.exception.LoanInvariantViolationException;

/**
 * 개인·법인 공통 차주 값 객체(불변).
 *
 * <p>기존 {@link CorporateLoan} 은 상장사 종목코드(6자리)에 차주 정체성을 묶어 두어 개인 차주를 표현할 수
 * 없다. 담보대출은 개인도 차주가 되므로 차주 개념을 별도 VO 로 분리했다. 소유권 스코핑(IDOR 방지)을 위해
 * {@code userId}(JWT 주체)는 유형과 무관하게 항상 필수이며, 애플리케이션 계층은 요청 파라미터가 아니라
 * 인증 주체에서 이 값을 파생시켜야 한다.
 *
 * <p><b>유형별 불변식</b>은 생성 시점에 강제된다 — 법인은 사업자등록번호가 반드시 있어야 하고, 개인은
 * 반대로 절대 가질 수 없다. 정규 생성자가 이 조합을 검증하므로 팩토리를 우회해도 잘못된 차주는 만들어지지 않는다.
 *
 * @param type           차주 유형
 * @param userId         JWT 주체 식별자(소유권 스코핑용)
 * @param name           차주명(개인명 또는 법인명)
 * @param registrationNo 사업자등록번호 10자리(법인 전용, 개인은 {@code null})
 */
public record Borrower(BorrowerType type, Long userId, String name, String registrationNo) {

    private static final int REGISTRATION_NO_LENGTH = 10;

    /** 유형별 불변식 검증 + 정규화(이름 trim, 사업자번호 하이픈 제거). */
    public Borrower {
        if (type == null) {
            throw new LoanInvariantViolationException("차주 유형은 필수입니다");
        }
        if (userId == null) {
            throw new LoanInvariantViolationException("차주 식별자(userId)는 필수입니다");
        }
        if (name == null || name.isBlank()) {
            throw new LoanInvariantViolationException("차주명은 필수입니다");
        }
        name = name.trim();
        registrationNo = normalizeRegistrationNo(type, registrationNo);
    }

    /** 개인 차주. 사업자등록번호를 갖지 않는다. */
    public static Borrower individual(Long userId, String name) {
        return new Borrower(BorrowerType.INDIVIDUAL, userId, name, null);
    }

    /** 법인·개인사업자 차주. 사업자등록번호(하이픈 허용)가 필수다. */
    public static Borrower corporate(Long userId, String name, String registrationNo) {
        return new Borrower(BorrowerType.CORPORATE, userId, name, registrationNo);
    }

    /** 개인 차주인지 여부 — 심사 경로(담보 LTV vs 신용평가) 분기의 단일 판정점. */
    public boolean isIndividual() {
        return type == BorrowerType.INDIVIDUAL;
    }

    /**
     * 유형에 맞는 사업자등록번호로 정규화한다. 개인은 값이 있으면 안 되고, 법인은 하이픈을 제거한
     * 숫자 10자리여야 한다.
     */
    private static String normalizeRegistrationNo(BorrowerType type, String registrationNo) {
        if (type == BorrowerType.INDIVIDUAL) {
            if (registrationNo != null) {
                throw new LoanInvariantViolationException("개인 차주는 사업자등록번호를 가질 수 없습니다");
            }
            return null;
        }
        if (registrationNo == null || registrationNo.isBlank()) {
            throw new LoanInvariantViolationException("법인 차주는 사업자등록번호가 필수입니다");
        }
        String digits = registrationNo.replace("-", "").trim();
        if (digits.length() != REGISTRATION_NO_LENGTH) {
            throw new LoanInvariantViolationException(
                    "사업자등록번호는 " + REGISTRATION_NO_LENGTH + "자리여야 합니다: " + registrationNo);
        }
        for (int i = 0; i < digits.length(); i++) {
            if (!Character.isDigit(digits.charAt(i))) {
                throw new LoanInvariantViolationException(
                        "사업자등록번호는 숫자만 허용합니다: " + registrationNo);
            }
        }
        return digits;
    }
}
