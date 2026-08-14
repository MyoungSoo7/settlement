package github.lms.lemuel.payout.adapter.out.firmbanking.fep;

import github.lms.lemuel.payout.adapter.out.firmbanking.fep.client.FepSocketClient;
import github.lms.lemuel.payout.adapter.out.firmbanking.fep.protocol.FepProtocolException;
import github.lms.lemuel.payout.application.port.out.FirmBankingPort;
import github.lms.lemuel.payout.domain.SellerBankAccount;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static github.lms.lemuel.payout.adapter.out.firmbanking.fep.protocol.FepLayouts.*;

/**
 * FEP 대외계 펌뱅킹 어댑터 — 고정길이 전문(EUC-KR)을 TCP 소켓으로 송수신한다.
 *
 * <p>{@code app.firmbanking.mode=fep} 일 때 {@code MockFirmBankingAdapter} 를 대체한다.
 *
 * <p><b>미확정 거래 처리(핵심)</b>: 이체요청(0200) 송신 후 응답을 받지 못하면(타임아웃·연결 끊김)
 * 성공/실패를 <b>단정하지 않고</b> 결과조회(0400)로 원거래를 확정한다 —
 * <ul>
 *   <li>조회 결과 S — 이체는 이미 성공. 거래번호를 반환해 이중이체를 막는다.</li>
 *   <li>조회 결과 F — 실패 확정. 오류코드와 함께 실패 처리(재시도 가능).</li>
 *   <li>조회 결과 N — 원거래 미접수. 은행에 도달하지 않았으므로 재시도 안전.</li>
 *   <li>조회 자체 실패 — {@code FEP_UNCONFIRMED}. 자동 재시도 금지, 수동 대사 필요.</li>
 * </ul>
 * 재시도 멱등은 출금의뢰번호(referenceId = {@code PAYOUT-<id>}) 기반 은행측 중복 판정이 보장한다
 * (동일 REF_ID 재송신 시 은행이 최초 결과를 재응답 — {@code PayoutSingleExecutor} 의 referenceId 불변 계약).
 */
@Component
@ConditionalOnProperty(name = "app.firmbanking.mode", havingValue = "fep")
public class FepFirmBankingAdapter implements FirmBankingPort {

    private static final Logger log = LoggerFactory.getLogger(FepFirmBankingAdapter.class);
    private static final DateTimeFormatter TRANS_DT_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final DateTimeFormatter TELEGRAM_DATE_FMT = DateTimeFormatter.ofPattern("yyMMdd");
    private static final int AMOUNT_MAX_DIGITS = 13;

    private final FepSocketClient client;
    private final AtomicLong telegramSeq = new AtomicLong();

    public FepFirmBankingAdapter(
            @Value("${app.fep.host:localhost}") String host,
            @Value("${app.fep.port:9410}") int port,
            @Value("${app.fep.connect-timeout-ms:2000}") int connectTimeoutMs,
            @Value("${app.fep.read-timeout-ms:3000}") int readTimeoutMs) {
        this(new FepSocketClient(host, port, connectTimeoutMs, readTimeoutMs));
    }

    FepFirmBankingAdapter(FepSocketClient client) {
        this.client = client;
    }

    @Override
    public String send(SellerBankAccount account, BigDecimal amount, String referenceId)
            throws FirmBankingException {
        String amountWon = toWonString(amount);
        String telegramNo = nextTelegramNo();

        Map<String, String> req = new HashMap<>();
        req.put(MSG_TYPE, MSG_TYPE_TRANSFER_REQ);
        req.put(TELEGRAM_NO, telegramNo);
        req.put(TRANS_DT, OffsetDateTime.now().format(TRANS_DT_FMT));
        req.put(RESP_CODE, "");
        req.put(BANK_CODE, account.bankCode());
        req.put(ACCOUNT_NO, account.bankAccountNumber());
        req.put(AMOUNT, amountWon);
        req.put(HOLDER_NAME, account.accountHolderName());
        req.put(REF_ID, referenceId);

        byte[] responseBytes;
        try {
            responseBytes = client.exchange(encodeOrThrow(req));
        } catch (FepSocketClient.FepConnectException e) {
            // 접속 자체가 실패 — 전문 미도달 보장. FAILED 후 재시도 안전.
            throw new FirmBankingException("FEP_CONNECT_ERROR",
                    "FEP 접속 실패 — 원전문 미도달, 재시도 안전 (ref=" + referenceId + ")", e);
        } catch (IOException e) {
            // 송신 후 응답 미수신 — 도달 여부 불확정. 결과조회로 확정한다.
            log.warn("[FEP] 이체응답 미수신 — 결과조회로 확정 시도: ref={}, telegramNo={}, cause={}",
                    referenceId, telegramNo, e.toString());
            return resolveUnconfirmed(telegramNo, referenceId, e);
        }

        Map<String, String> res = decodeOrThrow(TRANSFER_RESPONSE_LABEL, responseBytes, referenceId);
        String respCode = res.get(RESP_CODE);
        if (!RESP_OK.equals(respCode)) {
            throw new FirmBankingException(respCode,
                    "펌뱅킹 이체 거절 (응답코드 " + respCode + ", ref=" + referenceId + ")");
        }
        String txnId = res.get(TXN_ID);
        log.info("[FEP] 이체 완료: ref={}, bank={}, account={}, amount={}, txnId={}",
                referenceId, account.bankCode(), account.maskedAccountNumber(), amountWon, txnId);
        return txnId;
    }

