package github.lms.lemuel.payout.adapter.out.firmbanking.fep;

import github.lms.lemuel.payout.adapter.out.firmbanking.fep.client.FepSocketClient;
import github.lms.lemuel.payout.adapter.out.firmbanking.fep.mockbank.MockBankServer;
import github.lms.lemuel.payout.application.port.out.FirmBankingPort.FirmBankingException;
import github.lms.lemuel.payout.domain.SellerBankAccount;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.ServerSocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * FEP 어댑터 왕복 테스트 — 모의 은행 서버(임시 포트) 상대로 정상/거절/타임아웃·결과조회/미확정 경로를 검증.
 *
 * <p>클라이언트 읽기 타임아웃(300ms) &lt; 서버 지연(700ms) 로 설정해 {@code ...2222} 계좌가
 * 항상 타임아웃 → 결과조회 경로를 타게 한다.
 */
class FepFirmBankingAdapterTest {

    private static MockBankServer server;
    private static FepFirmBankingAdapter adapter;

    @BeforeAll
    static void startMockBank() throws IOException {
        server = new MockBankServer(0, 700);
        server.start();
        adapter = new FepFirmBankingAdapter(new FepSocketClient("127.0.0.1", server.port(), 1000, 300));
    }

    @AfterAll
    static void stopMockBank() {
        server.stop();
    }

    private static SellerBankAccount account(String accountNo) {
        return new SellerBankAccount("KB", accountNo, "홍길동");
    }

    @Test
    @DisplayName("정상 이체: 0200→0210 왕복 후 은행 거래번호 반환")
    void transferSuccess() {
        String txnId = adapter.send(account("1234567890000001"), new BigDecimal("150000"), "REF-OK-1");
        assertThat(txnId).startsWith("FEPTX");
    }

    @Test
    @DisplayName("멱등: 동일 출금의뢰번호(REF_ID) 재송신 시 은행이 최초 거래번호를 그대로 반환 — 이중이체 없음")
    void duplicateRefIdReturnsSameTxnId() {
        String first = adapter.send(account("1234567890000001"), new BigDecimal("99000"), "REF-DUP-1");
        String second = adapter.send(account("1234567890000001"), new BigDecimal("99000"), "REF-DUP-1");
        assertThat(first).startsWith("FEPTX"); // 스텁(null==null) 통과 방지 — 실거래번호임을 먼저 확인
        assertThat(second).isEqualTo(first);
    }

    @Test
    @DisplayName("이체 거절: 응답코드 2001(계좌오류)이 FirmBankingException errorCode 로 전달")
    void invalidAccountRejected() {
        assertThatThrownBy(() -> adapter.send(account("1234567890001111"), new BigDecimal("50000"), "REF-BAD-1"))
                .isInstanceOf(FirmBankingException.class)
                .extracting(e -> ((FirmBankingException) e).getErrorCode())
                .isEqualTo("2001");
    }

    @Test
    @DisplayName("타임아웃→결과조회 확정: 응답 지연으로 타임아웃해도 0400 조회로 성공을 확정하고 거래번호 반환")
    void timeoutResolvedByInquiry() {
        String txnId = adapter.send(account("1234567890002222"), new BigDecimal("70000"), "REF-SLOW-1");
        assertThat(txnId).startsWith("FEPTX");
    }

    @Test
    @DisplayName("원거래 미접수: 무응답 후 결과조회가 N 이면 FEP_ORIG_NOT_FOUND — 재시도 안전 신호")
    void unresponsiveTransferConfirmedNotFound() {
        FirmBankingException e = catchThrowableOfType(FirmBankingException.class,
                () -> adapter.send(account("1234567890003333"), new BigDecimal("30000"), "REF-DROP-1"));
        assertThat(e.getErrorCode()).isEqualTo("FEP_ORIG_NOT_FOUND");
    }

    @Test
    @DisplayName("실패 확정: 타임아웃 후 결과조회가 F 이면 은행 오류코드로 실패 확정 — 이중이체 방지의 실패측 분기")
    void timeoutResolvedByInquiryAsConfirmedFailure() {
        FirmBankingException e = catchThrowableOfType(FirmBankingException.class,
                () -> adapter.send(account("1234567890004444"), new BigDecimal("60000"), "REF-FAIL-1"));
        assertThat(e.getErrorCode()).isEqualTo("9999");
        assertThat(e.getMessage()).contains("실패 확정");
    }

    @Test
    @DisplayName("프로토콜 오류: 결과조회 RESULT 가 규격 외 값이면 FEP_PROTOCOL_ERROR — 임의 해석 금지")
    void inquiryUnknownResultRejected() {
        FirmBankingException e = catchThrowableOfType(FirmBankingException.class,
                () -> adapter.send(account("1234567890002222"), new BigDecimal("40000"), "REF-BADRES-1"));
        assertThat(e.getErrorCode()).isEqualTo("FEP_PROTOCOL_ERROR");
    }

    @Test
    @DisplayName("미확정: 결과조회조차 무응답이면 FEP_UNCONFIRMED — 자동 재시도 금지·수동 대사 필요")
    void inquiryFailureLeavesUnconfirmed() {
        FirmBankingException e = catchThrowableOfType(FirmBankingException.class,
                () -> adapter.send(account("1234567890002222"), new BigDecimal("80000"), "REF-NOINQ-1"));
        assertThat(e.getErrorCode()).isEqualTo("FEP_UNCONFIRMED");
        assertThat(e.getMessage()).contains("수동");
    }

    @Test
    @DisplayName("접속 실패: 은행 미접속이면 FEP_CONNECT_ERROR — 원전문 미도달 보장, 재시도 안전")
    void connectFailure() throws IOException {
        int closedPort;
        try (ServerSocket probe = new ServerSocket(0)) {
            closedPort = probe.getLocalPort();
        }
        FepFirmBankingAdapter unreachable =
                new FepFirmBankingAdapter(new FepSocketClient("127.0.0.1", closedPort, 300, 300));
        FirmBankingException e = catchThrowableOfType(FirmBankingException.class,
                () -> unreachable.send(account("1234567890000001"), new BigDecimal("10000"), "REF-CONN-1"));
        assertThat(e.getErrorCode()).isEqualTo("FEP_CONNECT_ERROR");
    }

    @Test
    @DisplayName("금액 검증: 0원·음수·소수점(원 미만)·13자리 초과는 송신 전 FEP_INVALID_AMOUNT 로 차단")
    void invalidAmountsRejectedBeforeSend() {
        SellerBankAccount acct = account("1234567890000001");
        for (BigDecimal invalid : new BigDecimal[]{
                BigDecimal.ZERO,
                new BigDecimal("-1"),
                new BigDecimal("1234.56"),
                new BigDecimal("10000000000000") // 14자리
        }) {
            FirmBankingException e = catchThrowableOfType(FirmBankingException.class,
                    () -> adapter.send(acct, invalid, "REF-AMT-" + invalid.toPlainString()));
            assertThat(e.getErrorCode()).as("amount=%s", invalid).isEqualTo("FEP_INVALID_AMOUNT");
        }
    }

    @Test
    @DisplayName("금액 경계: 1원 이체 성공, 소수점 스케일이어도 정수값(150000.00)이면 정상 처리")
    void amountBoundaries() {
        assertThat(adapter.send(account("1234567890000001"), new BigDecimal("1"), "REF-MIN-1"))
                .startsWith("FEPTX");
        assertThat(adapter.send(account("1234567890000001"), new BigDecimal("150000.00"), "REF-SCALE-1"))
                .startsWith("FEPTX");
    }
}
