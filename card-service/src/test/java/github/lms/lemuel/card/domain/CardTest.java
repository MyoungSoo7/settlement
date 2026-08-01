package github.lms.lemuel.card.domain;

import github.lms.lemuel.card.domain.exception.InvalidCardTransitionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CardTest {

    @Test
    @DisplayName("발급 직후는 ISSUED")
    void issuedStartsAsIssued() {
        Card card = Card.issue(1L, 888L, "1234-****-****-5678", new BigDecimal("500000"));

        assertThat(card.getStatus()).isEqualTo(CardStatus.ISSUED);
        assertThat(card.getCardAccountId()).isEqualTo(1L);
        assertThat(card.getHolderUserId()).isEqualTo(888L);
        assertThat(card.getSubLimit()).isEqualByComparingTo("500000");
    }

    @Test
    @DisplayName("ISSUED ⇄ SUSPENDED 는 왕복 가능")
    void issuedSuspendedRoundTrip() {
        Card card = Card.issue(1L, 888L, "1234-****-****-5678", new BigDecimal("500000"));

        card.suspend();
        assertThat(card.getStatus()).isEqualTo(CardStatus.SUSPENDED);
        card.resume();
        assertThat(card.getStatus()).isEqualTo(CardStatus.ISSUED);
    }

    @Test
    @DisplayName("서브한도 변경이 반영된다")
    void changeSubLimitApplies() {
        Card card = Card.issue(1L, 888L, "1234-****-****-5678", new BigDecimal("500000"));

        card.changeSubLimit(new BigDecimal("300000"));

        assertThat(card.getSubLimit()).isEqualByComparingTo("300000");
    }

    @Test
    @DisplayName("이미 CANCELED 인 카드는 어떤 전이도 불가 — 터미널")
    void canceledIsTerminal() {
        Card card = Card.issue(1L, 888L, "1234-****-****-5678", new BigDecimal("500000"));
        card.cancel();

        assertThatThrownBy(card::suspend).isInstanceOf(InvalidCardTransitionException.class);
        assertThatThrownBy(() -> card.changeSubLimit(new BigDecimal("100000")))
                .isInstanceOf(InvalidCardTransitionException.class);
    }

    @Test
    @DisplayName("CANCELED 인 카드를 다시 해지하면 예외 — 멱등이 아니다")
    void cancelIsNotIdempotent() {
        Card card = Card.issue(1L, 888L, "1234-****-****-5678", new BigDecimal("500000"));
        card.cancel();

        assertThatThrownBy(card::cancel).isInstanceOf(InvalidCardTransitionException.class);
    }

    @Test
    @DisplayName("이미 SUSPENDED 인 카드를 다시 정지해도 예외 없이 무시된다 — 이벤트 재수신 멱등")
    void suspendIsIdempotent() {
        Card card = Card.issue(1L, 888L, "1234-****-****-5678", new BigDecimal("500000"));
        card.suspend();
        card.suspend();   // member_removed 재수신

        assertThat(card.getStatus()).isEqualTo(CardStatus.SUSPENDED);
    }

    @Test
    @DisplayName("카드번호는 마스킹된 값만 보관한다")
    void onlyMaskedNumberStored() {
        Card card = Card.issue(1L, 888L, "1234-****-****-5678", new BigDecimal("500000"));
        assertThat(card.getMaskedCardNo()).isEqualTo("1234-****-****-5678");
    }

    // ★ 재리뷰(I5) 회귀 — "연속 숫자 N자리"만 보는 정규식은 구분자(공백·대시)로 4자리씩 끊긴
    // 미마스킹 PAN 을 통과시킨다(거짓 음성). 구분자를 제거한 뒤 숫자 자릿수로 판단해야 한다.
    // 임계값 12 는 PAN 최소자리(13)와 PCI 표시형식(첫6+끝4=숫자10자리) 사이에 둬서, PCI 표시
    // 형식(123456******7890)은 통과시키면서(거짓 양성 금지) 실제 PAN 은 구분자 형태와 무관하게
    // 전부 거부한다(거짓 음성 금지).
    @ParameterizedTest(name = "[{index}] \"{0}\" → 마스킹통과={1}")
    @DisplayName("마스킹 판정 경계값 5종 — 거짓 양성·거짓 음성 회귀(I5 재리뷰)")
    @CsvSource({
            "1234-****-****-5678, true",     // 정상 마스킹(숫자 8자리) → 통과
            "123456******7890, true",        // PCI 표시형식 첫6+끝4(숫자 10자리) → 통과, 거짓 양성 금지
            "'5678 9012 3456 7890', false",  // 공백 구분 PAN(숫자 16자리) → 거부, 거짓 음성 금지
            "5678-9012-3456-7890, false",    // 대시 구분 PAN(숫자 16자리) → 거부, 거짓 음성 금지
            "5678901234567890, false",       // 구분자 없는 PAN(숫자 16자리) → 거부
    })
    void maskingBoundaryCases(String candidate, boolean accepted) {
        if (accepted) {
            assertThatCode(() -> Card.issue(1L, 888L, candidate, new BigDecimal("500000")))
                    .doesNotThrowAnyException();
        } else {
            assertThatThrownBy(() -> Card.issue(1L, 888L, candidate, new BigDecimal("500000")))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("Builder 재구성 경로에서도 마스킹되지 않은 카드번호는 거부된다")
    void unmaskedPanRejectedOnBuilder() {
        assertThatThrownBy(() -> Card.builder()
                .id(9L)
                .cardAccountId(1L)
                .holderUserId(888L)
                .maskedCardNo("5678901234567890")
                .subLimit(new BigDecimal("300000"))
                .status(CardStatus.ISSUED)
                .version(2L)
                .build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("음수 서브한도는 발급 시점에 거부된다")
    void negativeSubLimitRejectedOnIssue() {
        assertThatThrownBy(() -> Card.issue(1L, 888L, "1234-****-****-5678", new BigDecimal("-1")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("음수 서브한도는 변경 시점에도 거부된다")
    void negativeSubLimitRejectedOnChange() {
        Card card = Card.issue(1L, 888L, "1234-****-****-5678", new BigDecimal("500000"));
        assertThatThrownBy(() -> card.changeSubLimit(new BigDecimal("-1")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("빈 카드번호는 발급 시점에 거부된다")
    void blankMaskedCardNoRejected() {
        assertThatThrownBy(() -> Card.issue(1L, 888L, " ", new BigDecimal("500000")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Builder 로 영속 상태를 재구성한다 — id/version 포함")
    void builderReconstitutesFullState() {
        Card card = Card.builder()
                .id(9L)
                .cardAccountId(1L)
                .holderUserId(888L)
                .maskedCardNo("1234-****-****-5678")
                .subLimit(new BigDecimal("300000"))
                .status(CardStatus.ISSUED)
                .version(2L)
                .build();

        assertThat(card.getId()).isEqualTo(9L);
        assertThat(card.getVersion()).isEqualTo(2L);
    }
}
