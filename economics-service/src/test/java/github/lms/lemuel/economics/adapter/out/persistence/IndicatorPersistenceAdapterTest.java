package github.lms.lemuel.economics.adapter.out.persistence;

import github.lms.lemuel.economics.domain.Indicator;
import github.lms.lemuel.economics.domain.IndicatorCycle;
import github.lms.lemuel.economics.domain.IndicatorValue;
import github.lms.lemuel.economics.domain.ValueSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Limit;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * IndicatorPersistenceAdapter — 카탈로그/관측치 조회 매핑·upsert(신규/기존 병합)와,
 * 그에 딸린 JPA 엔티티 fromDomain/applyDomain/toDomain 왕복 매핑을 Mockito 리포지토리로 검증.
 */
@ExtendWith(MockitoExtension.class)
class IndicatorPersistenceAdapterTest {

    @Mock
    private IndicatorRepository indicatorRepository;
    @Mock
    private IndicatorValueRepository indicatorValueRepository;

    private final Indicator baseRate = new Indicator("BASE_RATE", "한국은행 기준금리", "%",
            IndicatorCycle.D, "722Y001", "0101000", Instant.parse("2026-01-01T00:00:00Z"));

    private IndicatorValue value(LocalDate date, String v) {
        return new IndicatorValue(null, "BASE_RATE", date, new BigDecimal(v),
                ValueSource.ECOS, Instant.parse("2026-01-01T00:00:00Z"));
    }

    private IndicatorPersistenceAdapter adapter() {
        return new IndicatorPersistenceAdapter(indicatorRepository, indicatorValueRepository);
    }

    @Test
    @DisplayName("findAll / findByCode — 지표 엔티티 매핑")
    void indicatorLookups() {
        when(indicatorRepository.findAll())
                .thenReturn(List.of(IndicatorJpaEntity.fromDomain(baseRate)));
        when(indicatorRepository.findById("BASE_RATE"))
                .thenReturn(Optional.of(IndicatorJpaEntity.fromDomain(baseRate)));

        List<Indicator> all = adapter().findAll();
        assertThat(all).hasSize(1);
        assertThat(all.get(0).code()).isEqualTo("BASE_RATE");
        assertThat(all.get(0).name()).isEqualTo("한국은행 기준금리");
        assertThat(all.get(0).cycle()).isEqualTo(IndicatorCycle.D);

        assertThat(adapter().findByCode("BASE_RATE")).map(Indicator::unit).contains("%");
    }

    @Test
    @DisplayName("findLatest — Limit 적용, 관측치 엔티티→도메인 매핑")
    void findLatest() {
        when(indicatorValueRepository.findByIndicatorCodeOrderByObservedDateDesc(eq("BASE_RATE"), any(Limit.class)))
                .thenReturn(List.of(IndicatorValueJpaEntity.fromDomain(value(LocalDate.of(2026, 6, 1), "3.25"))));

        List<IndicatorValue> latest = adapter().findLatest("BASE_RATE", 5);

        assertThat(latest).hasSize(1);
        assertThat(latest.get(0).observedDate()).isEqualTo(LocalDate.of(2026, 6, 1));
        assertThat(latest.get(0).value()).isEqualByComparingTo("3.25");
        assertThat(latest.get(0).source()).isEqualTo(ValueSource.ECOS);
    }

    @Test
    @DisplayName("findSeries — 기간 조회 매핑")
    void findSeries() {
        LocalDate from = LocalDate.of(2026, 1, 1);
        LocalDate to = LocalDate.of(2026, 6, 30);
        when(indicatorValueRepository
                .findByIndicatorCodeAndObservedDateBetweenOrderByObservedDateAsc("BASE_RATE", from, to))
                .thenReturn(List.of(
                        IndicatorValueJpaEntity.fromDomain(value(LocalDate.of(2026, 1, 1), "3.00")),
                        IndicatorValueJpaEntity.fromDomain(value(LocalDate.of(2026, 6, 1), "3.25"))));

        List<IndicatorValue> series = adapter().findSeries("BASE_RATE", from, to);

        assertThat(series).hasSize(2);
        assertThat(series.get(0).value()).isEqualByComparingTo("3.00");
    }

    @Test
    @DisplayName("upsert — 신규는 fromDomain 저장")
    void upsertNew() {
        when(indicatorValueRepository.findByIndicatorCodeAndObservedDate("BASE_RATE", LocalDate.of(2026, 6, 1)))
                .thenReturn(Optional.empty());

        adapter().upsert(value(LocalDate.of(2026, 6, 1), "3.25"));

        verify(indicatorValueRepository).save(any(IndicatorValueJpaEntity.class));
    }

    @Test
    @DisplayName("upsert — 기존 관측치면 applyDomain 병합 저장")
    void upsertExisting() {
        IndicatorValueJpaEntity existing = IndicatorValueJpaEntity.fromDomain(
                new IndicatorValue(7L, "BASE_RATE", LocalDate.of(2026, 6, 1),
                        new BigDecimal("3.00"), ValueSource.SEED, Instant.parse("2025-01-01T00:00:00Z")));
        when(indicatorValueRepository.findByIndicatorCodeAndObservedDate("BASE_RATE", LocalDate.of(2026, 6, 1)))
                .thenReturn(Optional.of(existing));

        adapter().upsert(value(LocalDate.of(2026, 6, 1), "3.25"));

        ArgumentCaptor<IndicatorValueJpaEntity> captor =
                ArgumentCaptor.forClass(IndicatorValueJpaEntity.class);
        verify(indicatorValueRepository).save(captor.capture());
        IndicatorValue merged = captor.getValue().toDomain();
        assertThat(merged.value()).isEqualByComparingTo("3.25");
        assertThat(merged.source()).isEqualTo(ValueSource.ECOS);
    }
}
