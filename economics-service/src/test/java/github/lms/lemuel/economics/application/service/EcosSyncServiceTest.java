package github.lms.lemuel.economics.application.service;

import github.lms.lemuel.economics.application.port.in.SyncResult;
import github.lms.lemuel.economics.application.port.out.EcosClientPort;
import github.lms.lemuel.economics.application.port.out.EcosClientPort.Observation;
import github.lms.lemuel.economics.application.port.out.LoadIndicatorPort;
import github.lms.lemuel.economics.application.port.out.SaveIndicatorValuePort;
import github.lms.lemuel.economics.domain.Indicator;
import github.lms.lemuel.economics.domain.IndicatorCycle;
import github.lms.lemuel.economics.domain.IndicatorValue;
import github.lms.lemuel.economics.domain.ValueSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EcosSyncServiceTest {

    private static final LocalDate FROM = LocalDate.of(2026, 1, 1);
    private static final LocalDate TO = LocalDate.of(2026, 6, 30);

    @Mock
    private EcosClientPort ecosClient;
    @Mock
    private LoadIndicatorPort loadIndicatorPort;
    @Mock
    private SaveIndicatorValuePort saveIndicatorValuePort;

    private EcosSyncService service;

    private final Indicator baseRate = new Indicator("BASE_RATE", "한국은행 기준금리", "%",
            IndicatorCycle.D, "722Y001", "0101000", null);
    private final Indicator cpi = new Indicator("CPI", "소비자물가지수", "2020=100",
            IndicatorCycle.M, "901Y009", "0", null);

    @BeforeEach
    void setUp() {
        // 테스트에서는 호출 간격 0 (sleep 없음)
        service = new EcosSyncService(ecosClient, loadIndicatorPort, saveIndicatorValuePort, 0L);
        lenient().when(ecosClient.isConfigured()).thenReturn(true);
    }

    @Test
    @DisplayName("API 키 미설정이면 IllegalStateException — 배치 시작 자체를 거부")
    void requiresApiKey() {
        when(ecosClient.isConfigured()).thenReturn(false);

        assertThatIllegalStateException()
                .isThrownBy(() -> service.syncIndicators(null, FROM, TO))
                .withMessageContaining("ECOS API 키가 설정되지 않았습니다");
    }

    @Test
    @DisplayName("from > to 면 IllegalArgumentException")
    void rejectsInvertedRange() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.syncIndicators(null, TO, FROM));
    }

    @Test
    @DisplayName("전체 동기화 — 카탈로그 전 지표를 fetch 해 관측치 upsert 집계")
    void syncsWholeCatalog() {
        when(loadIndicatorPort.findAll()).thenReturn(List.of(baseRate, cpi));
        when(ecosClient.fetchObservations(eq(baseRate), any(), any())).thenReturn(List.of(
                new Observation(LocalDate.of(2026, 5, 1), new BigDecimal("3.00")),
                new Observation(LocalDate.of(2026, 6, 1), new BigDecimal("3.25"))));
        when(ecosClient.fetchObservations(eq(cpi), any(), any())).thenReturn(List.of(
                new Observation(LocalDate.of(2026, 6, 1), new BigDecimal("114.5"))));

        SyncResult result = service.syncIndicators(null, FROM, TO);

        assertThat(result).isEqualTo(new SyncResult(2, 3, 0, 0));
        ArgumentCaptor<IndicatorValue> captor = ArgumentCaptor.forClass(IndicatorValue.class);
        verify(saveIndicatorValuePort, times(3)).upsert(captor.capture());
        assertThat(captor.getAllValues()).allMatch(v -> v.source() == ValueSource.ECOS);
    }

    @Test
    @DisplayName("특정 code 동기화 — 그 지표만 fetch, findAll 호출 없음")
    void syncsSpecificCode() {
        when(loadIndicatorPort.findByCode("BASE_RATE")).thenReturn(Optional.of(baseRate));
        when(ecosClient.fetchObservations(eq(baseRate), any(), any())).thenReturn(List.of(
                new Observation(LocalDate.of(2026, 6, 1), new BigDecimal("3.25"))));

        SyncResult result = service.syncIndicators("BASE_RATE", FROM, TO);

        assertThat(result).isEqualTo(new SyncResult(1, 1, 0, 0));
        verify(loadIndicatorPort, never()).findAll();
    }

    @Test
    @DisplayName("존재하지 않는 code → IllegalArgumentException")
    void rejectsUnknownCode() {
        when(loadIndicatorPort.findByCode("NOPE")).thenReturn(Optional.empty());

        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.syncIndicators("NOPE", FROM, TO));
        verify(saveIndicatorValuePort, never()).upsert(any());
    }

    @Test
    @DisplayName("관측치 0건 지표는 skipped 로 집계")
    void countsEmptyAsSkipped() {
        when(loadIndicatorPort.findByCode("BASE_RATE")).thenReturn(Optional.of(baseRate));
        when(ecosClient.fetchObservations(eq(baseRate), any(), any())).thenReturn(List.of());

        SyncResult result = service.syncIndicators("BASE_RATE", FROM, TO);

        assertThat(result).isEqualTo(new SyncResult(1, 0, 1, 0));
        verify(saveIndicatorValuePort, never()).upsert(any());
    }

    @Test
    @DisplayName("upsert 후 실 ECOS 최신일 이후의 후행 SEED 를 제거한다 (헤드라인 실데이터 보장)")
    void purgesTrailingSeedAfterUpsert() {
        when(loadIndicatorPort.findByCode("USD_KRW")).thenReturn(Optional.of(
                new Indicator("USD_KRW", "원/달러 환율", "KRW", IndicatorCycle.D, "731Y001", "0000001", null)));
        when(ecosClient.fetchObservations(any(), any(), any())).thenReturn(List.of(
                new Observation(LocalDate.of(2026, 7, 14), new BigDecimal("1375")),
                new Observation(LocalDate.of(2026, 7, 15), new BigDecimal("1378"))));

        service.syncIndicators("USD_KRW", FROM, TO);

        // 관측치 중 가장 늦은 날(07-15) 이후의 SEED(가짜 07-16 등)를 잘라야 한다
        verify(saveIndicatorValuePort).purgeSeedNewerThan("USD_KRW", LocalDate.of(2026, 7, 15));
    }

    @Test
    @DisplayName("관측치 0건이면 후행 SEED 제거도 하지 않는다 (실데이터 없으면 시드 폴백 보존)")
    void doesNotPurgeWhenNoObservations() {
        when(loadIndicatorPort.findByCode("USD_KRW")).thenReturn(Optional.of(
                new Indicator("USD_KRW", "원/달러 환율", "KRW", IndicatorCycle.D, "731Y001", "0000001", null)));
        when(ecosClient.fetchObservations(any(), any(), any())).thenReturn(List.of());

        service.syncIndicators("USD_KRW", FROM, TO);

        verify(saveIndicatorValuePort, never()).purgeSeedNewerThan(any(), any());
    }

    @Test
    @DisplayName("한 지표 fetch 가 예외 → failed 집계 후 나머지 지표는 계속 진행")
    void isolatesPerIndicatorFailure() {
        when(loadIndicatorPort.findAll()).thenReturn(List.of(baseRate, cpi));
        when(ecosClient.fetchObservations(eq(baseRate), any(), any()))
                .thenThrow(new RuntimeException("ECOS 오류"));
        when(ecosClient.fetchObservations(eq(cpi), any(), any())).thenReturn(List.of(
                new Observation(LocalDate.of(2026, 6, 1), new BigDecimal("114.5"))));

        SyncResult result = service.syncIndicators(null, FROM, TO);

        assertThat(result).isEqualTo(new SyncResult(2, 1, 0, 1));
        verify(saveIndicatorValuePort, times(1)).upsert(any());
    }
}
