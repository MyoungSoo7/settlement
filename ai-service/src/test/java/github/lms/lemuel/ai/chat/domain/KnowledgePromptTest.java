package github.lms.lemuel.ai.chat.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgePromptTest {

    private static final String SYSTEM_PROMPT = "당신은 Lemuel 도우미입니다.";

    @Test
    @DisplayName("근거 0건 — 원본 프롬프트가 바이트 그대로 반환된다 (RAG 도입 전과 동일한 동작)")
    void noContext_returnsOriginalUnchanged() {
        assertThat(KnowledgePrompt.augment(SYSTEM_PROMPT, List.of())).isSameAs(SYSTEM_PROMPT);
        assertThat(KnowledgePrompt.augment(SYSTEM_PROMPT, null)).isSameAs(SYSTEM_PROMPT);
    }

    @Test
    @DisplayName("근거가 있으면 원본 뒤에 자료가 번호와 제목과 함께 붙는다")
    void withContext_appendsNumberedSections() {
        String augmented = KnowledgePrompt.augment(SYSTEM_PROMPT, List.of(
                new RetrievedContext("정산 정책", "docs://policy", "VIP 는 T+3 영업일입니다.", 0.91),
                new RetrievedContext("수수료 정책", "docs://fee", "VIP 수수료는 2.5% 입니다.", 0.83)));

        assertThat(augmented)
                .startsWith(SYSTEM_PROMPT)
                .contains("--- 자료 1: 정산 정책 ---")
                .contains("VIP 는 T+3 영업일입니다.")
                .contains("--- 자료 2: 수수료 정책 ---")
                .contains("VIP 수수료는 2.5% 입니다.");
    }

    @Test
    @DisplayName("근거를 붙일 때 환각 금지 지시가 함께 들어간다 — RAG 의 실패 모드는 '못 찾음'이 아니라 '지어냄'이다")
    void withContext_includesAntiHallucinationInstruction() {
        String augmented = KnowledgePrompt.augment(SYSTEM_PROMPT, List.of(
                new RetrievedContext("제목", "docs://x", "내용", 0.7)));

        assertThat(augmented)
                .contains("[참고 자료 사용 규칙]")
                .contains("확인할 수 없습니다")
                .contains("무관하면 무시");
    }

    @Test
    @DisplayName("자료 순서는 입력 순서(= 유사도 내림차순)를 유지한다")
    void preservesOrder() {
        String augmented = KnowledgePrompt.augment(SYSTEM_PROMPT, List.of(
                new RetrievedContext("첫째", "docs://1", "A", 0.9),
                new RetrievedContext("둘째", "docs://2", "B", 0.5)));

        assertThat(augmented.indexOf("첫째")).isLessThan(augmented.indexOf("둘째"));
    }
}
