package github.lms.lemuel.ai.chat.adapter.out.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.ai.chat.application.exception.AiUnavailableException;
import github.lms.lemuel.ai.chat.domain.ChatCompletion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link DeepSeekChatAdapter} 응답 파싱 + {@code <think>} 필터 단위 검증 — 실 API 없이
 * OpenAI 호환 Chat Completions 정본 응답 형태로 분기를 전수 실행한다.
 * ({@code parse}/{@code extractDelta}/{@code ThinkTagFilter} 는 package-private)
 */
class DeepSeekChatAdapterTest {

    private final ObjectMapper om = new ObjectMapper();

    @Nested
    @DisplayName("DeepSeekChatProperties — 기본값과 구성 판정")
    class Properties {

        @Test
        @DisplayName("빈 설정 — DeepSeek 원격 기본값으로 채워지고 미구성 상태")
        void defaults() {
            var p = new DeepSeekChatProperties(null, null, null, 0);
            assertThat(p.apiKey()).isEmpty();
            assertThat(p.model()).isEqualTo("deepseek-chat");
            assertThat(p.baseUrl()).isEqualTo("https://api.deepseek.com/v1");
            assertThat(p.maxTokens()).isEqualTo(2048);
            assertThat(p.configured()).isFalse();
        }

        @Test
        @DisplayName("로컬 Ollama 로 baseUrl·model 을 갈아끼울 수 있다(같은 어댑터 재사용)")
        void ollamaOverride() {
            var p = new DeepSeekChatProperties("ollama", "deepseek-r1:7b", "http://localhost:11434/v1", 512);
            assertThat(p.baseUrl()).isEqualTo("http://localhost:11434/v1");
            assertThat(p.model()).isEqualTo("deepseek-r1:7b");
            assertThat(p.configured()).isTrue();
        }

        @Test
        @DisplayName("공백뿐인 키는 미구성으로 본다")
        void blankKey_notConfigured() {
            assertThat(new DeepSeekChatProperties("   ", null, null, 0).configured()).isFalse();
        }
    }

    @Nested
    @DisplayName("parse — 비스트리밍 chat/completions 응답")
    class Parse {

        @Test
        @DisplayName("정상 응답 — content·usage 토큰 추출")
        void parse_ok() {
            String json = """
                    {"id":"chat-1","object":"chat.completion","created":1,"model":"deepseek-chat",
                    "choices":[{"index":0,"message":{"role":"assistant","content":"정산 주기는 등급별로 다릅니다."},
                    "finish_reason":"stop"}],
                    "usage":{"prompt_tokens":6,"completion_tokens":42,"total_tokens":48}}
                    """;
            ChatCompletion c = DeepSeekChatAdapter.parse(json, "deepseek-chat", om);
            assertThat(c.text()).isEqualTo("정산 주기는 등급별로 다릅니다.");
            assertThat(c.model()).isEqualTo("deepseek-chat");
            assertThat(c.inputTokens()).isEqualTo(6);
            assertThat(c.outputTokens()).isEqualTo(42);
        }

        @Test
        @DisplayName("usage 미제공 — 토큰은 null 이되 응답은 정상")
        void parse_noUsage_tokensNull() {
            String json = """
                    {"choices":[{"index":0,"message":{"role":"assistant","content":"답변"},"finish_reason":"stop"}]}
                    """;
            ChatCompletion c = DeepSeekChatAdapter.parse(json, "deepseek-chat", om);
            assertThat(c.text()).isEqualTo("답변");
            assertThat(c.inputTokens()).isNull();
            assertThat(c.outputTokens()).isNull();
        }

        @Test
        @DisplayName("reasoner 응답 — reasoning_content(사고과정)는 버리고 content 만 취한다")
        void parse_reasoner_dropsReasoningContent() {
            String json = """
                    {"choices":[{"index":0,"message":{"role":"assistant",
                    "reasoning_content":"내부 사고: 수수료율을 떠올린다","content":"VIP 는 2.5% 입니다."},
                    "finish_reason":"stop"}],
                    "usage":{"prompt_tokens":3,"completion_tokens":9}}
                    """;
            ChatCompletion c = DeepSeekChatAdapter.parse(json, "deepseek-reasoner", om);
            assertThat(c.text()).isEqualTo("VIP 는 2.5% 입니다.");
            assertThat(c.text()).doesNotContain("내부 사고");
        }

