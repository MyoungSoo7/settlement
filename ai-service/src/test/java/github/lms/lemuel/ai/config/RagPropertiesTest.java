package github.lms.lemuel.ai.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RagPropertiesTest {

    @Test
    @DisplayName("미지정(0) 값은 안전한 기본값으로 채워진다")
    void appliesDefaults() {
        RagProperties properties = new RagProperties(true, 0, 0.55, 0, -1);

        assertThat(properties.topK()).isEqualTo(4);
        assertThat(properties.chunkMaxChars()).isEqualTo(1200);
        assertThat(properties.chunkOverlapChars()).isEqualTo(200);
    }

    @Test
    @DisplayName("overlap ≥ max 인 설정은 부팅 시점에 거부된다 — 런타임에 청킹이 무한 루프이기 때문")
    void rejectsOverlapNotSmallerThanMax() {
        assertThatThrownBy(() -> new RagProperties(true, 4, 0.55, 500, 500))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("chunk-overlap-chars");
    }

    @Test
    @DisplayName("명시된 값은 그대로 보존된다")
    void keepsExplicitValues() {
        RagProperties properties = new RagProperties(false, 8, 0.8, 2000, 100);

        assertThat(properties.enabled()).isFalse();
        assertThat(properties.topK()).isEqualTo(8);
        assertThat(properties.minSimilarity()).isEqualTo(0.8);
        assertThat(properties.chunkMaxChars()).isEqualTo(2000);
        assertThat(properties.chunkOverlapChars()).isEqualTo(100);
    }
}
