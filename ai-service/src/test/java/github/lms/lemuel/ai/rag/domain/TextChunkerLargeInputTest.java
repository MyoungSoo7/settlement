package github.lms.lemuel.ai.rag.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 문단 구분 정규식의 대형 입력 내성.
 *
 * <p>SonarCloud java:S5998 이 {@code (?:\r?\n[ \t]*+){2,}} 를 "스택 오버플로를 유발할 수 있는 반복"으로
 * 지적한다. Java 정규식은 그룹 반복을 재귀로 처리하므로, 반복 횟수가 아주 크면 StackOverflowError 가
 * 날 수 있다는 것이 규칙의 근거다.
 *
 * <p>RAG 적재는 관리자가 넣는 대형 문서를 통째로 청킹하는 경로라 이 입력이 이론이 아니라 실제다.
 * 그래서 "규칙이 그렇다더라"가 아니라 <b>실제로 터지는지</b>를 이 테스트가 판정한다.
 * 여기서 실패하면 정규식을 재구성해야 하고, 통과하면 이 입력 규모에서는 안전하다는 근거가 된다.
 */
@DisplayName("TextChunker — 대형 입력 내성")
class TextChunkerLargeInputTest {

    /** 연속 빈 줄이 아주 많은 문서 — 그룹 반복이 최대로 늘어나는 최악 입력. */
    private static String documentWithConsecutiveBlankLines(int blankLines) {
        StringBuilder sb = new StringBuilder("첫 문단");
        sb.append("\n \t".repeat(blankLines));
        sb.append("\n\n마지막 문단");
        return sb.toString();
    }

    @Test
    @DisplayName("연속 빈 줄 50,000 개를 넣어도 StackOverflowError 가 나지 않는다")
    void doesNotOverflowOnManyConsecutiveBlankLines() {
        String huge = documentWithConsecutiveBlankLines(50_000);

        assertThatCode(() -> TextChunker.chunk(huge, 1_000, 100)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("최악 입력에서도 문단 경계는 그대로다 — 앞뒤 문단이 살아 있다")
    void stillSplitsParagraphsOnHugeInput() {
        String huge = documentWithConsecutiveBlankLines(10_000);

        List<String> chunks = TextChunker.chunk(huge, 1_000, 100);

        assertThat(chunks).isNotEmpty();
        assertThat(String.join("\n", chunks)).contains("첫 문단").contains("마지막 문단");
    }

    @Test
    @DisplayName("CRLF 도 한 줄바꿈으로 센다 — \\r\\n 하나가 문단 경계가 되면 안 된다")
    void crlfIsSingleLineBreak() {
        // 단일 CRLF 는 문단 경계가 아니다 → 앞 두 줄은 같은 문단(=같은 청크)에 남아야 한다.
        // 청크 수로 단정하지 않는다: 청커는 최대 길이까지 문단을 이어 붙이므로 개수는 길이 설정에 종속된다.
        List<String> chunks = TextChunker.chunk("문단 A\r\n이어지는 줄\r\n\r\n문단 B", 1_000, 100);

        assertThat(chunks).anySatisfy(chunk ->
                assertThat(chunk).contains("문단 A").contains("이어지는 줄"));
        assertThat(String.join("\n", chunks)).contains("문단 B");
    }
}