        @Test
        @DisplayName("content 안에 인라인 <think> (Ollama deepseek-r1) — 사고과정은 걷어낸다")
        void parse_inlineThink_stripped() {
            String json = """
                    {"choices":[{"index":0,"message":{"role":"assistant",
                    "content":"<think>사용자가 정산을 묻는다</think>T+7 영업일입니다."},"finish_reason":"stop"}]}
                    """;
            ChatCompletion c = DeepSeekChatAdapter.parse(json, "deepseek-r1:7b", om);
            assertThat(c.text()).isEqualTo("T+7 영업일입니다.");
        }

        @Test
        @DisplayName("빈 choices — AiUnavailableException")
        void parse_emptyChoices_throws() {
            assertThatThrownBy(() -> DeepSeekChatAdapter.parse("{\"choices\":[]}", "deepseek-chat", om))
                    .isInstanceOf(AiUnavailableException.class)
                    .hasMessageContaining("빈 응답");
        }

        @Test
        @DisplayName("빈 content(max_tokens 소진) — finish_reason 을 사유로 AiUnavailableException")
        void parse_blankContent_throws() {
            String json = """
                    {"choices":[{"index":0,"message":{"role":"assistant","content":""},"finish_reason":"length"}],
                    "usage":{"prompt_tokens":6,"completion_tokens":2048}}
                    """;
            assertThatThrownBy(() -> DeepSeekChatAdapter.parse(json, "deepseek-chat", om))
                    .isInstanceOf(AiUnavailableException.class)
                    .hasMessageContaining("length");
        }

        @Test
        @DisplayName("사고과정만 있고 답변이 비면 — 빈 응답으로 실패(폴백 금지)")
        void parse_onlyThink_throws() {
            String json = """
                    {"choices":[{"index":0,"message":{"role":"assistant","content":"<think>고민중</think>"},
                    "finish_reason":"length"}]}
                    """;
            assertThatThrownBy(() -> DeepSeekChatAdapter.parse(json, "deepseek-r1:7b", om))
                    .isInstanceOf(AiUnavailableException.class);
        }

        @Test
        @DisplayName("깨진 JSON — AiUnavailableException(파싱 실패)")
        void parse_malformed_throws() {
            assertThatThrownBy(() -> DeepSeekChatAdapter.parse("not-json{", "deepseek-chat", om))
                    .isInstanceOf(AiUnavailableException.class)
                    .hasMessageContaining("파싱");
        }

        @Test
        @DisplayName("null 응답 본문 — 빈 응답으로 AiUnavailableException")
        void parse_null_throws() {
            assertThatThrownBy(() -> DeepSeekChatAdapter.parse(null, "deepseek-chat", om))
                    .isInstanceOf(AiUnavailableException.class);
        }
    }

    @Nested
    @DisplayName("extractDelta — 스트리밍 청크")
    class ExtractDelta {

        @Test
        @DisplayName("delta.content 를 뽑는다")
        void delta_ok() throws Exception {
            var chunk = om.readTree(
                    "{\"choices\":[{\"index\":0,\"delta\":{\"content\":\"조각\"},\"finish_reason\":null}]}");
            assertThat(DeepSeekChatAdapter.extractDelta(chunk)).isEqualTo("조각");
        }

        @Test
        @DisplayName("role 만 실린 첫 청크 — 빈 문자열")
        void delta_roleOnly_empty() throws Exception {
            var chunk = om.readTree("{\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\"}}]}");
            assertThat(DeepSeekChatAdapter.extractDelta(chunk)).isEmpty();
        }

        @Test
        @DisplayName("usage 전용 청크(choices 비어 있음) — 빈 문자열")
        void delta_usageOnly_empty() throws Exception {
            var chunk = om.readTree("{\"choices\":[],\"usage\":{\"prompt_tokens\":3}}");
            assertThat(DeepSeekChatAdapter.extractDelta(chunk)).isEmpty();
        }

