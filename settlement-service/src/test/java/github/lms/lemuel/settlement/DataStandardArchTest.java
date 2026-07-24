package github.lms.lemuel.settlement;

import github.lms.lemuel.SettlementServiceApplication;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Data Definition Standard 강제 — settlement (근거: ~/wiki/DATA-STANDARD.md).
 *
 * <p><b>N1 (시각)</b>: 순간(instant)은 UTC tz-aware(OffsetDateTime/Instant)만, {@code LocalDateTime} 금지.
 *
 * <p><b>왜 소스 스캔인가</b>: 이 gradle 셋업에서 ArchUnit {@code ClassFileImporter}는 어떤 방식으로도
 * 0개 클래스를 임포트한다(기존 arch 테스트가 {@code allowEmptyShould(true)}로 vacuous 통과하던 원인).
 * 그래서 클래스패스에 의존하지 않는 <b>소스 파일 스캔 + baseline 동결(ratchet)</b>로 N1을 강제한다:
 * {@code src/main/java} 에서 {@code LocalDateTime} 을 쓰는 파일 집합이 커밋된 baseline 의 부분집합이어야
 * 하고, <b>신규 파일이 LocalDateTime 을 도입하면 실패</b>한다. 레거시를 제거하면 통과(부분집합 유지).
 */
class DataStandardArchTest {

    private static final String NEEDLE = "LocalDateTime";
    private static final Path BASELINE_REL = Paths.get("src/test/resources/datastandard/n1-localdatetime-baseline.txt");

    @Test
    void n1_noNewLocalDateTime() throws Exception {
        Path moduleRoot = moduleRoot();
        Path src = moduleRoot.resolve("src/main/java");
        assertTrue(Files.isDirectory(src), "src/main/java 없음: " + src);

        Set<String> current = scan(src);
        Path baselineFile = moduleRoot.resolve(BASELINE_REL);

        // 최초 실행: baseline 없으면 현재 위반을 동결하고 통과(커밋 필요).
        if (!Files.exists(baselineFile)) {
            Files.createDirectories(baselineFile.getParent());
            Files.write(baselineFile, current);
            System.out.println("N1: baseline 생성(" + current.size() + "건) → 반드시 커밋: " + baselineFile);
            return;
        }

        Set<String> baseline = new TreeSet<>(Files.readAllLines(baselineFile));
        Set<String> introduced = new TreeSet<>(current);
        introduced.removeAll(baseline);
        System.out.println("N1: current=" + current.size() + " baseline=" + baseline.size()
                + " introduced=" + introduced.size());

        if (!introduced.isEmpty()) {
            fail("N1 위반 — 신규 LocalDateTime 사용 파일(순간은 UTC OffsetDateTime/Instant 사용, ~/wiki/DATA-STANDARD.md):\n  "
                    + String.join("\n  ", introduced));
        }
    }

    /** src/main/java 아래 .java 중 LocalDateTime 을 쓰는 파일의 모듈상대경로 집합. */
    private static Set<String> scan(Path src) throws IOException {
        try (Stream<Path> paths = Files.walk(src)) {
            return paths
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(DataStandardArchTest::usesNeedle)
                    .map(p -> src.getParent().getParent().getParent().relativize(p).toString().replace('\\', '/'))
                    .collect(Collectors.toCollection(TreeSet::new));
        }
    }

    private static boolean usesNeedle(Path javaFile) {
        try {
            return Files.readString(javaFile).contains(NEEDLE);
        } catch (IOException e) {
            return false;
        }
    }

    /** SettlementServiceApplication 의 code-source 위치에서 위로 올라가 src/main/java 를 가진 모듈 루트를 찾는다. */
    private static Path moduleRoot() throws Exception {
        Path p = Paths.get(SettlementServiceApplication.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());
        if (!Files.isDirectory(p)) p = p.getParent();        // jar 인 경우 그 디렉토리로
        while (p != null && !Files.isDirectory(p.resolve("src/main/java"))) p = p.getParent();
        if (p == null) throw new IllegalStateException("모듈 루트(src/main/java 보유) 탐색 실패");
        return p;
    }
}
