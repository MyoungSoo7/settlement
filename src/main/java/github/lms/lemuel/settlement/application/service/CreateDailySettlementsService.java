package github.lms.lemuel.settlement.application.service;

import github.lms.lemuel.ledger.application.port.in.RecordJournalEntryUseCase;
import github.lms.lemuel.ledger.domain.Money;
import github.lms.lemuel.seller.application.port.out.LoadSellerPort;
import github.lms.lemuel.seller.domain.Seller;
import github.lms.lemuel.seller.domain.SellerStatus;
import github.lms.lemuel.settlement.application.port.in.CreateDailySettlementsUseCase;
import github.lms.lemuel.settlement.application.port.out.LoadCapturedPaymentsPort;
import github.lms.lemuel.settlement.application.port.out.LoadCapturedPaymentsPort.CapturedPaymentInfo;
import github.lms.lemuel.settlement.application.port.out.SaveSettlementPort;
import github.lms.lemuel.settlement.application.port.out.SettlementSearchIndexPort;
import github.lms.lemuel.settlement.domain.Settlement;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class CreateDailySettlementsService implements CreateDailySettlementsUseCase {

    private final LoadCapturedPaymentsPort loadCapturedPaymentsPort;
    private final SaveSettlementPort saveSettlementPort;
    private final SettlementSearchIndexPort settlementSearchIndexPort;
    private final RecordJournalEntryUseCase recordJournalEntryUseCase;
    private final LoadSellerPort loadSellerPort;

    @Override
    public CreateSettlementResult createDailySettlements(CreateSettlementCommand command) {
        log.info("일일 정산 생성 시작: targetDate={}", command.targetDate());

        // 1. 승인된 판매자 중 오늘 정산 대상인 판매자 필터링
        List<Seller> dueSellers = loadSellerPort.findByStatus(SellerStatus.APPROVED).stream()
                .filter(seller -> seller.isSettlementDueOn(command.targetDate()))
                .toList();

        if (dueSellers.isEmpty()) {
            log.info("정산 대상 판매자 없음: targetDate={}", command.targetDate());
            return new CreateSettlementResult(command.targetDate(), 0, 0);
        }

        log.info("정산 대상 판매자 {}명", dueSellers.size());

        List<Settlement> allSaved = new ArrayList<>();
        int totalPayments = 0;

        // 2. 판매자별 결제 조회 + 정산 생성
        for (Seller seller : dueSellers) {
            List<CapturedPaymentInfo> payments =
                    loadCapturedPaymentsPort.findCapturedPaymentsByDateAndSeller(
                            command.targetDate(), seller.getId());

            if (payments.isEmpty()) continue;

            totalPayments += payments.size();

            for (CapturedPaymentInfo payment : payments) {
                Settlement settlement = Settlement.createFromPayment(
                        payment.paymentId(),
                        payment.orderId(),
                        seller.getId(),
                        payment.amount(),
                        seller.getCommissionRate(),
                        command.targetDate()
                );

                Settlement saved = saveSettlementPort.save(settlement);

                // 3. Ledger 분개 기록
                try {
                    recordJournalEntryUseCase.recordSettlementCreated(
                            saved.getId(),
                            seller.getId(),
                            Money.krw(saved.getPaymentAmount()),
                            Money.krw(saved.getCommission())
                    );
                } catch (Exception e) {
                    log.error("Ledger 분개 실패 (정산 생성은 성공): settlementId={}", saved.getId(), e);
                }

                allSaved.add(saved);

                log.debug("정산 생성: sellerId={}, paymentId={}, amount={}, commission={}, net={}",
                        seller.getId(), payment.paymentId(), payment.amount(),
                        saved.getCommission(), saved.getNetAmount());
            }
        }

        log.info("정산 {}건 저장 완료 (결제 {}건)", allSaved.size(), totalPayments);

        // 4. Elasticsearch 비동기 인덱싱
        if (settlementSearchIndexPort.isSearchEnabled() && !allSaved.isEmpty()) {
            try {
                settlementSearchIndexPort.bulkIndexSettlements(allSaved);
            } catch (Exception e) {
                log.error("Elasticsearch 인덱싱 실패: targetDate={}", command.targetDate(), e);
            }
        }

        return new CreateSettlementResult(command.targetDate(), totalPayments, allSaved.size());
    }
}
