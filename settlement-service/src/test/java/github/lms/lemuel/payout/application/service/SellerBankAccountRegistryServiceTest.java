package github.lms.lemuel.payout.application.service;

import github.lms.lemuel.payout.application.port.out.LoadSellerBankAccountRegistrationPort;
import github.lms.lemuel.payout.application.port.out.SaveSellerBankAccountRegistrationPort;
import github.lms.lemuel.payout.domain.SellerBankAccountRegistration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SellerBankAccountRegistryServiceTest {

    @Mock LoadSellerBankAccountRegistrationPort loadPort;
    @Mock SaveSellerBankAccountRegistrationPort savePort;

    private SellerBankAccountRegistryService service;

    @BeforeEach
    void setUp() {
        service = new SellerBankAccountRegistryService(loadPort, savePort);
    }

    @Test
    @DisplayName("미등록 셀러 → 신규 등록")
    void registersNew() {
        when(loadPort.findBySellerId(7L)).thenReturn(Optional.empty());
        when(savePort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        SellerBankAccountRegistration result = service.register(7L, "KB", "111-11-111111", "홍길동");

        assertThat(result.getSellerId()).isEqualTo(7L);
        assertThat(result.getBankCode()).isEqualTo("KB");
        assertThat(result.getAccountNumber()).isEqualTo("111-11-111111");
    }

    @Test
    @DisplayName("기존 셀러 → 계좌 정정(같은 애그리거트에 changeAccount 반영)")
    void changesExisting() {
        SellerBankAccountRegistration existing =
                SellerBankAccountRegistration.register(7L, "KB", "111-11-111111", "홍길동");
        when(loadPort.findBySellerId(7L)).thenReturn(Optional.of(existing));
        when(savePort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.register(7L, "SHINHAN", "222-22-222222", "홍길동");

        ArgumentCaptor<SellerBankAccountRegistration> captor =
                ArgumentCaptor.forClass(SellerBankAccountRegistration.class);
        verify(savePort).save(captor.capture());
        assertThat(captor.getValue().getBankCode()).isEqualTo("SHINHAN");
        assertThat(captor.getValue().getAccountNumber()).isEqualTo("222-22-222222");
    }

    @Test
    @DisplayName("동시 최초 등록 경합 — INSERT PK 위반 시 재조회 후 정정으로 재시도(500 대신 성공)")
    void concurrentFirstRegisterRace_retriesAsChange() {
        SellerBankAccountRegistration winnerRow =
                SellerBankAccountRegistration.register(7L, "KB", "999-99-999999", "먼저등록");
        // 최초 확인 시점엔 미등록으로 관찰되지만, 경합 승자가 먼저 커밋해 재조회 시점엔 존재한다.
        when(loadPort.findBySellerId(7L))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winnerRow));
        // saveAndFlush 동기 반영을 모사 — 1차 INSERT 시도는 PK UNIQUE 위반, 2차(정정) 저장은 성공.
        when(savePort.save(any()))
                .thenThrow(new DataIntegrityViolationException("seller_bank_accounts_pkey"))
                .thenAnswer(inv -> inv.getArgument(0));

        SellerBankAccountRegistration result = service.register(7L, "SHINHAN", "222-22-222222", "홍길동");

        // 패자 요청도 최종적으로 자신이 의도한 계좌로 반영된다(정정 의미로 수렴, 500 없음).
        assertThat(result.getBankCode()).isEqualTo("SHINHAN");
        assertThat(result.getAccountNumber()).isEqualTo("222-22-222222");
        verify(savePort, times(2)).save(any());
    }

    @Test
    @DisplayName("동시 경합 후 재조회에도 미등록(격리수준 이상) → 원 예외 재던짐")
    void concurrentRace_rethrowsWhenWinnerStillInvisible() {
        when(loadPort.findBySellerId(7L)).thenReturn(Optional.empty());
        DataIntegrityViolationException raceException =
                new DataIntegrityViolationException("seller_bank_accounts_pkey");
        when(savePort.save(any())).thenThrow(raceException);

        org.junit.jupiter.api.Assertions.assertThrows(DataIntegrityViolationException.class,
                () -> service.register(7L, "SHINHAN", "222-22-222222", "홍길동"));
    }
}
