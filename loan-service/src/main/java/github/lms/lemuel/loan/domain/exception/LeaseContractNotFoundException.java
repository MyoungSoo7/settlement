package github.lms.lemuel.loan.domain.exception;

/**
 * 리스·할부 계약을 찾을 수 없음 — HTTP 404 로 매핑된다
 * ({@link SecuredLoanNotFoundException} 동형).
 *
 * <p><b>소유권 불일치와 구분한다</b> — 남의 계약을 조회하면 403(존재는 알린다)이 아니라 이 예외로
 * 404 를 준다. 계약 번호만 바꿔가며 존재 여부를 훑는 것을 막기 위해서다.
 */
public class LeaseContractNotFoundException extends RuntimeException {

    public LeaseContractNotFoundException(String message) {
        super(message);
    }
}
