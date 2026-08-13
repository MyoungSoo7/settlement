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
 * 생성물 드리프트 게이트 (ADR 0033 Phase 2·3).
 *
 * <p>생성 코드와 설계서는 커밋되지만 <b>원본은 스펙 YAML</b> 이다. 누군가 생성물을 손으로 고치거나
 * 스펙만 고치고 재생성을 잊으면 이 테스트가 빌드를 깨뜨린다 — 그래야 "스펙이 원본"이 규율이 아니라
 * 사실이 된다.
 *
 * <p>재생성: {@code ./gradlew :settlement-service:generateTelegramSources}
 */
class TelegramGeneratedSourcesTest {

    private static final Path CODE_DIR = Path.of(
            "src/main/java/github/lms/lemuel/payout/adapter/out/firmbanking/fep/protocol/generated");
    /** 설계서는 저장소 문서 트리에 둔다 — 사람이 읽는 산출물이라 모듈 안이 아니라 docs 아래가 맞다. */
    private static final Path DOC_DIR = Path.of("../docs/plan/telegram");

    private static final TelegramCatalog CATALOG =
            TelegramSpecLoader.loadFromClasspath(TelegramSpecLoader.FIRMBANKING_LOCATION);

    @Test
    @DisplayName("생성물이 스펙과 일치한다 (write 모드면 재생성한다)")
    void generatedArtifactsMatchSpecs() throws IOException {
        Map<String, String> code = TelegramCodeGenerator.generate(CATALOG);
        Map<String, String> docs = TelegramCodeGenerator.generateDocs(CATALOG);

        if (Boolean.getBoolean("telegram.codegen.write")) {
            rewrite(CODE_DIR, code);
            rewrite(DOC_DIR, docs);
            return;
        }

        assertDirectoryMatches(CODE_DIR, code);
        assertDirectoryMatches(DOC_DIR, docs);
    }

    @Test
    @DisplayName("스펙 1건당 VO·코덱 2파일 + 설계서 1편(+목차)")
    void generatesExpectedArtifactCounts() {
        Map<String, String> code = TelegramCodeGenerator.generate(CATALOG);
        Map<String, String> docs = TelegramCodeGenerator.generateDocs(CATALOG);

        assertThat(code).hasSize(CATALOG.size() * 2);
        assertThat(docs).hasSize(CATALOG.size() + 1);
        assertThat(code).containsKeys(
                "TransferRequestTelegram.java", "TransferRequestCodec.java",
                "BulkTransferRequestTelegram.java", "BulkTransferRequestCodec.java",
                // 개정 2 는 이름 뒤에 V2 — 구 개정 클래스와 공존한다
                "BalanceResponseV2Telegram.java", "BalanceResponseV2Codec.java");
        assertThat(code.get("BulkTransferRequestTelegram.java"))
                .as("반복부는 List 로 노출된다")
                .contains("List<Detail> details")
                .contains("public record Detail(");
        assertThat(code.get("BulkTransferRequestCodec.java"))
                .as("가변 전문은 건수를 읽어 레이아웃을 만든다")
                .contains("SPEC.readOccurrences(raw)")
                .contains("SPEC.layoutFor(");
        assertThat(code.get("TransferRequestTelegram.java"))
                .as("scale 선언 필드만 BigDecimal 이 된다")
                .contains("BigDecimal amount")
                .contains("String accountNo");
        assertThat(docs.get("README.md")).contains("| `0220` |").contains("가변");
    }

    private static void assertDirectoryMatches(Path directory, Map<String, String> expected) throws IOException {
        assertThat(directory).as("생성물 디렉터리 — 없으면 generateTelegramSources 를 먼저 돌린다").exists();

        try (Stream<Path> files = Files.list(directory)) {
            assertThat(new TreeSet<>(files.map(path -> path.getFileName().toString()).toList()))
                    .as("%s 파일 목록 — 스펙에서 사라진 전문의 잔재가 남으면 안 된다", directory)
                    .isEqualTo(new TreeSet<>(expected.keySet()));
        }
        for (Map.Entry<String, String> entry : expected.entrySet()) {
            String actual = Files.readString(directory.resolve(entry.getKey()), StandardCharsets.UTF_8);
            assertThat(normalize(actual))
                    .as("%s — 스펙과 생성물이 어긋난다. 손으로 고쳤거나 재생성을 잊었다", entry.getKey())
                    .isEqualTo(normalize(entry.getValue()));
        }
    }

    private static void rewrite(Path directory, Map<String, String> artifacts) throws IOException {
        Files.createDirectories(directory);
        try (Stream<Path> existing = Files.list(directory)) {
            List<Path> obsolete = existing
                    .filter(path -> !artifacts.containsKey(path.getFileName().toString()))
                    .toList();
            for (Path path : obsolete) {
                Files.delete(path);
            }
        }
        for (Map.Entry<String, String> entry : artifacts.entrySet()) {
            Files.writeString(directory.resolve(entry.getKey()), entry.getValue(), StandardCharsets.UTF_8);
        }
    }

    /** 개행 정규화 — Windows 체크아웃에서 CRLF 로 바뀌어도 내용 비교가 흔들리지 않게. */
    private static String normalize(String source) {
        return source.replace("\r\n", "\n");
    }
}
