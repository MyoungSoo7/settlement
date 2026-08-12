package github.lms.lemuel.ai.rag.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class EmbeddingTest {

    @Test
    @DisplayName("L2 정규화 후 노름은 1 이다 (코사인 유사도가 벡터 길이에 오염되지 않는다)")
    void l2Normalized_hasUnitNorm() {
        Embedding normalized = Embedding.of(3f, 4f).l2Normalized();

        assertThat(normalized.norm()).isCloseTo(1.0, within(1e-6));
        assertThat(normalized.values()).containsExactly(new float[]{0.6f, 0.8f}, within(1e-6f));
    }

    @Test
    @DisplayName("정규화는 멱등이다 — 이미 단위벡터면 값이 그대로다")
    void l2Normalized_isIdempotent() {
        Embedding once = Embedding.of(1f, 2f, 3f).l2Normalized();

        assertThat(once.l2Normalized().norm()).isCloseTo(1.0, within(1e-6));
        assertThat(once.l2Normalized().values()).containsExactly(once.values(), within(1e-6f));
    }

    @Test
    @DisplayName("영벡터 정규화는 거부한다 — 통과시키면 NaN 이 DB 로 들어간다")
    void zeroVector_cannotBeNormalized() {
        assertThatThrownBy(() -> Embedding.of(0f, 0f, 0f).l2Normalized())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("영벡터");
    }

    @Test
    @DisplayName("NaN·무한대·null 이 섞인 응답은 값 생성 시점에 거부한다")
    void rejectsNonFiniteValues() {
        assertThatThrownBy(() -> Embedding.of(Arrays.asList(1.0, Double.NaN)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Embedding.of(Arrays.asList(1.0, Double.POSITIVE_INFINITY)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Embedding.of(Arrays.asList(1.0, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("빈 벡터는 거부한다")
    void rejectsEmpty() {
        assertThatThrownBy(() -> Embedding.of(new float[0]))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Embedding.of(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("pgvector 리터럴은 대괄호 + 쉼표 형식이다 (?::vector 캐스팅용)")
    void toPgVectorLiteral() {
        assertThat(Embedding.of(1f, -0.5f, 0f).toPgVectorLiteral()).isEqualTo("[1.0,-0.5,0.0]");
    }

    @Test
    @DisplayName("값 객체 — 방어적 복사로 외부에서 내부 배열을 바꿀 수 없다")
    void isImmutable() {
        float[] source = {1f, 2f};
        Embedding embedding = Embedding.of(source);

        source[0] = 99f;                 // 생성에 쓴 배열을 바꿔도
        embedding.values()[1] = 99f;     // 꺼낸 배열을 바꿔도

        assertThat(embedding.values()).containsExactly(1f, 2f);
    }

    @Test
    @DisplayName("동등성은 값 기반이다")
    void equality() {
        assertThat(Embedding.of(1f, 2f)).isEqualTo(Embedding.of(1f, 2f))
                .hasSameHashCodeAs(Embedding.of(1f, 2f));
        assertThat(Embedding.of(1f, 2f)).isNotEqualTo(Embedding.of(1f, 3f));
    }

    @Test
    @DisplayName("toString 은 벡터 원소를 노출하지 않는다 (로그 오염 방지)")
    void toStringHidesValues() {
        assertThat(Embedding.of(1.2345f, 6.789f)).hasToString("Embedding(dimension=2)");
    }

    @Test
    @DisplayName("인덱싱 가능 차원 상한은 pgvector 문서의 2000 이다")
    void maxIndexableDimension() {
        assertThat(Embedding.MAX_INDEXABLE_DIMENSION).isEqualTo(2000);
    }
}