        @Test
        @DisplayName("종료 청크의 content:null — 문자열 \"null\" 이 새어나오면 안 된다")
        void delta_nullContent_empty() throws Exception {
            var chunk = om.readTree(
                    "{\"choices\":[{\"index\":0,\"delta\":{\"content\":null},\"finish_reason\":\"stop\"}]}");
            assertThat(DeepSeekChatAdapter.extractDelta(chunk)).isEmpty();
        }

        @Test
        @DisplayName("reasoning_content 만 실린 청크 — 사용자에게 흘리지 않는다")
        void delta_reasoningOnly_empty() throws Exception {
            var chunk = om.readTree(
                    "{\"choices\":[{\"delta\":{\"reasoning_content\":\"내부 사고\"}}]}");
            assertThat(DeepSeekChatAdapter.extractDelta(chunk)).isEmpty();
        }
    }

    @Nested
    @DisplayName("ThinkTagFilter — 청크 경계를 넘나드는 <think> 제거")
    class Think {

        @Test
        @DisplayName("한 청크 안에서 열고 닫히면 그 구간만 사라진다")
        void singleChunk() {
            var f = new DeepSeekChatAdapter.ThinkTagFilter();
            assertThat(f.apply("<think>사고</think>답변")).isEqualTo("답변");
            assertThat(f.flush()).isEmpty();
        }

        @Test
        @DisplayName("태그가 청크 경계에 걸려도 정확히 제거된다")
        void splitAcrossChunks() {
            var f = new DeepSeekChatAdapter.ThinkTagFilter();
            StringBuilder out = new StringBuilder();
            for (String chunk : new String[]{"<thi", "nk>내부 ", "사고</thi", "nk>진짜 답변"}) {
                out.append(f.apply(chunk));
            }
            out.append(f.flush());
            assertThat(out.toString()).isEqualTo("진짜 답변");
        }

        @Test
        @DisplayName("think 이 없으면 원문 그대로 흘린다")
        void noThink_passthrough() {
            var f = new DeepSeekChatAdapter.ThinkTagFilter();
            assertThat(f.apply("안녕하세요") + f.flush()).isEqualTo("안녕하세요");
        }

        @Test
        @DisplayName("think 이 아닌 꺾쇠(<b> 등)는 보존한다")
        void otherAngleBracket_preserved() {
            var f = new DeepSeekChatAdapter.ThinkTagFilter();
            assertThat(f.apply("<b>굵게</b> 끝") + f.flush()).isEqualTo("<b>굵게</b> 끝");
        }

        @Test
        @DisplayName("여는 태그 조각으로 끝나면 flush 가 원문을 되돌려준다(유실 금지)")
        void danglingPartialTag_flushed() {
            var f = new DeepSeekChatAdapter.ThinkTagFilter();
            assertThat(f.apply("답변<thi")).isEqualTo("답변");
            assertThat(f.flush()).isEqualTo("<thi");
        }

        @Test
        @DisplayName("닫히지 않은 think 은 flush 해도 새어나오지 않는다")
        void unclosedThink_notLeaked() {
            var f = new DeepSeekChatAdapter.ThinkTagFilter();
            assertThat(f.apply("<think>영원히 고민")).isEmpty();
            assertThat(f.flush()).isEmpty();
        }

        @Test
        @DisplayName("think 블록이 두 번 나와도 각각 제거된다")
        void multipleBlocks() {
            var f = new DeepSeekChatAdapter.ThinkTagFilter();
            assertThat(f.apply("<think>1</think>가<think>2</think>나") + f.flush()).isEqualTo("가나");
        }

        @Test
        @DisplayName("stripAll — 완결 텍스트에서 한 번에 걷어낸다")
        void stripAll_ok() {
            assertThat(DeepSeekChatAdapter.ThinkTagFilter.stripAll("<think>x</think>본문")).isEqualTo("본문");
            assertThat(DeepSeekChatAdapter.ThinkTagFilter.stripAll("본문만")).isEqualTo("본문만");
        }
    }
}
