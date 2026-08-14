package github.lms.lemuel.deposit.domain;

import github.lms.lemuel.deposit.domain.exception.InvalidDepositProofException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 이체확인증 OCR 추출 VO 불변식 — 이체금액·신뢰도 필수, 입금자명·이체일은 판독 실패를 null 로 표현한다.
 */
class ExtractedTransferProofTest {

    @Test
    @DisplayName("정상 추출 결과를 보관한다")
    void createsValid() {
        ExtractedTransferProof proof = new ExtractedTransferProof(
                "홍길동", LocalDate.of(2026, 8, 12), new BigDecimal("3000000"), new BigDecimal("0.93"));

        assertThat(proof.senderName()).isEqualTo("홍길동");
        assertThat(proof.transferDate()).isEqualTo(LocalDate.of(2026, 8, 12));
        assertThat(proof.transferAmount()).isEqualByComparingTo("3000000");
        assertThat(proof.confidence()).isEqualByComparingTo("0.93");
    }

    @Test
    @DisplayName("입금자명·이체일은 판독 실패(null) 허용 — 공백 입금자명은 null 정규화")
    void optionalFieldsMayBeNull() {
        ExtractedTransferProof proof = new ExtractedTransferProof(
                "  ", null, new BigDecimal("3000000"), new BigDecimal("0.50"));

        assertThat(proof.senderName()).isNull();
        assertThat(proof.transferDate()).isNull();
    }

    @Test
    @DisplayName("이체금액은 필수·양수 — 0원·음수·누락은 거부")
    void amountMustBePositive() {
        assertThatThrownBy(() -> new ExtractedTransferProof(null, null, null, new BigDecimal("0.9")))
                .isInstanceOf(InvalidDepositProofException.class);
        assertThatThrownBy(() -> new ExtractedTransferProof(null, null, BigDecimal.ZERO, new BigDecimal("0.9")))
                .isInstanceOf(InvalidDepositProofException.class);
        assertThatThrownBy(() -> new ExtractedTransferProof(null, null, new BigDecimal("-1"), new BigDecimal("0.9")))
                .isInstanceOf(InvalidDepositProofException.class);
    }

    @Test
    @DisplayName("신뢰도는 0~1 범위 필수 — 경계값 0·1 은 허용")
    void confidenceRange() {
        assertThatThrownBy(() -> new ExtractedTransferProof(null, null, new BigDecimal("100"), null))
                .isInstanceOf(InvalidDepositProofException.class);
        assertThatThrownBy(() -> new ExtractedTransferProof(null, null, new BigDecimal("100"), new BigDecimal("1.01")))
                .isInstanceOf(InvalidDepositProofException.class);
        assertThat(new ExtractedTransferProof(null, null, new BigDecimal("100"), BigDecimal.ZERO)
                .confidence()).isEqualByComparingTo("0");
        assertThat(new ExtractedTransferProof(null, null, new BigDecimal("100"), BigDecimal.ONE)
                .confidence()).isEqualByComparingTo("1");
    }
}
