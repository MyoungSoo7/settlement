package github.lms.lemuel.idempotency.adapter.out.persistence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ManualIdempotencyGuardTest {

    private final ManualOperationRecordRepository repository = mock(ManualOperationRecordRepository.class);
    private final ManualIdempotencyGuard guard = new ManualIdempotencyGuard(repository);

    @Test
    @DisplayName("처음 보는 키는 원자 upsert 선점 성공(영향 1행) → true")
    void firstUseOfKey_claimsAndReturnsTrue() {
        when(repository.insertIfAbsent(anyString(), anyString(), anyString(), any(Instant.class)))
                .thenReturn(1);

        assertThat(guard.claim("k-1", "payout:retry:5", "op")).isTrue();
        verify(repository).insertIfAbsent(eq("k-1"), eq("payout:retry:5"), eq("op"), any(Instant.class));
    }

    @Test
    @DisplayName("이미 선점된 키(ON CONFLICT DO NOTHING → 영향 0행)는 중복 → false, 예외 없음")
    void duplicateKey_returnsFalse() {
        when(repository.insertIfAbsent(anyString(), anyString(), anyString(), any(Instant.class)))
                .thenReturn(0);

        assertThat(guard.claim("k-1", "payout:retry:5", "op")).isFalse();
    }

    @Test
    @DisplayName("키가 null/blank 면 멱등 미적용 — 저장 없이 true")
    void blankKey_skipsIdempotency() {
        assertThat(guard.claim(null, "payout:retry:5", "op")).isTrue();
        assertThat(guard.claim("   ", "payout:retry:5", "op")).isTrue();
        verify(repository, never()).insertIfAbsent(anyString(), anyString(), anyString(), any(Instant.class));
    }
}
