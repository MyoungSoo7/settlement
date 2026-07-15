package github.lms.lemuel.economics.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PartitionMaintenanceRunnerTest {

    @Mock
    JdbcTemplate jdbcTemplate;

    @Test
    void run_은_연별_ensure_indicator_value_함수에_years_인자로_위임한다() {
        var runner = new PartitionMaintenanceRunner(jdbcTemplate, "public", 1);

        runner.run(null);

        verify(jdbcTemplate).queryForObject("SELECT public.ensure_indicator_value_partition(?)", Integer.class, 1);
    }

    @Test
    void 함수가_없어_예외가_나도_삼켜서_부팅을_막지_않는다() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any()))
                .thenThrow(new RuntimeException("function ensure_indicator_value_partition does not exist"));
        var runner = new PartitionMaintenanceRunner(jdbcTemplate, "public", 1);

        assertDoesNotThrow(() -> runner.run(null));
    }

    @Test
    void ensureMonthly_도_연별_ensure_indicator_value_함수에_years_인자로_위임한다() {
        var runner = new PartitionMaintenanceRunner(jdbcTemplate, "public", 1);

        runner.ensureMonthly();

        verify(jdbcTemplate).queryForObject("SELECT public.ensure_indicator_value_partition(?)", Integer.class, 1);
    }

    @Test
    void ensureMonthly_도_예외를_삼켜서_스케줄을_막지_않는다() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any()))
                .thenThrow(new RuntimeException("function ensure_indicator_value_partition does not exist"));
        var runner = new PartitionMaintenanceRunner(jdbcTemplate, "public", 1);

        assertDoesNotThrow(runner::ensureMonthly);
    }
}
