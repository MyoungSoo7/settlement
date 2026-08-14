package github.lms.lemuel.payout.adapter.out.firmbanking.fep.codegen;

import github.lms.lemuel.payout.adapter.out.firmbanking.fep.protocol.FepLayouts;
import github.lms.lemuel.payout.adapter.out.firmbanking.fep.protocol.FepProtocolException;
import github.lms.lemuel.payout.adapter.out.firmbanking.fep.protocol.generated.BulkTransferRequestCodec;
import github.lms.lemuel.payout.adapter.out.firmbanking.fep.protocol.generated.BulkTransferRequestTelegram;
import github.lms.lemuel.payout.adapter.out.firmbanking.fep.protocol.generated.TransferRequestCodec;
import github.lms.lemuel.payout.adapter.out.firmbanking.fep.protocol.generated.TransferRequestTelegram;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 생성된 코덱의 동작 계약 (ADR 0033 Phase 2).
 *
 * <p>Phase 1 의 {@code Map<String,String>} API 로는 컴파일이 잡아주지 못하던 것들 — 필드명 오타,
 * 금액이 문자열인 것, 반복부를 인덱스 문자열로 조립하는 것 — 이 여기서 타입으로 바뀐다.
 */
class TelegramGeneratedCodecTest {

    @Test
    @DisplayName("금액은 BigDecimal 로 왕복한다 — 전문에는 13자리 0패딩, 값으로는 원 단위")
    void amountRoundTripsAsBigDecimal() {
        TransferRequestTelegram request = new TransferRequestTelegram(
                "0200", "260808000001", "20260808120000", "",
                "KB", "1234567890123456", new BigDecimal("1000000"), "홍길동", "PAYOUT-42");

        byte[] telegram = TransferRequestCodec.encode(request);
        TransferRequestTelegram decoded = TransferRequestCodec.decode(telegram);

        assertThat(telegram).hasSize(TransferRequestCodec.TOTAL_LENGTH);
        assertThat(decoded.amount()).isEqualByComparingTo("1000000");
        assertThat(decoded.holderName()).isEqualTo("홍길동");
        assertThat(decoded.refId()).isEqualTo("PAYOUT-42");
    }

    @Test
    @DisplayName("생성 코덱의 바이트는 기존 Map 기반 인코딩과 완전히 같다")
    void producesSameBytesAsHandWrittenMapEncoding() {
        Map<String, String> values = new HashMap<>();
        values.put(FepLayouts.MSG_TYPE, FepLayouts.MSG_TYPE_TRANSFER_REQ);
        values.put(FepLayouts.TELEGRAM_NO, "260808000001");
        values.put(FepLayouts.TRANS_DT, "20260808120000");
        values.put(FepLayouts.RESP_CODE, "");
        values.put(FepLayouts.BANK_CODE, "KB");
        values.put(FepLayouts.ACCOUNT_NO, "1234567890123456");
        values.put(FepLayouts.AMOUNT, "1000000");
        values.put(FepLayouts.HOLDER_NAME, "홍길동");
        values.put(FepLayouts.REF_ID, "PAYOUT-42");

        byte[] handWritten = FepLayouts.TRANSFER_REQUEST.encode(values);
        byte[] generated = TransferRequestCodec.encode(new TransferRequestTelegram(
                "0200", "260808000001", "20260808120000", "",
                "KB", "1234567890123456", new BigDecimal("1000000"), "홍길동", "PAYOUT-42"));

        assertThat(generated).isEqualTo(handWritten);
    }

    @Test
    @DisplayName("가변 전문은 건수만큼만 길어진다 — 1건과 2건의 바이트 길이가 다르다")
    void variableTelegramLengthFollowsCount() {
        var one = bulk(List.of(detail("1", "PAYOUT-1", "50000")), null);
        var two = bulk(List.of(detail("1", "PAYOUT-1", "50000"), detail("2", "PAYOUT-2", "70000")), null);

        int oneLength = BulkTransferRequestCodec.encode(one).length;
        int twoLength = BulkTransferRequestCodec.encode(two).length;

        assertThat(oneLength).isEqualTo(BulkTransferRequestCodec.BASE_LENGTH + 82);
        assertThat(twoLength).isEqualTo(BulkTransferRequestCodec.BASE_LENGTH + 82 * 2);
    }

