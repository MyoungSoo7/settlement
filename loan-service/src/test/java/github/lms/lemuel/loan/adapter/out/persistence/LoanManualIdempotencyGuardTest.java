package github.lms.lemuel.loan.adapter.out.persistence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LoanManualIdempotencyGuardTest {

    private final LoanManualOperationRecordRepository repository =
            mock(LoanManualOperationRecordRepository.class);
    private final LoanManualIdempotencyGuard guard = new LoanManualIdempotencyGuard(repository);

    @Test
    @DisplayName("처음 보는 키는 선점 성공(1행 삽입) → true")
    void firstUseOfKey_claimsAndReturnsTrue() {
        when(repository.insertIfAbsent(any(), any(), any(), any())).thenReturn(1);

        assertThat(guard.claim("k-1", "loan:corporate:repay:5", "op")).isTrue();
        verify(repository).insertIfAbsent(any(), any(), any(), any());
    }

    @Test
    @DisplayName("이미 선점된 키(ON CONFLICT — 0행 삽입)는 중복으로 검출 → false")
    void duplicateKey_returnsFalse() {
        when(repository.insertIfAbsent(any(), any(), any(), any())).thenReturn(0);

        assertThat(guard.claim("k-1", "loan:corporate:repay:5", "op")).isFalse();
    }

    @Test
    @DisplayName("키가 null/blank 면 멱등 미적용 — 저장 없이 true")
    void blankKey_skipsIdempotency() {
        assertThat(guard.claim(null, "loan:corporate:repay:5", "op")).isTrue();
        assertThat(guard.claim("   ", "loan:corporate:repay:5", "op")).isTrue();
        verify(repository, never()).insertIfAbsent(any(), any(), any(), any());
    }
}
