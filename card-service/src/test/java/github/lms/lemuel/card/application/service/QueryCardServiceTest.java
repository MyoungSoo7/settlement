package github.lms.lemuel.card.application.service;

import github.lms.lemuel.card.application.port.out.LoadCardAccountPort;
import github.lms.lemuel.card.application.port.out.LoadCardPort;
import github.lms.lemuel.card.domain.Card;
import github.lms.lemuel.card.domain.CardAccount;
import github.lms.lemuel.card.domain.CardAccountStatus;
import github.lms.lemuel.card.domain.CardStatus;
import github.lms.lemuel.card.domain.LimitSnapshot;
import github.lms.lemuel.card.domain.OrgRole;
import github.lms.lemuel.card.domain.ReputationGrade;
import github.lms.lemuel.common.exception.BusinessException;
import github.lms.lemuel.common.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 카드 조회 유스케이스 테스트.
 *
 * <p>조회에도 인가가 붙는 이유는 <b>한도와 보유 현황 자체가 여신 정보</b>이기 때문이다.
 * 다만 등급은 다르다 — 계정 요약은 구성원이면 누구나(자기 회사 한도는 알아야 한다),
 * 임직원 카드 목록은 OWNER·MANAGER 만(남의 카드 보유·한도는 인사 정보에 가깝다).
 * "내 카드"는 조직 판정 자체가 필요 없다 — 주체가 곧 대상이라 남의 것을 볼 경로가 없다.
 */
@ExtendWith(MockitoExtension.class)
class QueryCardServiceTest {

    @Mock CardOrgAuthorizer authorizer;
    @Mock LoadCardAccountPort loadCardAccountPort;
    @Mock LoadCardPort loadCardPort;

    QueryCardService service;

    @BeforeEach
    void setUp() {
        service = new QueryCardService(authorizer, loadCardAccountPort, loadCardPort);
    }

    private CardAccount stubAccount() {
        CardAccount account = CardAccount.builder()
                .id(1L)
                .organizationId(3001L)
                .sellerId("777")
                .status(CardAccountStatus.SCREENING)
                .masterLimit(BigDecimal.ZERO)
                .build();
        account.activate(new BigDecimal("1000000"), new LimitSnapshot(
                new BigDecimal("1000000"), BigDecimal.ZERO,
                new BigDecimal("0.7000"), ReputationGrade.B, "seller*0.7"));
        when(loadCardAccountPort.findById(1L)).thenReturn(Optional.of(account));
        return account;
    }

    private static Card card(Long id, Long holderUserId) {
        return Card.builder()
                .id(id).cardAccountId(1L).holderUserId(holderUserId)
                .maskedCardNo("****-****-****-1234")
                .subLimit(new BigDecimal("100000"))
                .status(CardStatus.ISSUED)
                .build();
    }

    @Test
    @DisplayName("계정 조회는 구성원이면 역할 무관 — STAFF 도 자기 회사 한도는 볼 수 있다")
    void anyMemberCanReadAccount() {
        CardAccount account = stubAccount();

        assertThat(service.getAccount(1L, 100L)).isSameAs(account);

        verify(authorizer).requireRole(eq(3001L), eq(100L),
                eq(Set.of(OrgRole.OWNER, OrgRole.MANAGER, OrgRole.STAFF)), anyString());
    }

    @Test
    @DisplayName("없는 계정 조회는 404")
    void unknownAccountIsNotFound() {
        when(loadCardAccountPort.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getAccount(1L, 100L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.CARD_ACCOUNT_NOT_FOUND);
        verify(authorizer, never()).requireRole(any(), any(), any(), anyString());
    }

    @Test
    @DisplayName("임직원 카드 목록은 OWNER·MANAGER 만 — 남의 보유 현황은 STAFF 에게 열지 않는다")
    void onlyOwnerAndManagerCanListCards() {
        stubAccount();
        when(loadCardPort.findByCardAccountId(1L)).thenReturn(List.of(card(10L, 888L)));

        assertThat(service.listCards(1L, 100L)).hasSize(1);

        verify(authorizer).requireRole(eq(3001L), eq(100L),
                eq(Set.of(OrgRole.OWNER, OrgRole.MANAGER)), anyString());
    }

    /**
     * "내 카드"의 대상은 요청 파라미터가 아니라 JWT 주체다 — 조회 대상을 입력으로 받는 순간
     * 그 자체가 IDOR 경로가 된다. 그래서 조직 인가를 거치지 않는 유일한 조회다.
     */
    @Test
    @DisplayName("내 카드 조회는 주체 uid 로만 조회하며 조직 인가를 거치지 않는다")
    void myCardsNeedNoOrgAuthorization() {
        when(loadCardPort.findByHolderUserId(888L)).thenReturn(List.of(card(10L, 888L)));

        List<Card> mine = service.listMyCards(888L);

        assertThat(mine).singleElement()
                .extracting(Card::getHolderUserId).isEqualTo(888L);
        verify(authorizer, never()).requireRole(any(), any(), any(), anyString());
    }
}
