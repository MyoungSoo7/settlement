package github.lms.lemuel.loan.application.port.in;

import github.lms.lemuel.loan.domain.AssetFinanceType;
import github.lms.lemuel.loan.domain.EarlyTerminationQuote;
import github.lms.lemuel.loan.domain.LeaseContract;

import java.math.BigDecimal;

/**
 * 리스·할부 계약 인바운드 포트 — 신청부터 종료까지.
 *
 * <p>운영자 조작(승인·거절·인도개시·연체·기한이익상실)과 차주 조작(신청·중도해지 요청)이 한 포트에
 * 있지만, <b>권한 판정은 어댑터(컨트롤러)가</b> JWT 로 한다. 유스케이스는 소유권 대조만 책임진다 —
 * 소유권은 도메인 규칙이고 역할은 웹 계약이기 때문이다.
 */
public interface ManageLeaseContractUseCase {

    /** 신규 신청 — 스케줄은 이 시점 입력으로 확정된다. */
    LeaseContract apply(ApplyLeaseCommand command);

    /** 심사 승인(운영자). */
    LeaseContract approve(Long contractId);

    /** 심사 거절(운영자). */
    LeaseContract reject(Long contractId);

    /** 승인 후 인도 전 취소(운영자). */
    LeaseContract cancel(Long contractId);

    /** 물건 인도 완료 → 계약 개시(운영자). */
    LeaseContract activate(Long contractId);

    /** 회차 수납. 연체 중이었다면 정상화된다. */
    LeaseContract payInstallment(Long contractId);

    /** 회차 미납 처리(운영자). */
    LeaseContract markOverdue(Long contractId);

    /** 기한이익상실(운영자) — 연체를 거쳐야만 가능하다. */
    LeaseContract markDefaulted(Long contractId);

    /** 만기 종료(운영자) — 전 회차 수납 후에만. */
    LeaseContract mature(Long contractId);

    /** 중도해지 정산액 조회 — 상태를 바꾸지 않는다. 차주 조회 시 소유권을 대조한다. */
    EarlyTerminationQuote quoteEarlyTermination(Long contractId, BigDecimal penaltyRatePercent,
                                                Long requesterUserId);

    /** 중도해지 확정 — 정산서를 산정하고 계약을 종결한다(운영자). */
    EarlyTerminationQuote terminateEarly(Long contractId, BigDecimal penaltyRatePercent);

    /**
     * 신청 커맨드 — 차주 식별자는 <b>JWT 주체에서 파생</b>해 채운다(요청 바디로 받지 않는다, IDOR 방지).
     */
    record ApplyLeaseCommand(
            Long borrowerUserId,
            String borrowerName,
            String borrowerRegistrationNo,
            AssetFinanceType financeType,
            String assetDescription,
            BigDecimal acquisitionCost,
            BigDecimal downPayment,
            BigDecimal deposit,
            BigDecimal residualValue,
            int termMonths,
            BigDecimal annualRatePercent) {

        /** 법인 차주인가 — 사업자번호 유무로 가른다. */
        public boolean isCorporate() {
            return borrowerRegistrationNo != null && !borrowerRegistrationNo.isBlank();
        }
    }
}
