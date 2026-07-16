package github.lms.lemuel.loan.application.port.in;

import github.lms.lemuel.loan.domain.CorporateLoan;

import java.math.BigDecimal;

/**
 * 기업 신용대출 신청 인바운드 포트. 신용평가 통과 시 수수료를 산정해 REQUESTED 로 등록한다.
 */
public interface RequestCorporateLoanUseCase {

    CorporateLoan request(RequestCorporateLoanCommand command);

    /**
     * @param stockCode   신청 상장사 종목코드(6자리)
     * @param principal   대출 신청 원금
     * @param termDays    대출 기간(일) — 수수료 산정 기준
     * @param ownerUserId 신청자(JWT 주체 userId) — 소유권 스코핑용. 미인증 파생 불가 시 null 이 아니라 403 이 앞단에서 난다.
     */
    record RequestCorporateLoanCommand(String stockCode, BigDecimal principal, int termDays, Long ownerUserId) {
    }
}
