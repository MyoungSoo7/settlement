package github.lms.lemuel.settlement.application.service;

import github.lms.lemuel.settlement.application.port.in.CreateDailySettlementsUseCase.CreateSettlementCommand;
import github.lms.lemuel.settlement.application.port.in.CreateDailySettlementsUseCase.CreateSettlementResult;
import github.lms.lemuel.settlement.application.port.out.LoadCapturedPaymentsPort;
import github.lms.lemuel.settlement.application.port.out.LoadCapturedPaymentsPort.CapturedPaymentInfo;
import github.lms.lemuel.settlement.application.port.out.SaveSettlementPort;
import github.lms.lemuel.settlement.application.port.out.SettlementSearchIndexPort;
import github.lms.lemuel.settlement.domain.Settlement;
import github.lms.lemuel.settlement.domain.SettlementStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("CreateDailySettlementsService")
class CreateDailySettlementsServiceTest {

    @Mock private LoadCapturedPaymentsPort loadCapturedPaymentsPort;
    @Mock private SaveSettlementPort saveSettlementPort;
    @Mock private SettlementSearchIndexPort settlementSearchIndexPort;

    @InjectMocks
    private CreateDailySettlementsService service;

    private final LocalDate TARGET_DATE = LocalDate.of(2026, 1, 15);

    /** 테스트용 CapturedPaymentInfo 생성 헬퍼 (capturedAt 포함) */
    private CapturedPaymentInfo payment(Long paymentId, Long orderId, BigDecimal amount) {
        return new CapturedPaymentInfo(paymentId, orderId, amount, LocalDateTime.now());
    }

    @Nested
    @DisplayName("정산 생성 성공")
    class Success {

        @Test
        @DisplayName("승인된 결제 2건에 대해 정산 2건을 생성하고 저장한다")
        void createDailySettlements_twoPayments_createsTwoSettlements() {
            List<CapturedPaymentInfo> payments = List.of(
                payment(1L, 10L, new BigDecimal("100000")),
                payment(2L, 11L, new BigDecimal("50000"))
            );
            given(loadCapturedPaymentsPort.findCapturedPaymentsByDate(TARGET_DATE)).willReturn(payments);

            AtomicLong idSeq = new AtomicLong(1);
            given(saveSettlementPort.save(any(Settlement.class))).willAnswer(inv -> {
                Settlement s = inv.getArgument(0);
                s.setId(idSeq.getAndIncrement());
                return s;
            });
            given(settlementSearchIndexPort.isSearchEnabled()).willReturn(false);

            CreateSettlementResult result = service.createDailySettlements(new CreateSettlementCommand(TARGET_DATE));

            // CreateSettlementResult(targetDate, totalPayments, createdCount)
            assertThat(result.totalPayments()).isEqualTo(2);
            assertThat(result.createdCount()).isEqualTo(2);
            assertThat(result.targetDate()).isEqualTo(TARGET_DATE);
            then(saveSettlementPort).should(times(2)).save(any(Settlement.class));
        }

        @Test
        @DisplayName("생성된 정산의 초기 상태는 REQUESTED이다")
        void createDailySettlements_initialStatusIsRequested() {
            List<CapturedPaymentInfo> payments = List.of(
                payment(1L, 10L, new BigDecimal("100000"))
            );
            given(loadCapturedPaymentsPort.findCapturedPaymentsByDate(TARGET_DATE)).willReturn(payments);
            given(saveSettlementPort.save(any(Settlement.class))).willAnswer(inv -> {
                Settlement s = inv.getArgument(0);
                s.setId(1L);
                return s;
            });
            given(settlementSearchIndexPort.isSearchEnabled()).willReturn(false);

            service.createDailySettlements(new CreateSettlementCommand(TARGET_DATE));

            then(saveSettlementPort).should().save(
                argThat(s ->
                    s.getStatus() == SettlementStatus.REQUESTED
                    && s.getCommission() != null
                    && s.getNetAmount() != null
                )
            );
        }

        @Test
        @DisplayName("ES 검색이 활성화된 경우 bulkIndex를 호출한다")
        void createDailySettlements_whenSearchEnabled_callsBulkIndex() {
            List<CapturedPaymentInfo> payments = List.of(
                payment(1L, 10L, new BigDecimal("100000"))
            );
            given(loadCapturedPaymentsPort.findCapturedPaymentsByDate(TARGET_DATE)).willReturn(payments);
            given(saveSettlementPort.save(any(Settlement.class))).willAnswer(inv -> {
                Settlement s = inv.getArgument(0);
                s.setId(1L);
                return s;
            });
            given(settlementSearchIndexPort.isSearchEnabled()).willReturn(true);

            service.createDailySettlements(new CreateSettlementCommand(TARGET_DATE));

            then(settlementSearchIndexPort).should().bulkIndexSettlements(anyList());
        }

        @Test
        @DisplayName("ES 검색이 비활성화된 경우 bulkIndex를 호출하지 않는다")
        void createDailySettlements_whenSearchDisabled_skipsBulkIndex() {
            List<CapturedPaymentInfo> payments = List.of(
                payment(1L, 10L, new BigDecimal("100000"))
            );
            given(loadCapturedPaymentsPort.findCapturedPaymentsByDate(TARGET_DATE)).willReturn(payments);
            given(saveSettlementPort.save(any(Settlement.class))).willAnswer(inv -> {
                Settlement s = inv.getArgument(0);
                s.setId(1L);
                return s;
            });
            given(settlementSearchIndexPort.isSearchEnabled()).willReturn(false);

            service.createDailySettlements(new CreateSettlementCommand(TARGET_DATE));

            then(settlementSearchIndexPort).should(never()).bulkIndexSettlements(anyList());
        }

        @Test
        @DisplayName("ES 인덱싱 실패해도 정산 생성 결과를 반환한다")
        void createDailySettlements_esFailure_stillReturnsResult() {
            List<CapturedPaymentInfo> payments = List.of(
                payment(1L, 10L, new BigDecimal("100000"))
            );
            given(loadCapturedPaymentsPort.findCapturedPaymentsByDate(TARGET_DATE)).willReturn(payments);
            given(saveSettlementPort.save(any(Settlement.class))).willAnswer(inv -> {
                Settlement s = inv.getArgument(0);
                s.setId(1L);
                return s;
            });
            given(settlementSearchIndexPort.isSearchEnabled()).willReturn(true);
            // void 메서드 stubbing: willThrow를 먼저 체이닝
            willThrow(new RuntimeException("ES 연결 실패"))
                .given(settlementSearchIndexPort).bulkIndexSettlements(anyList());

            CreateSettlementResult result = service.createDailySettlements(new CreateSettlementCommand(TARGET_DATE));

            assertThat(result.createdCount()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("대상 결제 없음")
    class NoCapturedPayments {

        @Test
        @DisplayName("승인된 결제가 없으면 totalPayments=0을 반환하고 저장하지 않는다")
        void createDailySettlements_noPayments_returnsZero() {
            given(loadCapturedPaymentsPort.findCapturedPaymentsByDate(TARGET_DATE)).willReturn(List.of());

            CreateSettlementResult result = service.createDailySettlements(new CreateSettlementCommand(TARGET_DATE));

            assertThat(result.totalPayments()).isEqualTo(0);
            assertThat(result.createdCount()).isEqualTo(0);
            then(saveSettlementPort).should(never()).save(any(Settlement.class));
        }
    }
}