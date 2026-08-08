package github.lms.lemuel.payout.adapter.out.firmbanking.fep.client;

import github.lms.lemuel.payout.adapter.out.firmbanking.fep.protocol.FepProtocolException;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * FEP TCP 전문 송수신 클라이언트 — 요청당 단일 연결(connect → send → receive → close).
 *
 * <p><b>프레이밍</b>: 전문 앞에 4바이트 ASCII 길이(전문길이부)를 선행한다. TCP 는 스트림이라
 * 메시지 경계가 없으므로 길이 프레임으로 전문 단위를 복원한다.
 *
 * <p><b>실패 구분이 핵심 계약이다</b>:
 * <ul>
 *   <li>{@link FepConnectException} — 접속 단계 실패. 전문이 은행에 도달하지 않았음이 보장되므로
 *       호출측은 안전하게 실패 처리(재시도 가능)할 수 있다.</li>
 *   <li>그 외 {@link IOException}(읽기 타임아웃·연결 끊김) — 전문이 <b>도달했을 수도 있다</b>.
 *       호출측은 성공/실패를 단정하지 말고 결과조회 전문으로 확정해야 한다.</li>
 * </ul>
 */
public class FepSocketClient {

    private final String host;
    private final int port;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;

    public FepSocketClient(String host, int port, int connectTimeoutMs, int readTimeoutMs) {
        this.host = host;
        this.port = port;
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
    }

    /**
     * 전문 1건 송신 후 응답 전문 1건 수신.
     *
     * @throws FepConnectException 접속 실패 (원전문 미도달 보장 — 재시도 안전)
     * @throws IOException         송신 후 수신 실패 (도달 여부 불확정 — 결과조회 필요)
     */
    public byte[] exchange(byte[] telegram) throws IOException {
        try (Socket socket = new Socket()) {
            try {
                socket.connect(new InetSocketAddress(host, port), connectTimeoutMs);
            } catch (IOException e) {
                throw new FepConnectException("FEP 접속 실패 " + host + ":" + port, e);
            }
            socket.setSoTimeout(readTimeoutMs);
            writeFrame(socket.getOutputStream(), telegram);
            return readFrame(socket.getInputStream());
        }
    }

    /** 4바이트 ASCII 길이 프레임 + 전문 본문 송신. */
    public static void writeFrame(OutputStream out, byte[] payload) throws IOException {
        if (payload.length > 9999) {
            throw new FepProtocolException("전문 길이가 길이부 4자리를 초과: " + payload.length);
        }
        out.write(String.format("%04d", payload.length).getBytes(StandardCharsets.US_ASCII));
        out.write(payload);
        out.flush();
    }

    /** 길이 프레임을 읽고 그 길이만큼 전문 본문을 수신. */
    public static byte[] readFrame(InputStream in) throws IOException {
        byte[] lenBytes = readFully(in, 4);
        int length;
        try {
            length = Integer.parseInt(new String(lenBytes, StandardCharsets.US_ASCII).trim());
        } catch (NumberFormatException e) {
            throw new FepProtocolException("길이 프레임 훼손: " + new String(lenBytes, StandardCharsets.US_ASCII), e);
        }
        return readFully(in, length);
    }

    private static byte[] readFully(InputStream in, int n) throws IOException {
        byte[] buf = new byte[n];
        int off = 0;
        while (off < n) {
            int read = in.read(buf, off, n - off);
            if (read < 0) {
                throw new EOFException("전문 수신 중 연결 종료 (수신 " + off + "/" + n + " 바이트)");
            }
            off += read;
        }
        return buf;
    }

    /** 접속 단계 실패 — 원전문이 은행에 도달하지 않았음이 보장된다. */
    public static class FepConnectException extends IOException {
        public FepConnectException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
