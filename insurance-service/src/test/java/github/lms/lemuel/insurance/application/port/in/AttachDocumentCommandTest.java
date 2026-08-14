package github.lms.lemuel.insurance.application.port.in;

import github.lms.lemuel.insurance.application.port.in.AttachApplicationDocumentUseCase.AttachDocumentCommand;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 청약서류 첨부 명령의 동치성 — 파일 본문은 참조가 아니라 <b>내용</b>으로 비교한다.
 *
 * <p>레코드 기본 구현은 {@code byte[]} 를 참조 동일성으로 비교한다. 같은 파일을 두 번 읽어 만든 명령이
 * 서로 다른 것으로 판정되면 "같은 (applicationId, 파일 해시) 재업로드는 멱등" 계약이 조용히 깨진다.
 */
class AttachDocumentCommandTest {

    private static AttachDocumentCommand command(byte[] content) {
        return new AttachDocumentCommand("APP-1", "FC-9", "application.pdf", "application/pdf", content);
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("내용이 같으면 배열 인스턴스가 달라도 같은 명령이다 (equals·hashCode)")
    void equalsByContent() {
        AttachDocumentCommand one = command(bytes("doc-bytes"));
        AttachDocumentCommand other = command(bytes("doc-bytes"));

        assertThat(one).isEqualTo(other).hasSameHashCodeAs(other);
    }

    @Test
    @DisplayName("본문이나 청약 자연키가 다르면 다른 명령이다")
    void differsByContentOrApplication() {
        assertThat(command(bytes("a"))).isNotEqualTo(command(bytes("b")));
        assertThat(command(bytes("a"))).isNotEqualTo(
                new AttachDocumentCommand("APP-2", "FC-9", "application.pdf", "application/pdf", bytes("a")));
        assertThat(command(bytes("a"))).isNotEqualTo("레코드가 아닌 것");
    }

    @Test
    @DisplayName("toString 은 파일 본문 대신 길이만 남긴다 — 바이트가 로그로 새지 않게")
    void toStringHidesContent() {
        assertThat(command(bytes("secret")).toString())
                .contains("contentBytes=6")
                .doesNotContain("secret");
    }
}
