package github.lms.lemuel.board.application.port.out;

import java.util.Optional;

/**
 * 목록용 축소본 생성.
 *
 * <p><b>실패가 정상 경로다.</b> 반환이 {@code Optional} 인 것은 실수가 아니다 — JDK 의 ImageIO 는
 * WEBP 을 읽지 못하고, 손상된 이미지도 들어온다. 그때 업로드 전체를 실패시키면 <b>썸네일이라는
 * 부가 기능이 본 기능(첨부)을 죽이는</b> 셈이 된다. 축소본이 없으면 원본을 내려 줄 뿐이다.
 */
public interface GenerateThumbnailPort {

    /**
     * 긴 변을 {@code maxEdge} 로 줄인 이미지를 만든다. 만들 수 없으면 비어 있는 값.
     *
     * @param extension 서버가 판정한 확장자(요청이 주장한 값이 아니다)
     */
    Optional<Thumbnail> generate(byte[] source, String extension, int maxEdge);

    record Thumbnail(byte[] content, String extension) {
    }
}
