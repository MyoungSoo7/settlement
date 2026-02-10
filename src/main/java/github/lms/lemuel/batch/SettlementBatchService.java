package github.lms.lemuel.batch;

import github.lms.lemuel.domain.Payment;
import github.lms.lemuel.domain.Settlement;
import github.lms.lemuel.repository.PaymentRepository;
import github.lms.lemuel.repository.SettlementRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

@Service
public class SettlementBatchService {

    private static final Logger logger = LoggerFactory.getLogger(SettlementBatchService.class);

    private final PaymentRepository paymentRepository;
    private final SettlementRepository settlementRepository;

    public SettlementBatchService(PaymentRepository paymentRepository, SettlementRepository settlementRepository) {
        this.paymentRepository = paymentRepository;
        this.settlementRepository = settlementRepository;
    }

    /**
     * 매일 새벽 2시에 전날 CAPTURED 상태의 결제를 정산 대상으로 생성
     * 크론 표현식: 초 분 시 일 월 요일
     * "0 0 2 * * *" = 매일 2시 0분 0초
     */
    @Scheduled(cron = "0 0 2 * * *")
    @Transactional
    public void createDailySettlements() {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        LocalDateTime startOfYesterday = yesterday.atStartOfDay();
        LocalDateTime endOfYesterday = yesterday.atTime(LocalTime.MAX);

        logger.info("정산 배치 시작: {} ~ {}", startOfYesterday, endOfYesterday);

        // 전날 CAPTURED 상태의 결제 조회
        List<Payment> capturedPayments = paymentRepository.findCapturedPaymentsBetween(
                startOfYesterday, endOfYesterday
        );

        int createdCount = 0;
        for (Payment payment : capturedPayments) {
            // 이미 정산 대상이 생성된 경우 스킵
            if (settlementRepository.findByPaymentId(payment.getId()).isPresent()) {
                logger.debug("이미 정산 대상이 존재함: paymentId={}", payment.getId());
                continue;
            }

            Settlement settlement = new Settlement();
            settlement.setPaymentId(payment.getId());
            settlement.setOrderId(payment.getOrderId());
            settlement.setAmount(payment.getAmount());
            settlement.setStatus(Settlement.SettlementStatus.PENDING);
            settlement.setSettlementDate(yesterday);

            settlementRepository.save(settlement);
            createdCount++;
            logger.debug("정산 대상 생성: paymentId={}, amount={}", payment.getId(), payment.getAmount());
        }

        logger.info("정산 배치 완료: {} 건 생성됨", createdCount);
    }

    /**
     * 매일 새벽 3시에 전날 생성된 PENDING 상태의 정산을 CONFIRMED로 확정
     * "0 0 3 * * *" = 매일 3시 0분 0초
     */
    @Scheduled(cron = "0 0 3 * * *")
    @Transactional
    public void confirmDailySettlements() {
        LocalDate yesterday = LocalDate.now().minusDays(1);

        logger.info("정산 확정 배치 시작: settlementDate={}", yesterday);

        List<Settlement> pendingSettlements = settlementRepository.findBySettlementDate(yesterday);

        int confirmedCount = 0;
        for (Settlement settlement : pendingSettlements) {
            if (settlement.getStatus() == Settlement.SettlementStatus.PENDING) {
                settlement.setStatus(Settlement.SettlementStatus.CONFIRMED);
                settlement.setConfirmedAt(LocalDateTime.now());
                settlementRepository.save(settlement);
                confirmedCount++;
                logger.debug("정산 확정: settlementId={}, amount={}", settlement.getId(), settlement.getAmount());
            }
        }

        logger.info("정산 확정 배치 완료: {} 건 확정됨", confirmedCount);
    }
}