    /**
     * 미확정 원거래를 결과조회(0400) 전문으로 확정한다 — FEP 운영의 핵심 규율:
     * 응답을 못 받은 이체는 "실패" 가 아니라 "모름" 이며, 모름 상태로 재송신하면 이중이체가 난다.
     */
    private String resolveUnconfirmed(String origTelegramNo, String referenceId, IOException unconfirmedCause) {
        Map<String, String> inquiry = new HashMap<>();
        inquiry.put(MSG_TYPE, MSG_TYPE_INQUIRY_REQ);
        inquiry.put(TELEGRAM_NO, nextTelegramNo());
        inquiry.put(TRANS_DT, OffsetDateTime.now().format(TRANS_DT_FMT));
        inquiry.put(RESP_CODE, "");
        inquiry.put(ORIG_TELEGRAM_NO, origTelegramNo);
        inquiry.put(REF_ID, referenceId);

        byte[] responseBytes;
        try {
            responseBytes = client.exchange(INQUIRY_REQUEST.encode(inquiry));
        } catch (IOException e) {
            throw new FirmBankingException("FEP_UNCONFIRMED",
                    "이체 결과 미확정 — 결과조회도 실패. 자동 재시도 금지, 수동 대사 필요 (ref=" + referenceId + ")",
                    suppress(unconfirmedCause, e));
        }

        Map<String, String> res = decodeOrThrow(INQUIRY_RESPONSE_LABEL, responseBytes, referenceId);
        String result = res.get(RESULT);
        switch (result) {
            case RESULT_SUCCESS -> {
                String txnId = res.get(TXN_ID);
                log.info("[FEP] 결과조회로 성공 확정: ref={}, txnId={}", referenceId, txnId);
                return txnId;
            }
            case RESULT_FAIL -> {
                String errorCode = res.get(ERROR_CODE);
                throw new FirmBankingException(errorCode.isBlank() ? "FEP_FAIL" : errorCode,
                        "이체 실패 확정 (결과조회, ref=" + referenceId + ")");
            }
            case RESULT_NOT_FOUND -> throw new FirmBankingException("FEP_ORIG_NOT_FOUND",
                    "원거래 미접수 확인 — 은행 미도달, 재시도 안전 (ref=" + referenceId + ")");
            default -> throw new FirmBankingException("FEP_PROTOCOL_ERROR",
                    "결과조회 응답 RESULT 값 불명: '" + result + "' (ref=" + referenceId + ")");
        }
    }

    private static final String TRANSFER_RESPONSE_LABEL = "이체응답(0210)";
    private static final String INQUIRY_RESPONSE_LABEL = "결과조회응답(0410)";

    private byte[] encodeOrThrow(Map<String, String> values) {
        try {
            return TRANSFER_REQUEST.encode(values);
        } catch (FepProtocolException e) {
            throw new FirmBankingException("FEP_PROTOCOL_ERROR", "이체요청 전문 인코딩 실패: " + e.getMessage(), e);
        }
    }

    private Map<String, String> decodeOrThrow(String label, byte[] telegram, String referenceId) {
        try {
            return (label.equals(TRANSFER_RESPONSE_LABEL) ? TRANSFER_RESPONSE : INQUIRY_RESPONSE).decode(telegram);
        } catch (FepProtocolException e) {
            throw new FirmBankingException("FEP_PROTOCOL_ERROR",
                    label + " 전문 해석 실패 (ref=" + referenceId + "): " + e.getMessage(), e);
        }
    }

    /**
     * 금액 → 원 단위 정수 문자열. {@code BigDecimal} 만 받으며(금액 double 금지 가드),
     * 0 이하·원 미만 소수·13자리 초과는 송신 전에 차단한다 — 잘못된 전문이 은행에 도달하면 대사가 꼬인다.
     */
    private String toWonString(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new FirmBankingException("FEP_INVALID_AMOUNT",
                    "이체 금액은 양수여야 합니다: " + amount);
        }
        BigInteger won;
        try {
            won = amount.toBigIntegerExact();
        } catch (ArithmeticException e) {
            throw new FirmBankingException("FEP_INVALID_AMOUNT",
                    "이체 금액은 원 단위 정수여야 합니다: " + amount.toPlainString(), e);
        }
        String digits = won.toString();
        if (digits.length() > AMOUNT_MAX_DIGITS) {
            throw new FirmBankingException("FEP_INVALID_AMOUNT",
                    "이체 금액이 전문 한도(" + AMOUNT_MAX_DIGITS + "자리)를 초과: " + digits);
        }
        return digits;
    }

    /** 전문일련번호 12자리: yyMMdd(6) + 프로세스 내 순번(6). 결과조회의 원거래 식별자로 쓰인다. */
    private String nextTelegramNo() {
        return OffsetDateTime.now().format(TELEGRAM_DATE_FMT)
                + String.format("%06d", telegramSeq.incrementAndGet() % 1_000_000);
    }

    private static IOException suppress(IOException primary, IOException secondary) {
        primary.addSuppressed(secondary);
        return primary;
    }
}
