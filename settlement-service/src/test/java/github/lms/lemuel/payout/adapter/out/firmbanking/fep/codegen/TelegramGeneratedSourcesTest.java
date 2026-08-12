package github.lms.lemuel.payout.adapter.out.firmbanking.fep.codegen;

import github.lms.lemuel.payout.adapter.out.firmbanking.fep.protocol.spec.TelegramCatalog;
import github.lms.lemuel.payout.adapter.out.firmbanking.fep.protocol.spec.TelegramSpecLoader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 생성물 드리프트 게이트 (ADR 0033 Phase 2).
 *
 * <p>생성 코드는 커밋되지만 <b>원본은 스펙 YAML</b> 이다. 누군가 생성물을 손으로 고치거나 스펙만 고치고
 * 재생성을 잊으면 이 테스트가 빌드를 깨뜨린다 — 그래야 "스펙이 원본"이 규율이 아니라 사실이 된다.
 *
 * <p>재생성: {@code ./gradlew :settlement-service:generateTelegramSources}
 */
class TelegramGeneratedSourcesTest {

    private static final Path OUTPUT_DIR = Path.of(
            "src/main/java/github/lms/lemuel/payout/adapter/out/firmbanking/fep/protocol/generated");

    @Test
    @DisplayName("생성물이 스펙과 일치한다 (write 모드면 재생성한다)")
    void generatedSourcesMatchSpecs() throws IOException {
        TelegramCatalog catalog = TelegramSpecLoader.loadFromClasspath(TelegramSpecLoader.FIRMBANKING_LOCATION);
        Map<String, String> expected = TelegramCodeGenerator.generate(catalog);

        if (Boolean.getBoolean("telegram.codegen.write")) {
            rewrite(expected);
            return;
        }

        assertThat(OUTPUT_DIR).as("생성물 디렉터리 — 없으면 generateTelegramSources 를 먼저 돌린다").exists();

        try (Stream<Path> files = Files.list(OUTPUT_DIR)) {
            assertThat(new TreeSet<>(files.map(p -> p.getFileName().toString()).toList()))
                    .as("생성물 파일 목록 — 스펙에서 사라진 전문의 잔재가 남으면 안 된다")
                    .isEqualTo(new TreeSet<>(expected.keySet()));
        }

        for (Map.Entry<String, String> entry : expected.entrySet()) {
            String actual = Files.readString(OUTPUT_DIR.resolve(entry.getKey()), StandardCharsets.UTF_8);
            assertThat(normalize(actual))
                    .as("%s — 스펙과 생성물이 어긋난다. 손으로 고쳤거나 재생성을 잊었다", entry.getKey())
                    .isEqualTo(normalize(entry.getValue()));
        }
    }

    @Test
    @DisplayName("전문 10종이 VO·코덱 2파일씩 만들어진다")
    void generatesTwoFilesPerTelegram() {
        TelegramCatalog catalog = TelegramSpecLoader.loadFromClasspath(TelegramSpecLoader.FIRMBANKING_LOCATION);

        Map<String, String> generated = TelegramCodeGenerator.generate(catalog);

        assertThat(generated).hasSize(catalog.size() * 2);
        assertThat(generated).containsKeys(
                "TransferRequestTelegram.java", "TransferRequestCodec.java",
                "BulkTransferRequestTelegram.java", "BulkTransferRequestCodec.java");
        assertThat(generated.get("BulkTransferRequestTelegram.java"))
                .as("반복부는 List 로 노출된다")
                .contains("List<Detail> details")
                .contains("public record Detail(");
        assertThat(generated.get("TransferRequestTelegram.java"))
                .as("scale 선언 필드만 BigDecimal 이 된다")
                .contains("BigDecimal amount")
                .contains("String accountNo");
    }

    private static void rewrite(Map<String, String> sources) throws IOException {
        Files.createDirectories(OUTPUT_DIR);
        try (Stream<Path> stale = Files.list(OUTPUT_DIR)) {
            List<Path> obsolete = stale.filter(p -> !sources.containsKey(p.getFileName().toString())).toList();
            for (Path path : obsolete) {
                Files.delete(path);
            }
        }
        for (Map.Entry<String, String> entry : sources.entrySet()) {
            Files.writeString(OUTPUT_DIR.resolve(entry.getKey()), entry.getValue(), StandardCharsets.UTF_8);
        }
    }

    /** 개행 정규화 — Windows 체크아웃에서 CRLF 로 바뀌어도 내용 비교가 흔들리지 않게. */
    private static String normalize(String source) {
        return source.replace("\r\n", "\n");
    }
}