    @Test
    @DisplayName("건수 필드를 비워 두면 실제 건수로 채워진다")
    void derivesCountFieldFromDetails() {
        var request = bulk(List.of(detail("1", "PAYOUT-1", "50000"), detail("2", "PAYOUT-2", "70000")), null);

        var decoded = BulkTransferRequestCodec.decode(BulkTransferRequestCodec.encode(request));

        assertThat(decoded.totalCnt()).isEqualTo("002");
        assertThat(decoded.details()).hasSize(2);
    }

    @Test
    @DisplayName("건수 필드와 실제 명세 건수가 다르면 거부한다 — 은행이 앞 n건만 처리하고 나머지를 버린다")
    void rejectsCountFieldMismatch() {
        var request = bulk(List.of(detail("1", "PAYOUT-1", "50000")), "3");

        assertThatThrownBy(() -> BulkTransferRequestCodec.encode(request))
                .isInstanceOf(FepProtocolException.class)
                .hasMessageContaining("건수 필드와 실제 명세 건수가 다르다");
    }

    @Test
    @DisplayName("반복부는 List 로 넣고 List 로 받는다 — 보낸 건수만 돌아온다")
    void bulkDetailsRoundTripAsList() {
        var details = List.of(
                new BulkTransferRequestTelegram.Detail(
                        "1", "KB", "1111111111111111", new BigDecimal("50000"), "김철수", "PAYOUT-1"),
                new BulkTransferRequestTelegram.Detail(
                        "2", "SH", "2222222222222222", new BigDecimal("70000"), "이영희", "PAYOUT-2"));
        var request = new BulkTransferRequestTelegram(
                "0220", "260808000002", "20260808120500", "",
                "2", new BigDecimal("120000"), details);

        var decoded = BulkTransferRequestCodec.decode(BulkTransferRequestCodec.encode(request));

        assertThat(decoded.details()).hasSize(2);
        assertThat(decoded.details().get(0).refId()).isEqualTo("PAYOUT-1");
        assertThat(decoded.details().get(1).amount()).isEqualByComparingTo("70000");
        assertThat(decoded.details().get(1).holderName()).isEqualTo("이영희");
        assertThat(decoded.totalAmount()).isEqualByComparingTo("120000");
    }

    private static BulkTransferRequestTelegram bulk(
            List<BulkTransferRequestTelegram.Detail> details, String declaredCount) {
        return new BulkTransferRequestTelegram(
                "0220", "260808000002", "20260808120500", "",
                declaredCount, new BigDecimal("120000"), details);
    }

    private static BulkTransferRequestTelegram.Detail detail(String seq, String refId, String amount) {
        return new BulkTransferRequestTelegram.Detail(
                seq, "KB", "1111111111111111", new BigDecimal(amount), "김철수", refId);
    }

    @Test
    @DisplayName("반복 최대 건수를 넘기면 인코딩이 거부한다")
    void rejectsTooManyDetails() {
        var request = bulk(
                Collections.nCopies(BulkTransferRequestCodec.DETAIL_MAX + 1, detail("1", "PAYOUT-1", "1000")), null);

        assertThatThrownBy(() -> BulkTransferRequestCodec.encode(request))
                .isInstanceOf(FepProtocolException.class)
                .hasMessageContaining("최대 100건 초과");
    }

    @Test
    @DisplayName("음수 금액은 인코딩이 거부한다 — 부호 필드가 없어 조용히 양수가 된다")
    void rejectsNegativeAmount() {
        TransferRequestTelegram request = new TransferRequestTelegram(
                "0200", "260808000004", "20260808121500", "",
                "KB", "1234567890123456", new BigDecimal("-1000"), "홍길동", "PAYOUT-9");

        assertThatThrownBy(() -> TransferRequestCodec.encode(request))
                .isInstanceOf(FepProtocolException.class)
                .hasMessageContaining("음수 금액");
    }

    @Test
    @DisplayName("규격보다 정밀한 금액은 거부한다 — 원 단위 전문에 소수점은 담기지 않는다")
    void rejectsAmountFinerThanScale() {
        TransferRequestTelegram request = new TransferRequestTelegram(
                "0200", "260808000005", "20260808122000", "",
                "KB", "1234567890123456", new BigDecimal("1000.55"), "홍길동", "PAYOUT-10");

        assertThatThrownBy(() -> TransferRequestCodec.encode(request))
                .isInstanceOf(FepProtocolException.class)
                .hasMessageContaining("소수 자릿수");
    }
}
