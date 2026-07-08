package github.lms.lemuel.recon;

import github.lms.lemuel.recon.ReconQueryRepository.CompletedRefundRow;
import github.lms.lemuel.recon.ReconQueryRepository.ReconPaymentRow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ReconQueryRepository — order 소유 opslab 대사 쿼리")
class ReconQueryRepositoryTest {

    @Mock JdbcTemplate jdbcTemplate;
    @InjectMocks ReconQueryRepository repository;

    private final LocalDate d = LocalDate.of(2026, 7, 1);

    @Test
    @DisplayName("decimal 쿼리 — 값 반환")
    void decimalQueries() {
        when(jdbcTemplate.queryForObject(anyString(), eq(BigDecimal.class), any(Object[].class)))
                .thenReturn(new BigDecimal("123"));
        assertThat(repository.sumCapturedPayments(d)).isEqualByComparingTo("123");
        assertThat(repository.sumCapturedPayments(d, d.plusDays(1))).isEqualByComparingTo("123");
        assertThat(repository.sumRefundedAgainstCaptures(d)).isEqualByComparingTo("123");
        assertThat(repository.sumCompletedRefunds(d)).isEqualByComparingTo("123");
        assertThat(repository.sumCompletedRefunds(d, d.plusDays(1))).isEqualByComparingTo("123");
    }

    @Test
    @DisplayName("decimal 쿼리 — null 이면 ZERO 로 방어")
    void decimalNullCoalesce() {
        when(jdbcTemplate.queryForObject(anyString(), eq(BigDecimal.class), any(Object[].class)))
                .thenReturn(null);
        assertThat(repository.sumCapturedPayments(d)).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("count 쿼리 — 값 반환 및 null→0")
    void countQueries() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(Object[].class)))
                .thenReturn(7L);
        assertThat(repository.countCapturedPayments(d)).isEqualTo(7L);
        assertThat(repository.countCompletedRefunds(d)).isEqualTo(7L);
        assertThat(repository.countPaymentCapturedPublished(d, d.plusDays(1))).isEqualTo(7L);

        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(Object[].class)))
                .thenReturn(null);
        assertThat(repository.countCapturedPayments(d)).isZero();
    }

    @Test
    @DisplayName("sumCompletedRefundsByIds — 빈 리스트면 쿼리 없이 ZERO")
    void sumByIds_empty() {
        assertThat(repository.sumCompletedRefundsByIds(List.of())).isEqualByComparingTo("0");
        assertThat(repository.sumCompletedRefundsByIds(null)).isEqualByComparingTo("0");
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    @DisplayName("sumCompletedRefundsByIds — id 있으면 IN 절 합계, null→ZERO")
    void sumByIds_present() {
        when(jdbcTemplate.queryForObject(anyString(), eq(BigDecimal.class), any(Object[].class)))
                .thenReturn(new BigDecimal("40"));
        assertThat(repository.sumCompletedRefundsByIds(List.of(1L, 2L))).isEqualByComparingTo("40");

        when(jdbcTemplate.queryForObject(anyString(), eq(BigDecimal.class), any(Object[].class)))
                .thenReturn(null);
        assertThat(repository.sumCompletedRefundsByIds(List.of(1L))).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("listCompletedRefunds — 행 매핑 반환")
    void listCompleted() {
        List<CompletedRefundRow> rows = List.of(new CompletedRefundRow(1L, 2L, new BigDecimal("10"), d));
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(rows);
        assertThat(repository.listCompletedRefunds(d, d.plusDays(1), 100)).hasSize(1);
    }

    @Test
    @DisplayName("loadCapturedPaymentRows — 행 매핑 반환")
    void loadRows() {
        List<ReconPaymentRow> rows = List.of(new ReconPaymentRow(1L, "PG", new BigDecimal("10"), BigDecimal.ZERO, d));
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(rows);
        assertThat(repository.loadCapturedPaymentRows(d)).hasSize(1);
    }
}
