package github.lms.lemuel.company.adapter.out.persistence;

import github.lms.lemuel.company.application.port.out.LoadWorkforceComparisonPort.GroupStatistics;
import github.lms.lemuel.company.domain.AggregateRowTally;
import github.lms.lemuel.company.domain.ComparisonAxis;
import github.lms.lemuel.company.domain.ComparisonLevel;
import github.lms.lemuel.company.domain.CompanyWorkforce;
import github.lms.lemuel.company.domain.WorkforceMetric;
import github.lms.lemuel.company.domain.WorkplaceKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class WorkforceComparisonPersistenceAdaptersTest {

    private static final WorkplaceKey KEY = WorkplaceKey.of("주식회사에고이즘", "866759", "2026-06");

    @Nested
    class ComparisonQueryAdapter {

        private final CompanyWorkforceRepository repository = mock(CompanyWorkforceRepository.class);
        private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        private final WorkforceComparisonPersistenceAdapter adapter =
                new WorkforceComparisonPersistenceAdapter(repository, jdbcTemplate);

        private CompanyWorkforceJpaEntity entity() throws Exception {
            CompanyWorkforceJpaEntity e = new CompanyWorkforceJpaEntity();
            for (String[] pair : List.of(new String[]{"workplaceName", "주식회사에고이즘"},
                    new String[]{"bizRegNoPrefix", "866759"}, new String[]{"industryCode", "525101"},
                    new String[]{"industryName", "전자상거래 소매업"},
                    new String[]{"address", "서울특별시 성동구 연무장19길"}, new String[]{"snapshotMonth", "2026-06"})) {
                set(e, pair[0], pair[1]);
            }
            set(e, "headcount", 50);
            set(e, "monthlyBilledAmount", new BigDecimal("16406250"));
            set(e, "createdAt", Instant.parse("2026-07-30T00:00:00Z"));
            return e;
        }

        private void set(Object target, String fieldName, Object value) throws Exception {
            Field field = CompanyWorkforceJpaEntity.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        }

        @Test
        @DisplayName("findByKey — 복합키 3필드로 조회해 도메인으로 매핑한다")
        void findByKey() throws Exception {
            when(repository.findByWorkplaceNameAndBizRegNoPrefixAndSnapshotMonth(
                    "주식회사에고이즘", "866759", "2026-06")).thenReturn(Optional.of(entity()));

            Optional<CompanyWorkforce> found = adapter.findByKey(KEY);

            assertTrue(found.isPresent());
            assertEquals(Optional.of("525101"), found.get().industryGroupKey());
            assertEquals("성동구", found.get().region().sigungu());
        }

        @Test
        @DisplayName("findByKey — 미매칭이면 빈 값")
        void findByKeyMissing() {
            when(repository.findByWorkplaceNameAndBizRegNoPrefixAndSnapshotMonth(anyString(), anyString(),
                    anyString())).thenReturn(Optional.empty());

            assertTrue(adapter.findByKey(KEY).isEmpty());
        }

        @Test
        @DisplayName("findGroupStatistics — COMPLETE 집계 조인 + 백분위 LEFT JOIN 을 지표별로 접는다")
        @SuppressWarnings("unchecked")
        void findGroupStatistics() throws Exception {
            ArgumentCaptor<RowMapper<Object>> mapper = ArgumentCaptor.forClass(RowMapper.class);
            when(jdbcTemplate.query(contains("workforce_aggregate"), mapper.capture(),
                    eq("주식회사에고이즘"), eq("866759"), eq("2026-06"), eq("INDUSTRY"), eq("EXACT"), eq("525101")))
                    .thenAnswer(invocation -> {
                        RowMapper<Object> rowMapper = invocation.getArgument(1);
                        return List.of(
                                rowMapper.mapRow(row("HEADCOUNT", "12.50", 30, "91.20"), 0),
                                rowMapper.mapRow(row("ESTIMATED_ANNUAL_SALARY", "35000000.00", 30, null), 1));
                    });

            Optional<GroupStatistics> statistics = adapter.findGroupStatistics(KEY, ComparisonAxis.INDUSTRY,
                    ComparisonLevel.EXACT, "525101");

            assertTrue(statistics.isPresent());
            assertEquals(30, statistics.get().sampleSize());
            assertEquals("12.50",
                    statistics.get().byMetric().get(WorkforceMetric.HEADCOUNT).median().toPlainString());
            assertEquals("91.20",
                    statistics.get().byMetric().get(WorkforceMetric.HEADCOUNT).percentile().toPlainString());
            // 대상 사업장이 적격 모집단에 없으면 백분위 행이 없다 → null 로 흘려보낸다.
            assertNull(statistics.get().byMetric()
                    .get(WorkforceMetric.ESTIMATED_ANNUAL_SALARY).percentile());
        }

        @Test
        @DisplayName("findGroupStatistics — 집단 행이 없으면(미빌드·미완료 포함) 빈 값")
        void findGroupStatisticsEmpty() {
            when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                    .thenReturn(List.of());

            assertTrue(adapter.findGroupStatistics(KEY, ComparisonAxis.REGION, ComparisonLevel.BROADENED,
                    "서울특별시").isEmpty());
        }

        private ResultSet row(String metric, String median, int sampleSize, String percentile) throws Exception {
            ResultSet rs = mock(ResultSet.class);
            when(rs.getString("metric")).thenReturn(metric);
            when(rs.getBigDecimal("median")).thenReturn(new BigDecimal(median));
            when(rs.getInt("sample_size")).thenReturn(sampleSize);
            when(rs.getBigDecimal("percentile"))
                    .thenReturn(percentile == null ? null : new BigDecimal(percentile));
            return rs;
        }
    }

    @Nested
    class AggregateBuildAdapter {

        private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        private final WorkforceAggregatePersistenceAdapter adapter =
                new WorkforceAggregatePersistenceAdapter(jdbcTemplate);

        @Test
        @DisplayName("rebuild — BUILDING 표시 → 전량 삭제 → 재삽입 → COMPLETE 순서로 한 트랜잭션에서 교체한다")
        void rebuildReplacesAtomically() {
            adapter.rebuild(YearMonth.of(2026, 6), new AggregateRowTally(100, 90, 10));

            InOrder order = inOrder(jdbcTemplate);
            order.verify(jdbcTemplate).update(contains("'BUILDING'"), eq("2026-06"), eq(100L), eq(90L), eq(10L));
            order.verify(jdbcTemplate).update(contains("DELETE FROM workforce_aggregate"), eq("2026-06"));
            order.verify(jdbcTemplate).update(contains("DELETE FROM workforce_percentile"), eq("2026-06"));
            order.verify(jdbcTemplate).update(contains("INSERT INTO workforce_aggregate"),
                    eq(new BigDecimal("0.095")), eq("2026-06"), eq("2026-06"));
            order.verify(jdbcTemplate).update(contains("INSERT INTO workforce_percentile"),
                    eq(new BigDecimal("0.095")), eq("2026-06"), eq("2026-06"));
            order.verify(jdbcTemplate).update(contains("'COMPLETE'"), eq("2026-06"));
        }

        @Test
        @DisplayName("rebuild — NUMERIC 값의 행 순위로 연속 중앙값을 만들고 날짜별 보험료율을 바인딩한다")
        void aggregateUsesNumericMedianAndDateEffectiveRate() {
            adapter.rebuild(YearMonth.of(2026, 6), new AggregateRowTally(1, 1, 0));

            verify(jdbcTemplate).update(argThat(sql -> sql.contains("INSERT INTO workforce_aggregate")
                            && sql.contains("headcount > 0 AND monthly_billed_amount > 0")
                            && sql.contains("metric_values(axis, level, group_key, metric, metric_value)")
                            && sql.contains("ROW_NUMBER()")
                            && sql.contains("COUNT(*)")
                            && sql.contains("(group_count + 1) / 2")
                            && sql.contains("(group_count + 2) / 2")
                            && sql.contains("ROUND(AVG(metric_value), 2)")
                            && sql.contains("MAX(group_count)")
                            && sql.contains("LEFT(industry_code, 3)")
                            && !sql.contains("double precision")
                            && !sql.contains("headcount * 0.09")),
                    eq(new BigDecimal("0.095")), eq("2026-06"), eq("2026-06"));
        }

        @Test
        @DisplayName("rebuild — 백분위는 cume_dist 로 계산하고, 사업자번호 공란 행은 순위 모집단에는 넣되 "
                + "저장에서만 뺀다(필터가 창 함수 안쪽이면 분모가 표본수와 어긋난다)")
        void percentileUsesCumeDistOverFullPopulation() {
            adapter.rebuild(YearMonth.of(2026, 6), new AggregateRowTally(1, 1, 0));

            verify(jdbcTemplate).update(argThat(sql -> {
                if (!sql.contains("INSERT INTO workforce_percentile") || !sql.contains("CUME_DIST()")) {
                    return false;
                }
                // 공란 필터는 창 함수를 계산한 바깥 질의(ranked)에서만 걸려야 한다.
                int rankedBoundary = sql.indexOf(") ranked");
                int blankFilter = sql.indexOf("biz_reg_no_prefix <> ''");
                return rankedBoundary > 0 && blankFilter > rankedBoundary;
            }), eq(new BigDecimal("0.095")), eq("2026-06"), eq("2026-06"));
        }

        @Test
        @DisplayName("rebuild — 지원하지 않는 기준월은 어떤 빌드 상태 변경도 하기 전에 거부한다")
        void rejectsUnsupportedMonthBeforeAnyJdbcUpdate() {
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                    () -> adapter.rebuild(YearMonth.of(2027, 1), new AggregateRowTally(1, 1, 0)));

            verifyNoInteractions(jdbcTemplate);
        }
    }
}
