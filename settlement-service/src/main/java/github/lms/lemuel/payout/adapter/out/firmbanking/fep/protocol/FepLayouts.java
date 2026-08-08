package github.lms.lemuel.payout.adapter.out.firmbanking.fep.protocol;

import java.util.ArrayList;
import java.util.List;

import static github.lms.lemuel.payout.adapter.out.firmbanking.fep.protocol.FepFieldType.AN;
import static github.lms.lemuel.payout.adapter.out.firmbanking.fep.protocol.FepFieldType.N;

/**
 * 지급이체 FEP 전문 정의 — 전문공통부(헤더) + 업무별 개별부.
 *
 * <p><b>전문구분코드</b>: 0200 이체요청 / 0210 이체응답 / 0400 결과조회요청 / 0410 결과조회응답.
 * 전문 총길이는 별도 4바이트 길이 프레임({@code FepSocketClient})이 선행하므로 레이아웃에는 포함하지 않는다.
 *
 * <p><b>멱등 설계</b>: {@link #REF_ID}(출금의뢰번호 = {@code PAYOUT-<id>})가 은행측 중복 판정 키.
 * 동일 REF_ID 재송신은 은행이 최초 결과를 그대로 반환해야 한다(재시도 안전).
 */
public final class FepLayouts {

    private FepLayouts() {
    }

    // 전문구분코드
    public static final String MSG_TYPE_TRANSFER_REQ = "0200";
    public static final String MSG_TYPE_TRANSFER_RES = "0210";
    public static final String MSG_TYPE_INQUIRY_REQ = "0400";
    public static final String MSG_TYPE_INQUIRY_RES = "0410";

    // 응답코드
    public static final String RESP_OK = "0000";
    public static final String RESP_INVALID_ACCOUNT = "2001";
    public static final String RESP_SYSTEM_ERROR = "9999";

    // 결과조회 RESULT
    public static final String RESULT_SUCCESS = "S";
    public static final String RESULT_FAIL = "F";
    public static final String RESULT_NOT_FOUND = "N";

    // 공통부 필드
    public static final String MSG_TYPE = "MSG_TYPE";
    public static final String TELEGRAM_NO = "TELEGRAM_NO";
    public static final String TRANS_DT = "TRANS_DT";
    public static final String RESP_CODE = "RESP_CODE";

    // 개별부 필드
    public static final String BANK_CODE = "BANK_CODE";
    public static final String ACCOUNT_NO = "ACCOUNT_NO";
    public static final String AMOUNT = "AMOUNT";
    public static final String HOLDER_NAME = "HOLDER_NAME";
    public static final String REF_ID = "REF_ID";
    public static final String TXN_ID = "TXN_ID";
    public static final String ORIG_TELEGRAM_NO = "ORIG_TELEGRAM_NO";
    public static final String RESULT = "RESULT";
    public static final String ERROR_CODE = "ERROR_CODE";

    private static final List<FepField> HEADER = List.of(
            new FepField(MSG_TYPE, 4, AN),
            new FepField(TELEGRAM_NO, 12, N),
            new FepField(TRANS_DT, 14, N),
            new FepField(RESP_CODE, 4, AN));

    /** 0200 이체요청: 공통부 + 은행/계좌/금액(13, 원단위)/예금주(EUC-KR 20)/출금의뢰번호. */
    public static final TelegramLayout TRANSFER_REQUEST = withBody(
            new FepField(BANK_CODE, 10, AN),
            new FepField(ACCOUNT_NO, 16, AN),
            new FepField(AMOUNT, 13, N),
            new FepField(HOLDER_NAME, 20, AN),
            new FepField(REF_ID, 20, AN));

    /** 0210 이체응답: 요청 개별부 echo + 은행 거래번호. */
    public static final TelegramLayout TRANSFER_RESPONSE = withBody(
            new FepField(BANK_CODE, 10, AN),
            new FepField(ACCOUNT_NO, 16, AN),
            new FepField(AMOUNT, 13, N),
            new FepField(HOLDER_NAME, 20, AN),
            new FepField(REF_ID, 20, AN),
            new FepField(TXN_ID, 20, AN));

    /** 0400 결과조회요청: 원거래 전문일련번호 + 출금의뢰번호. */
    public static final TelegramLayout INQUIRY_REQUEST = withBody(
            new FepField(ORIG_TELEGRAM_NO, 12, N),
            new FepField(REF_ID, 20, AN));

    /** 0410 결과조회응답: 원거래 식별 + 처리결과(S/F/N) + 거래번호/오류코드. */
    public static final TelegramLayout INQUIRY_RESPONSE = withBody(
            new FepField(ORIG_TELEGRAM_NO, 12, N),
            new FepField(REF_ID, 20, AN),
            new FepField(RESULT, 1, AN),
            new FepField(TXN_ID, 20, AN),
            new FepField(ERROR_CODE, 4, AN));

    private static TelegramLayout withBody(FepField... body) {
        List<FepField> all = new ArrayList<>(HEADER);
        all.addAll(List.of(body));
        return new TelegramLayout(all);
    }
}
