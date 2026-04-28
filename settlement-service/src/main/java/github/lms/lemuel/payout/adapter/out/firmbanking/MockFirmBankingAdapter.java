package github.lms.lemuel.payout.adapter.out.firmbanking;

import github.lms.lemuel.payout.application.port.out.FirmBankingPort;
import github.lms.lemuel.payout.domain.SellerBankAccount;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 펌뱅킹 mock 어댑터 — 운영에서는 KB/신한/NH 펌뱅킹 API 또는 토스페이먼츠 송금 API 어댑터로 교체.
 *
 * <p>{@code app.firmbanking.failure-rate} 비율로 무작위 실패를 시뮬레이션해 운영자 콘솔의
 * FAILED 처리 흐름을 검증할 수 있게 한다 (시연·테스트 환경).
 */
@Component
public class MockFirmBankingAdapter implements FirmBankingPort {

    private static final Logger log = LoggerFactory.getLogger(MockFirmBankingAdapter.class);

    @Value("${app.firmbanking.failure-rate:0.0}")
    private double failureRate;

    @Override
    public String send(SellerBankAccount account, BigDecimal amount, String referenceId)
            throws FirmBankingException {
        // 실 운영: HTTPS POST to bank/PG API + Resilience4j circuit breaker
        if (ThreadLocalRandom.current().nextDouble() < failureRate) {
            log.warn("[FirmBanking-MOCK] 시뮬 실패: ref={}, account={}, amount={}",
                    referenceId, account.maskedAccountNumber(), amount);
            throw new FirmBankingException("MOCK_RANDOM_FAIL",
                    "Mock 펌뱅킹 시뮬레이션 실패 (운영자 retry 워크플로 검증용)");
        }
        String txnId = "FB-" + UUID.randomUUID();
        log.info("[FirmBanking-MOCK] 송금 완료: ref={}, bank={}, account={}, amount={}, txnId={}",
                referenceId, account.bankCode(), account.maskedAccountNumber(), amount, txnId);
        return txnId;
    }
}
