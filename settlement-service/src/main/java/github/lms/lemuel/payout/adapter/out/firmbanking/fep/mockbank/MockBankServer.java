package github.lms.lemuel.payout.adapter.out.firmbanking.fep.mockbank;

import github.lms.lemuel.payout.adapter.out.firmbanking.fep.client.FepSocketClient;
import github.lms.lemuel.payout.adapter.out.firmbanking.fep.protocol.TelegramLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

import static github.lms.lemuel.payout.adapter.out.firmbanking.fep.protocol.FepLayouts.*;

/**
 * 모의 은행 FEP 서버 — 지급이체(0200)·결과조회(0400) 전문을 수신해 시나리오별로 응답한다.
 *
 * <p>테스트 픽스처 겸 시연 서버. <b>계좌번호 끝 4자리</b>로 결정적 시나리오를 트리거한다:
 * <ul>
 *   <li>{@code ...1111} — 계좌오류(응답코드 2001) 즉시 거절</li>
 *   <li>{@code ...2222} — 이체는 <b>처리하되</b> 응답을 지연(클라이언트 타임아웃 유도 → 결과조회 확정 경로)</li>
 *   <li>{@code ...3333} — 전문 미접수 시뮬레이션: 처리·응답 없이 연결 종료 (결과조회 시 N)</li>
 *   <li>{@code ...4444} — 시스템오류(9999)로 <b>실패 확정</b> + 응답 지연 (타임아웃 → 결과조회 시 F)</li>
 *   <li>그 외 — 정상 이체(0000 + 거래번호)</li>
 * </ul>
 * 출금의뢰번호(REF_ID)가 {@code NOINQ} 를 포함하면 결과조회(0400)에도 무응답 — 미확정(UNCONFIRMED) 경로 검증용.
 * {@code BADRES} 를 포함하면 결과조회 RESULT 에 규격 외 값을 반환 — 프로토콜 오류 경로 검증용.
 *
 * <p><b>멱등</b>: 동일 REF_ID 재수신 시 최초 처리 결과를 그대로 반환한다(이중이체 없음) — 실제 은행의
 * 출금의뢰번호 중복 판정과 동일한 계약.
 */
public class MockBankServer {

    private static final Logger log = LoggerFactory.getLogger(MockBankServer.class);
    private static final DateTimeFormatter TRANS_DT_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /** 최초 처리 결과 스냅샷 — REF_ID 멱등 판정의 근거. */
    private record ProcessResult(String respCode, String txnId, String result) {
    }

    private final int requestedPort;
    private final long slowDelayMillis;
    private final ConcurrentHashMap<String, ProcessResult> processedByRefId = new ConcurrentHashMap<>();
    private final AtomicLong txnSeq = new AtomicLong();

    private volatile boolean running;
    private ServerSocket serverSocket;
    private ExecutorService pool;

    /**
     * @param port            0 이면 임시 포트 자동 할당 (테스트용)
     * @param slowDelayMillis {@code ...2222} 계좌의 응답 지연 시간
     */
    public MockBankServer(int port, long slowDelayMillis) {
        this.requestedPort = port;
        this.slowDelayMillis = slowDelayMillis;
    }

