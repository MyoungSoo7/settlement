package github.lms.lemuel.payout.adapter.out.firmbanking.fep.protocol;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static github.lms.lemuel.payout.adapter.out.firmbanking.fep.protocol.FepLayouts.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 고정길이 전문 코덱 규격 테스트 — byte offset·EUC-KR 패딩·라운드트립이 계약이다.
 */
class TelegramLayoutTest {

    private Map<String, String> transferValues() {
        Map<String, String> v = new HashMap<>();
        v.put(MSG_TYPE, MSG_TYPE_TRANSFER_REQ);
        v.put(TELEGRAM_NO, "260808000001");
        v.put(TRANS_DT, "20260808120000");
        v.put(RESP_CODE, "");
        v.put(BANK_CODE, "KB");
        v.put(ACCOUNT_NO, "1234567890123456");
        v.put(AMOUNT, "1000000");
        v.put(HOLDER_NAME, "홍길동");
        v.put(REF_ID, "PAYOUT-42");
        return v;
    }

    @Test
    @DisplayName("이체요청 전문 총길이 = 공통부 34 + 개별부 79 바이트")
    void transferRequestTotalLength() {
        assertThat(TRANSFER_REQUEST.totalLength()).isEqualTo(113);
    }

    @Test
    @DisplayName("인코딩: 전문 바이트 길이는 레이아웃 총길이와 정확히 일치한다")
    void encodeProducesExactLength() {
        byte[] telegram = TRANSFER_REQUEST.encode(transferValues());
        assertThat(telegram).hasSize(TRANSFER_REQUEST.totalLength());
    }

    @Test
    @DisplayName("N 필드는 좌측 0 패딩: 금액 1000000 → 13자리 0000001000000")
    void numericFieldZeroPadsLeft() {
        byte[] telegram = TRANSFER_REQUEST.encode(transferValues());
        // AMOUNT offset: 공통부 34 + BANK_CODE 10 + ACCOUNT_NO 16 = 60, 길이 13
        String amount = new String(telegram, 60, 13, TelegramLayout.EUC_KR);
        assertThat(amount).isEqualTo("0000001000000");
    }

    @Test
    @DisplayName("AN 필드 한글은 EUC-KR 바이트 기준 패딩: 홍길동(6바이트) + 공백 14바이트 = 20바이트")
    void koreanNamePadsByEucKrBytes() {
        byte[] telegram = TRANSFER_REQUEST.encode(transferValues());
        // HOLDER_NAME offset: 60 + AMOUNT 13 = 73, 길이 20
        String holder = new String(telegram, 73, 20, TelegramLayout.EUC_KR);
        assertThat(holder).isEqualTo("홍길동" + " ".repeat(14));
    }

    @Test
    @DisplayName("라운드트립: encode → decode 시 원본 값 복원 (AN 우측 공백 제거, N 선행 0 보존)")
    void roundTrip() {
        Map<String, String> decoded = TRANSFER_REQUEST.decode(TRANSFER_REQUEST.encode(transferValues()));
        assertThat(decoded.get(MSG_TYPE)).isEqualTo("0200");
        assertThat(decoded.get(TELEGRAM_NO)).isEqualTo("260808000001");
        assertThat(decoded.get(HOLDER_NAME)).isEqualTo("홍길동");
        assertThat(decoded.get(AMOUNT)).isEqualTo("0000001000000");
        assertThat(decoded.get(REF_ID)).isEqualTo("PAYOUT-42");
        assertThat(decoded.get(RESP_CODE)).isEmpty();
    }

    @Test
    @DisplayName("금액 경계: 0원(자릿수 없음 아님)·1원·13자리 최대값 모두 규격대로 인코딩")
    void amountBoundaries() {
        Map<String, String> v = transferValues();

        v.put(AMOUNT, "1");
        assertThat(TRANSFER_REQUEST.decode(TRANSFER_REQUEST.encode(v)).get(AMOUNT)).isEqualTo("0000000000001");

        v.put(AMOUNT, "9999999999999"); // 13자리 최대
        assertThat(TRANSFER_REQUEST.decode(TRANSFER_REQUEST.encode(v)).get(AMOUNT)).isEqualTo("9999999999999");
    }

    @Test
    @DisplayName("규격 위반: AN 값이 필드 바이트 길이 초과(한글 11자 = 22바이트 > 20) 시 예외")
    void anValueExceedingByteLengthRejected() {
        Map<String, String> v = transferValues();
        v.put(HOLDER_NAME, "가나다라마바사아자차카"); // 11자 × 2바이트 = 22바이트
        assertThatThrownBy(() -> TRANSFER_REQUEST.encode(v))
                .isInstanceOf(FepProtocolException.class)
                .hasMessageContaining("HOLDER_NAME");
    }

    @Test
    @DisplayName("규격 위반: N 필드에 비숫자 포함 시 예외")
    void numericFieldRejectsNonDigits() {
        Map<String, String> v = transferValues();
        v.put(AMOUNT, "10,000");
        assertThatThrownBy(() -> TRANSFER_REQUEST.encode(v))
                .isInstanceOf(FepProtocolException.class)
                .hasMessageContaining("AMOUNT");
    }

    @Test
    @DisplayName("규격 위반: N 값이 필드 길이 초과(14자리 > 13) 시 예외")
    void numericValueExceedingLengthRejected() {
        Map<String, String> v = transferValues();
        v.put(AMOUNT, "10000000000000"); // 14자리
        assertThatThrownBy(() -> TRANSFER_REQUEST.encode(v))
                .isInstanceOf(FepProtocolException.class)
                .hasMessageContaining("AMOUNT");
    }

    @Test
    @DisplayName("규격 위반: 수신 전문 길이가 레이아웃 총길이와 다르면 예외")
    void decodeRejectsWrongLength() {
        assertThatThrownBy(() -> TRANSFER_REQUEST.decode(new byte[112]))
                .isInstanceOf(FepProtocolException.class)
                .hasMessageContaining("전문 길이");
    }
}
