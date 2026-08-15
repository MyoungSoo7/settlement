package github.lms.lemuel.board.adapter.out.detect;

import github.lms.lemuel.board.application.port.out.DetectFileTypePort;
import github.lms.lemuel.board.domain.DetectedFileType;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 매직바이트 판정기 — 파일 앞부분의 고정 시그니처로 실제 형식을 정한다.
 *
 * <p>외부 라이브러리(Tika 등)를 쓰지 않는 이유: 게시판이 받는 형식은 손에 꼽고, 그 판정 로직은
 * 여기 20줄이면 끝난다. 반면 범용 판정기는 수백 개 형식을 인식하려 들어 <b>우리가 받지 않기로 한
 * 형식까지 "정상"으로 만들어</b> 준다 — 화이트리스트를 넓히는 방향의 의존은 이득이 없다.
 *
 * <p><b>인식 못 한 것은 여기서 통과시키지 않는다.</b> 시그니처가 없는 형식(txt·csv·md…)은
 * {@link TextContentSniffer} 가 내용으로 가린다 — 목록을 넓히는 게 아니라 판정 방법을 따로 둔 것이다.
 * 두 단계를 잇는 것은 {@link ContentFileTypeDetector} 이고, 이 클래스는 그 안의 1단계다
 * (그래서 스프링 빈이 아니다 — 포트 구현이 둘이 되면 주입이 갈린다).
 */
public class MagicByteFileTypeDetector implements DetectFileTypePort {

    private record Signature(int offset, byte[] magic, DetectedFileType type) {

        boolean matches(byte[] content) {
            if (content.length < offset + magic.length) {
                return false;
            }
            for (int i = 0; i < magic.length; i++) {
                if (content[offset + i] != magic[i]) {
                    return false;
                }
            }
            return true;
        }
    }

    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    private static final List<Signature> SIGNATURES = List.of(
            new Signature(0, new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF},
                    DetectedFileType.of("jpg", "image/jpeg", true, "jpeg")),
            new Signature(0, new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A},
                    DetectedFileType.of("png", "image/png", true)),
            new Signature(0, ascii("GIF87a"), DetectedFileType.of("gif", "image/gif", true)),
            new Signature(0, ascii("GIF89a"), DetectedFileType.of("gif", "image/gif", true)),
            new Signature(0, ascii("%PDF-"), DetectedFileType.of("pdf", "application/pdf", false)),
            // ZIP 컨테이너 — OOXML(docx/xlsx/pptx)·hwpx 가 전부 여기로 잡힌다. 별칭으로 허용하되
            // 실제 허용 여부는 게시판 정책이 정한다.
            new Signature(0, new byte[]{0x50, 0x4B, 0x03, 0x04},
                    DetectedFileType.of("zip", "application/zip", false, "docx", "xlsx", "pptx", "hwpx")));

    /** WEBP 는 RIFF 컨테이너라 두 곳을 함께 봐야 한다(0..3 = RIFF, 8..11 = WEBP). */
    private static final DetectedFileType WEBP = DetectedFileType.of("webp", "image/webp", true);

    @Override
    public DetectedFileType detect(byte[] content) {
        if (content == null || content.length == 0) {
            return DetectedFileType.unknown();
        }
        if (isWebp(content)) {
            return WEBP;
        }
        return SIGNATURES.stream()
                .filter(signature -> signature.matches(content))
                .map(Signature::type)
                .findFirst()
                .orElseGet(DetectedFileType::unknown);
    }

    private static boolean isWebp(byte[] content) {
        return new Signature(0, ascii("RIFF"), WEBP).matches(content)
                && new Signature(8, ascii("WEBP"), WEBP).matches(content);
    }
}
