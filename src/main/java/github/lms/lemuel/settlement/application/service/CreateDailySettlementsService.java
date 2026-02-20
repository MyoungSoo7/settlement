package github.lms.lemuel.settlement.application.service;

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

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class CreateDailySettlementsService implements CreateDailySettlementsUseCase {

    private final LoadCapturedPaymentsPort loadCapturedPaymentsPort;
    private final SaveSettlementPort saveSettlementPort;
    private final SettlementSearchIndexPort settlementSearchIndexPort;

    @Override
    public CreateSettlementResult createDailySettlements(CreateSettlementCommand command) {
        log.info("일일 정산 생성 시작: targetDate={}", command.targetDate());

        // 1. 특정 날짜의 승인된 결제 내역 조회
        List<CapturedPaymentInfo> capturedPayments = 
                loadCapturedPaymentsPort.findCapturedPaymentsByDate(command.targetDate());

        if (capturedPayments.isEmpty()) {
            log.info("정산 대상 결제 없음: targetDate={}", command.targetDate());
            return new CreateSettlementResult(command.targetDate(), 0, 0);
        }

        log.info("정산 대상 결제 {}건 발견", capturedPayments.size());

        // 2. 각 결제에 대해 정산 생성
        List<Settlement> settlements = capturedPayments.stream()
                .map(payment -> {
                    // Settlement 도메인 생성 (수수료 계산 포함)
                    Settlement settlement = Settlement.createFromPayment(
                            payment.paymentId(),
                            payment.orderId(),
                            payment.amount(),
                            command.targetDate()
                    );
                    
                    log.debug("정산 생성: paymentId={}, amount={}, commission={}, netAmount={}",
                            payment.paymentId(), payment.amount(), 
                            settlement.getCommission(), settlement.getNetAmount());
                    
                    return settlement;
                })
                .collect(Collectors.toList());

        // 3. 정산 저장
        List<Settlement> savedSettlements = settlements.stream()
                .map(saveSettlementPort::save)
                .collect(Collectors.toList());

        log.info("정산 {}건 저장 완료", savedSettlements.size());

        // 4. Elasticsearch 비동기 인덱싱 (검색 활성화된 경우만)
        if (settlementSearchIndexPort.isSearchEnabled()) {
            try {
                settlementSearchIndexPort.bulkIndexSettlements(savedSettlements);
                log.info("Elasticsearch 비동기 인덱싱 요청 완료: {}건", savedSettlements.size());
            } catch (Exception e) {
                log.error("Elasticsearch 인덱싱 실패 (정산 생성은 성공): targetDate={}", 
                        command.targetDate(), e);
                // 인덱싱 실패해도 정산 생성은 성공으로 처리
            }
        }

        return new CreateSettlementResult(
                command.targetDate(),
                capturedPayments.size(),
                savedSettlements.size()
        );
    }
}
