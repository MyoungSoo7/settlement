package github.lms.lemuel.batch;

import github.lms.lemuel.domain.Payment;
import github.lms.lemuel.domain.Settlement;
import github.lms.lemuel.domain.SettlementAdjustment;
import github.lms.lemuel.event.SettlementIndexEvent;
import github.lms.lemuel.monitoring.SettlementBatchMetrics;
import github.lms.lemuel.repository.PaymentRepository;
import github.lms.lemuel.repository.SettlementAdjustmentRepository;
import github.lms.lemuel.repository.SettlementRepository;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class SettlementBatchService {

    private static final Logger logger = LoggerFactory.getLogger(SettlementBatchService.class);

    private final PaymentRepository paymentRepository;
    private final SettlementRepository settlementRepository;
    private final SettlementAdjustmentRepository settlementAdjustmentRepository;
    private final SettlementBatchMetrics batchMetrics;
    private final ApplicationEventPublisher eventPublisher;

    public SettlementBatchService(PaymentRepository paymentRepository,
        SettlementRepository settlementRepository,
        SettlementAdjustmentRepository settlementAdjustmentRepository,
        SettlementBatchMetrics batchMetrics,
        ApplicationEventPublisher eventPublisher) {
        this.paymentRepository = paymentRepository;
        this.settlementRepository = settlementRepository;
        this.settlementAdjustmentRepository = settlementAdjustmentRepository;
        this.batchMetrics = batchMetrics;
        this.eventPublisher = eventPublisher;
    }

    @Scheduled(cron = "0 0 2 * * *")
    @Transactional
    public void createDailySettlements() {
        Timer.Sample sample = batchMetrics.startTimer();

        try {
            LocalDate yesterday = LocalDate.now().minusDays(1);
            LocalDateTime startOfYesterday = yesterday.atStartOfDay();
            LocalDateTime endOfYesterday = yesterday.atTime(LocalTime.MAX);

            logger.info("정산 배치 시작: {} ~ {}", startOfYesterday, endOfYesterday);

            List<Payment> capturedPayments =
                paymentRepository.findCapturedPaymentsBetween(startOfYesterday, endOfYesterday);

            int totalPayments = capturedPayments.size();
            int createdCount = 0;
            List<Long> createdSettlementIds = new ArrayList<>();

            for (Payment payment : capturedPayments) {
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

                Settlement saved = settlementRepository.save(settlement);
                createdSettlementIds.add(saved.getId());
                createdCount++;
            }

            logger.info("정산 배치 완료: {} 건 생성됨 (전체 대상: {} 건)", createdCount, totalPayments);

            // Elasticsearch 비동기 인덱싱 이벤트 발행
            if (!createdSettlementIds.isEmpty()) {
                eventPublisher.publishEvent(
                    new SettlementIndexEvent(createdSettlementIds, SettlementIndexEvent.IndexEventType.BATCH_CREATED)
                );
                logger.info("Published settlement index event: count={}", createdSettlementIds.size());
            }

            // 메트릭 기록
            batchMetrics.incrementSettlementCreated(createdCount);
            batchMetrics.recordSettlementCreationDataVolume(totalPayments); // 히스토그램
            batchMetrics.stopAndRecordSettlementCreation(sample);
            batchMetrics.updateLastBatchRunTimestamp();

        } catch (Exception e) {
            logger.error("정산 생성 배치 실패", e);
            batchMetrics.recordBatchFailure("settlement_creation");
            throw e;
        }
    }

    @Scheduled(cron = "0 0 3 * * *")
    @Transactional
    public void confirmDailySettlements() {
        Timer.Sample sample = batchMetrics.startTimer();

        try {
            LocalDate yesterday = LocalDate.now().minusDays(1);

            logger.info("정산 확정 배치 시작: settlementDate={}", yesterday);

            // ⚠️ 메서드명이 findBySettlementDate면 상태 필터가 없을 수 있음 (아래 개선 포인트 참고)
            List<Settlement> settlements = settlementRepository.findBySettlementDate(yesterday);

            int totalSettlements = settlements.size();
            int confirmedCount = 0;
            List<Long> confirmedSettlementIds = new ArrayList<>();

            for (Settlement settlement : settlements) {
                if (settlement.getStatus() == Settlement.SettlementStatus.PENDING) {
                    settlement.setStatus(Settlement.SettlementStatus.CONFIRMED);
                    settlement.setConfirmedAt(LocalDateTime.now());
                    Settlement saved = settlementRepository.save(settlement);
                    confirmedSettlementIds.add(saved.getId());
                    confirmedCount++;
                }
            }

            logger.info("정산 확정 배치 완료: {} 건 확정됨 (전체 대상: {} 건)", confirmedCount, totalSettlements);

            // Elasticsearch 비동기 인덱싱 이벤트 발행
            if (!confirmedSettlementIds.isEmpty()) {
                eventPublisher.publishEvent(
                    new SettlementIndexEvent(confirmedSettlementIds, SettlementIndexEvent.IndexEventType.BATCH_CONFIRMED)
                );
                logger.info("Published settlement index event: count={}", confirmedSettlementIds.size());
            }

            // 메트릭 기록
            batchMetrics.incrementSettlementConfirmed(confirmedCount);
            batchMetrics.recordSettlementConfirmationDataVolume(totalSettlements); // 히스토그램
            batchMetrics.stopAndRecordSettlementConfirmation(sample);
            batchMetrics.updateLastBatchRunTimestamp();

        } catch (Exception e) {
            logger.error("정산 확정 배치 실패", e);
            batchMetrics.recordBatchFailure("settlement_confirmation");
            throw e;
        }
    }

    @Scheduled(cron = "0 10 3 * * *")
    @Transactional
    public void confirmDailySettlementAdjustments() {
        Timer.Sample sample = batchMetrics.startTimer();

        try {
            LocalDate yesterday = LocalDate.now().minusDays(1);

            logger.info("정산 조정 확정 배치 시작: adjustmentDate={}", yesterday);

            List<SettlementAdjustment> pendingAdjustments =
                settlementAdjustmentRepository.findPendingByAdjustmentDate(yesterday);

            int totalAdjustments = pendingAdjustments.size();
            int confirmedCount = 0;
            for (SettlementAdjustment adjustment : pendingAdjustments) {
                adjustment.setStatus(SettlementAdjustment.AdjustmentStatus.CONFIRMED);
                adjustment.setConfirmedAt(LocalDateTime.now());
                settlementAdjustmentRepository.save(adjustment);
                confirmedCount++;
            }

            logger.info("정산 조정 확정 배치 완료: {} 건 확정됨", confirmedCount);

            // 메트릭 기록
            batchMetrics.incrementAdjustmentConfirmed(confirmedCount);
            batchMetrics.recordAdjustmentConfirmationDataVolume(totalAdjustments); // 히스토그램
            batchMetrics.stopAndRecordAdjustmentConfirmation(sample);
            batchMetrics.updateLastBatchRunTimestamp();

        } catch (Exception e) {
            logger.error("정산 조정 확정 배치 실패", e);
            batchMetrics.recordBatchFailure("adjustment_confirmation");
            throw e;
        }
    }
}
