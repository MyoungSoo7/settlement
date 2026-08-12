package github.lms.lemuel.ai.rag.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link TextChunker} 의 불변식을 못박는 테스트.
 *
 * <p>청킹은 조용히 틀리는 종류의 코드다 — 잘못 잘려도 예외가 없고, 검색 품질이 나빠질 뿐이라
 * 운영에서 원인을 특정하기 어렵다. 그래서 "길이 상한", "빈 청크 없음", "문단 보존" 을
 * 테스트로 고정한다.
 */
class TextChunkerTest {

    @Test
    @DisplayName("빈 문서 → 0청크 (지식 없음 = 검색 동작이 종전과 같다)")
    void blankText_producesNoChunks() {
        assertThat(TextChunker.chunk(null, 100, 10)).isEmpty();
        assertThat(TextChunker.chunk("", 100, 10)).isEmpty();
        assertThat(TextChunker.chunk("   \n\n  \t ", 100, 10)).isEmpty();
    }

    @Test
    @DisplayName("짧은 문단들은 maxChars 안에서 하나로 합쳐진다 (문맥 보존 + 청크 수 절감)")
    void shortParagraphs_arePacked() {
        String text = "첫 번째 문단.\n\n두 번째 문단.\n\n세 번째 문단.";

        List<String> chunks = TextChunker.chunk(text, 100, 10);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0)).contains("첫 번째 문단.", "두 번째 문단.", "세 번째 문단.");
    }

    @Test
    @DisplayName("합치면 maxChars 를 넘는 문단은 다음 청크로 넘어간다 — 문단은 쪼개지지 않는다")
    void paragraphs_areNotSplitWhenTheyFit() {
        String a = "가".repeat(30);
        String b = "나".repeat(30);
        String c = "다".repeat(30);

        List<String> chunks = TextChunker.chunk(a + "\n\n" + b + "\n\n" + c, 70, 10);

        assertThat(chunks).hasSize(2);
        // 각 문단은 어느 한 청크 안에 온전히 들어 있어야 한다(반쪽 문단 = 근거로 못 씀).
        assertThat(chunks).anySatisfy(chunk -> assertThat(chunk).contains(a));
        assertThat(chunks).anySatisfy(chunk -> assertThat(chunk).contains(b));
        assertThat(chunks).anySatisfy(chunk -> assertThat(chunk).contains(c));
        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.length()).isLessThanOrEqualTo(70));
    }

    @Test
    @DisplayName("maxChars 를 넘는 단일 문단은 overlapChars 만큼 겹쳐 강제 분할된다")
    void oversizeParagraph_isHardSplitWithOverlap() {
        String text = "가".repeat(250);

        List<String> chunks = TextChunker.chunk(text, 100, 20);

        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.length()).isLessThanOrEqualTo(100));
        // step = 100 - 20 = 80 → 시작 오프셋 0(0~100), 80(80~180), 160(160~250, 끝 도달) → 3청크
        assertThat(chunks).hasSize(3);
        // 겹침이 실제로 존재해야 경계에 걸친 문장이 최소 한 청크에 온전히 들어간다.
        int totalLength = chunks.stream().mapToInt(String::length).sum();
        assertThat(totalLength).isGreaterThan(text.length());
    }

    @Test
    @DisplayName("CRLF 와 공백만 있는 줄도 문단 구분으로 인식한다")
    void handlesCrlfAndWhitespaceOnlyLines() {
        String text = "문단 하나.\r\n   \r\n문단 둘.";

        List<String> chunks = TextChunker.chunk(text, 1000, 0);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0)).isEqualTo("문단 하나.\n\n문단 둘.");
    }

    @Test
    @DisplayName("공백만인 청크는 생기지 않는다 (임베딩 API 가 거부하는 입력)")
    void neverProducesBlankChunks() {
        String text = "\n\n\n실제 내용\n\n\n\n\n또 다른 내용\n\n\n";

        List<String> chunks = TextChunker.chunk(text, 1000, 0);

        assertThat(chunks).isNotEmpty().allSatisfy(chunk -> assertThat(chunk).isNotBlank());
    }

    @Test
    @DisplayName("overlap ≥ max 는 즉시 거부한다 — 통과시키면 step ≤ 0 으로 무한 루프다")
    void rejectsOverlapNotSmallerThanMax() {
        assertThatThrownBy(() -> TextChunker.chunk("내용", 100, 100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("작아야");
        assertThatThrownBy(() -> TextChunker.chunk("내용", 100, 200))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("maxChars ≤ 0, overlapChars < 0 은 거부한다")
    void rejectsInvalidBounds() {
        assertThatThrownBy(() -> TextChunker.chunk("내용", 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TextChunker.chunk("내용", 100, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