    public synchronized void start() throws IOException {
        serverSocket = new ServerSocket(requestedPort);
        pool = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "mock-bank-fep");
            t.setDaemon(true);
            return t;
        });
        running = true;
        pool.submit(this::acceptLoop);
        log.info("[MockBank] FEP 모의 은행 기동: port={}", serverSocket.getLocalPort());
    }

    public int port() {
        return serverSocket.getLocalPort();
    }

    public synchronized void stop() {
        running = false;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException ignored) {
            // 종료 경로 — 무시
        }
        if (pool != null) pool.shutdownNow();
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket conn = serverSocket.accept();
                pool.submit(() -> handle(conn));
            } catch (IOException e) {
                if (running) log.warn("[MockBank] accept 실패", e);
                return;
            }
        }
    }

    private void handle(Socket conn) {
        try (conn) {
            byte[] telegram = FepSocketClient.readFrame(conn.getInputStream());
            String msgType = new String(telegram, 0, 4, TelegramLayout.EUC_KR);
            switch (msgType) {
                case MSG_TYPE_TRANSFER_REQ -> handleTransfer(telegram, conn.getOutputStream());
                case MSG_TYPE_INQUIRY_REQ -> handleInquiry(telegram, conn.getOutputStream());
                default -> log.warn("[MockBank] 미지원 전문구분코드: {}", msgType);
            }
        } catch (IOException | InterruptedException e) {
            // 인터럽트를 삼키면 상위 루프가 종료 신호를 못 본다 — 플래그를 즉시 복원한다.
            // (서버 종료 시 accept 루프가 인터럽트로 내려오는데, 여기서 지워지면 스레드가 계속 돈다.)
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.debug("[MockBank] 연결 처리 종료: {}", e.getMessage());
        }
    }

    private void handleTransfer(byte[] telegram, OutputStream out) throws IOException, InterruptedException {
        Map<String, String> req = TRANSFER_REQUEST.decode(telegram);
        String refId = req.get(REF_ID);
        String account = req.get(ACCOUNT_NO);

        ProcessResult result = processedByRefId.get(refId);
        if (result == null) {
            if (account.endsWith("3333")) {
                log.info("[MockBank] 전문 미접수 시뮬레이션(무응답): ref={}", refId);
                return; // 처리도 응답도 하지 않는다 — 결과조회 시 NOT_FOUND
            }
            ProcessResult fresh;
            if (account.endsWith("1111")) {
                fresh = new ProcessResult(RESP_INVALID_ACCOUNT, "", RESULT_FAIL);
            } else if (account.endsWith("4444")) {
                fresh = new ProcessResult(RESP_SYSTEM_ERROR, "", RESULT_FAIL);
            } else {
                fresh = new ProcessResult(RESP_OK, String.format("FEPTX%015d", txnSeq.incrementAndGet()), RESULT_SUCCESS);
            }
            processedByRefId.putIfAbsent(refId, fresh); // 동시 재송신 경합 시 최초 결과 승리
            result = processedByRefId.get(refId);
        } else {
            log.info("[MockBank] REF_ID 중복 수신 — 최초 결과 재응답(멱등): ref={}, txnId={}", refId, result.txnId());
        }

        if (account.endsWith("2222") || account.endsWith("4444")) {
            Thread.sleep(slowDelayMillis); // 처리 완료 후 응답만 지연 — 클라이언트는 타임아웃, 결과조회로 확정
        }

        Map<String, String> res = new HashMap<>(req);
        res.put(MSG_TYPE, MSG_TYPE_TRANSFER_RES);
        res.put(TRANS_DT, OffsetDateTime.now().format(TRANS_DT_FMT));
        res.put(RESP_CODE, result.respCode());
        res.put(TXN_ID, result.txnId());
        FepSocketClient.writeFrame(out, TRANSFER_RESPONSE.encode(res));
    }

    private void handleInquiry(byte[] telegram, OutputStream out) throws IOException {
        Map<String, String> req = INQUIRY_REQUEST.decode(telegram);
        String refId = req.get(REF_ID);
        if (refId.contains("NOINQ")) {
            log.info("[MockBank] 결과조회 무응답 시뮬레이션: ref={}", refId);
            return; // 조회조차 실패 — 호출측 UNCONFIRMED 경로
        }

        ProcessResult result = processedByRefId.get(refId);
        Map<String, String> res = new HashMap<>();
        res.put(MSG_TYPE, MSG_TYPE_INQUIRY_RES);
        res.put(TELEGRAM_NO, req.get(TELEGRAM_NO));
        res.put(TRANS_DT, OffsetDateTime.now().format(TRANS_DT_FMT));
        res.put(RESP_CODE, RESP_OK);
        res.put(ORIG_TELEGRAM_NO, req.get(ORIG_TELEGRAM_NO));
        res.put(REF_ID, refId);
        if (refId.contains("BADRES")) {
            res.put(RESULT, "X"); // 규격 외 RESULT — 호출측 프로토콜 오류 경로 검증용
            res.put(TXN_ID, "");
            res.put(ERROR_CODE, "");
        } else if (result == null) {
            res.put(RESULT, RESULT_NOT_FOUND);
            res.put(TXN_ID, "");
            res.put(ERROR_CODE, "");
        } else {
            res.put(RESULT, result.result());
            res.put(TXN_ID, result.txnId());
            res.put(ERROR_CODE, RESP_OK.equals(result.respCode()) ? "" : result.respCode());
        }
        FepSocketClient.writeFrame(out, INQUIRY_RESPONSE.encode(res));
    }
}
