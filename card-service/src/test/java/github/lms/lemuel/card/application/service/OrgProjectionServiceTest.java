package github.lms.lemuel.card.application.service;

import github.lms.lemuel.card.application.port.in.IngestOrgProjectionUseCase.MemberCommand;
import github.lms.lemuel.card.application.port.in.IngestOrgProjectionUseCase.OrgCommand;
import github.lms.lemuel.card.application.port.out.LoadCardAccountPort;
import github.lms.lemuel.card.application.port.out.LoadCardPort;
import github.lms.lemuel.card.application.port.out.PublishCardEventPort;
import github.lms.lemuel.card.application.port.out.SaveCardPort;
import github.lms.lemuel.card.application.port.out.SaveOrgProjectionPort;
import github.lms.lemuel.card.domain.Card;
import github.lms.lemuel.card.domain.CardAccount;
import github.lms.lemuel.card.domain.CardAccountStatus;
import github.lms.lemuel.card.domain.CardStatus;
import github.lms.lemuel.card.domain.LimitSnapshot;
import github.lms.lemuel.card.domain.ReputationGrade;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrgProjectionServiceTest {

    @Mock SaveOrgProjectionPort saveOrgProjectionPort;
    @Mock LoadCardAccountPort loadCardAccountPort;
    @Mock LoadCardPort loadCardPort;
    @Mock SaveCardPort saveCardPort;
    @Mock PublishCardEventPort publishCardEventPort;

    OrgProjectionService service;

    @BeforeEach
    void setUp() {
        service = new OrgProjectionService(saveOrgProjectionPort, loadCardAccountPort,
                loadCardPort, saveCardPort, publishCardEventPort);
    }

    @Test
    void createOrg_delegatesToSavePort() {
        service.createOrg(new OrgCommand(3001L, "무신사 스토어", "SELLER", "SELLER-777"));

        verify(saveOrgProjectionPort).saveOrg(3001L, "무신사 스토어", "SELLER", "SELLER-777");
    }

    @Test
    void upsertMember_delegatesToSavePort() {
        service.upsertMember(new MemberCommand(3001L, 888L, "MANAGER", 9001L));

        verify(saveOrgProjectionPort).upsertMember(3001L, 888L, "MANAGER", 9001L);
    }

    @Test
    void removeMember_delegatesToDeactivate() {
        service.removeMember(3001L, 888L, 9001L);

        verify(saveOrgProjectionPort).deactivateMember(3001L, 888L, 9001L);
    }

    @Test
    @DisplayName("이탈자의 활성 카드는 정지되고 사유가 실린 상태 변경 이벤트가 나간다")
    void removeMember_suspendsActiveCard() {
        CardAccount account = stubActiveAccount();
        Card card = Card.issue(1L, 888L, "m", new BigDecimal("100000"));
        when(loadCardPort.findActiveByHolder(1L, 888L)).thenReturn(Optional.of(card));
        when(saveCardPort.save(card)).thenReturn(card);

        service.removeMember(3001L, 888L, 9001L);

        assertThat(card.getStatus()).isEqualTo(CardStatus.SUSPENDED);
        verify(saveOrgProjectionPort).deactivateMember(3001L, 888L, 9001L);
        verify(publishCardEventPort).publishStatusChanged(
                eq(card), eq(account), eq(CardStatus.ISSUED),
                org.mockito.ArgumentMatchers.contains("member_removed"));
    }

    /**
     * 해지는 되돌릴 수 없고 이탈은 번복된다(휴직·전출·오발행 정정). 되돌릴 수 있는 사실에
     * 터미널 전이를 붙이면 복직 경로 자체가 사라진다.
     */
    @Test
    @DisplayName("이탈은 정지지 해지가 아니다")
    void removeMember_suspendsNotCancels() {
        stubActiveAccount();
        Card card = Card.issue(1L, 888L, "m", new BigDecimal("100000"));
        when(loadCardPort.findActiveByHolder(1L, 888L)).thenReturn(Optional.of(card));
        when(saveCardPort.save(card)).thenReturn(card);

        service.removeMember(3001L, 888L, 9001L);

        assertThat(card.getStatus()).isNotEqualTo(CardStatus.CANCELED);
    }

    @Test
    @DisplayName("이미 정지된 카드는 다시 저장·발행하지 않는다 — 리플레이 노이즈 차단")
    void removeMember_alreadySuspendedPublishesNothing() {
        stubActiveAccount();
        Card card = Card.issue(1L, 888L, "m", new BigDecimal("100000"));
        card.suspend();
        when(loadCardPort.findActiveByHolder(1L, 888L)).thenReturn(Optional.of(card));

        service.removeMember(3001L, 888L, 9001L);

        verify(saveOrgProjectionPort).deactivateMember(3001L, 888L, 9001L);
        verify(saveCardPort, never()).save(any());
        verifyNoInteractions(publishCardEventPort);
    }

    /**
     * organization-service 는 상대가 카드를 쓰는지 모르고 이탈 이벤트를 보낸다 —
     * 카드계정 없음은 예외가 아니라 정상 경로다.
     */
    @Test
    @DisplayName("카드계정이 없는 조직이면 카드 조회 자체를 하지 않는다")
    void removeMember_withoutCardAccount_skipsCardLookup() {
        when(loadCardAccountPort.findByOrganizationId(3001L)).thenReturn(Optional.empty());

        service.removeMember(3001L, 888L, 9001L);

        verify(saveOrgProjectionPort).deactivateMember(3001L, 888L, 9001L);
        verifyNoInteractions(loadCardPort, saveCardPort, publishCardEventPort);
    }

    private CardAccount stubActiveAccount() {
        CardAccount account = CardAccount.builder()
                .id(1L)
                .organizationId(3001L)
                .sellerId("777")
                .status(CardAccountStatus.SCREENING)
                .masterLimit(BigDecimal.ZERO)
                .build();
        account.activate(new BigDecimal("1000000"), new LimitSnapshot(new BigDecimal("1000000"),
                BigDecimal.ZERO, new BigDecimal("0.7000"), ReputationGrade.B, "seller*0.7"));
        when(loadCardAccountPort.findByOrganizationId(3001L)).thenReturn(Optional.of(account));
        return account;
    }

    /**
     * 검증하지 않고 저장하면 실패가 <b>읽는 시점</b>(카드 발급 심사의 {@code findMemberRole})으로
     * 밀려 500 이 되고 원인 이벤트도 DLT 에 남지 않는다. 적재 시점 IAE 는 프레임워크가
     * non-retryable 로 규정한 격리·DLT 경로다.
     */
    @Test
    void upsertMember_unknownRole_rejectedAtIngest() {
        assertThatThrownBy(() -> service.upsertMember(new MemberCommand(3001L, 888L, "ACCOUNTANT", 9001L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ACCOUNTANT");

        verifyNoInteractions(saveOrgProjectionPort);
    }

    @Test
    void upsertMember_nullRole_rejectedAtIngest() {
        assertThatThrownBy(() -> service.upsertMember(new MemberCommand(3001L, 888L, null, 9001L)))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(saveOrgProjectionPort);
    }
}
