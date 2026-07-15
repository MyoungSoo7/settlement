package github.lms.lemuel.ai.audit.adapter.out.persistence;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 감사 기록 어댑터 단위 검증 — 기록 위임과 실패 무해성.
 */
class AuditRecordingAdapterTest {

    private final AuditLogRepository repository = mock(AuditLogRepository.class);
    private final AuditRecordingAdapter adapter = new AuditRecordingAdapter(repository);

    @Test
    void 감사행을_리포지토리에_위임하고_필드를_채운다() {
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        adapter.record("COLLECT_TRIGGERED", "DartSync", "companies", Map.of("job", "companies"));

        ArgumentCaptor<AuditLogJpaEntity> captor = ArgumentCaptor.forClass(AuditLogJpaEntity.class);
        verify(repository).save(captor.capture());
        AuditLogJpaEntity saved = captor.getValue();
        assertThat(saved.getAction()).isEqualTo("COLLECT_TRIGGERED");
        assertThat(saved.getResourceType()).isEqualTo("DartSync");
        assertThat(saved.getResourceId()).isEqualTo("companies");
        assertThat(saved.getDetailJson()).contains("\"job\"").contains("companies");
        assertThat(saved.getCreatedAt()).isNotNull();
    }

    @Test
    void 리포지토리_예외는_삼켜서_본_작업을_깨지_않는다() {
        doThrow(new RuntimeException("db down")).when(repository).save(any());

        assertThatCode(() -> adapter.record("COLLECT_TRIGGERED", "DartSync", "x", null))
                .doesNotThrowAnyException();
    }

    @Test
    void detail_이_null_이면_detail_json_도_null() {
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        adapter.record("COLLECT_TRIGGERED", "DartSync", "companies", null);

        ArgumentCaptor<AuditLogJpaEntity> captor = ArgumentCaptor.forClass(AuditLogJpaEntity.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getDetailJson()).isNull();
    }
}
