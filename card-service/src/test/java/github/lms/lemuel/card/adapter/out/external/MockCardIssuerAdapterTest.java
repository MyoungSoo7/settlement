package github.lms.lemuel.card.adapter.out.external;

import github.lms.lemuel.card.application.port.out.CardIssuerPort.IssuedCard;
import github.lms.lemuel.card.domain.Card;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 스텁 채번기 검증. 여기서 확인할 것은 "랜덤이 잘 도는가"가 아니라
 * <b>도메인의 PAN 게이트를 통과하는 값만 나오는가</b>다 — 채번기가 마스킹되지 않은 번호를
 * 만들기 시작하면 그 순간 PCI 스코프가 카드 DB 전체로 넓어진다.
 */
class MockCardIssuerAdapterTest {

    private final MockCardIssuerAdapter adapter = new MockCardIssuerAdapter();

    @Test
    @DisplayName("채번 결과는 도메인 마스킹 게이트를 통과한다")
    void issuedNumberPassesDomainMaskGate() {
        IssuedCard issued = adapter.issue(5001L, 888L);

        assertThat(issued.maskedCardNo()).startsWith("****-****-****-");
        // Card.issue 가 PAN 추정 값을 거부하므로, 통과한다는 것 자체가 마스킹 증명이다.
        assertThatCode(() -> Card.issue(5001L, 888L, issued.maskedCardNo(), new BigDecimal("100000")))
                .doesNotThrowAnyException();
    }

    /**
     * 같은 번호만 찍어내면 "임직원마다 다른 카드"라는 표시상의 최소 요건이 깨진다. 정확한 분포가
     * 아니라 <b>고정값이 아님</b>만 확인한다(100 회가 전부 같을 확률은 사실상 0).
     */
    @Test
    @DisplayName("채번은 고정값이 아니다 — 호출마다 다른 번호가 나온다")
    void issuedNumbersVary() {
        Set<String> numbers = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            numbers.add(adapter.issue(5001L, (long) i).maskedCardNo());
        }

        assertThat(numbers).hasSizeGreaterThan(1);
        assertThat(numbers).allSatisfy(no -> assertThat(no).hasSize(19));
    }
}
