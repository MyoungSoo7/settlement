package github.lms.lemuel.ai.config;

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
    void run_은_audit_와_chat_message_두_함수에_모두_위임한다() {
        var runner = new PartitionMaintenanceRunner(jdbcTemplate, "public", 3);

        runner.run(null);

        verify(jdbcTemplate).queryForObject("SELECT public.ensure_audit_log_partition(?)", Integer.class, 3);
        verify(jdbcTemplate).queryForObject("SELECT public.ensure_chat_message_partition(?)", Integer.class, 3);
    }

    @Test
    void 한_함수가_실패해도_삼키고_다른_함수를_계속_호출한다() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any()))
                .thenThrow(new RuntimeException("function does not exist"));
        var runner = new PartitionMaintenanceRunner(jdbcTemplate, "public", 3);

        assertDoesNotThrow(() -> runner.run(null));
        verify(jdbcTemplate).queryForObject("SELECT public.ensure_audit_log_partition(?)", Integer.class, 3);
        verify(jdbcTemplate).queryForObject("SELECT public.ensure_chat_message_partition(?)", Integer.class, 3);
    }

    @Test
    void ensureMonthly_도_audit_와_chat_message_두_함수에_모두_위임한다() {
        var runner = new PartitionMaintenanceRunner(jdbcTemplate, "public", 3);

        runner.ensureMonthly();

        verify(jdbcTemplate).queryForObject("SELECT public.ensure_audit_log_partition(?)", Integer.class, 3);
        verify(jdbcTemplate).queryForObject("SELECT public.ensure_chat_message_partition(?)", Integer.class, 3);
    }

    @Test
    void ensureMonthly_도_예외를_삼켜서_스케줄을_막지_않는다() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any()))
                .thenThrow(new RuntimeException("function does not exist"));
        var runner = new PartitionMaintenanceRunner(jdbcTemplate, "public", 3);

        assertDoesNotThrow(runner::ensureMonthly);
    }
}
