package github.lms.lemuel.settlement;

import github.lms.lemuel.SettlementServiceApplication;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Data Definition Standard 강제 — settlement (근거: ~/wiki/DATA-STANDARD.md).
 *
 * <p><b>왜 소스 스캔인가</b>: 이 gradle 셋업에서 ArchUnit {@code ClassFileImporter} 는 어떤 방식으로도
 * 클래스를 0개 임포트한다(기존 {@code *ArchitectureTest} 가 {@code allowEmptyShould(true)} 로 vacuous
 * 통과하던 원인). 그래서 클래스패스 비의존 <b>소스 파일 스캔</b> 으로 강제한다.
 *
 * <ul>
 *   <li><b>N1(시각)</b>: 순간에 {@code LocalDateTime} 금지. 기존 위반은 baseline 동결(ratchet), 신규만 차단.</li>
 *   <li><b>N6a(enum)</b>: {@code EnumType.STRING} 저장만. ORDINAL/무인자 {@code @Enumerated} 금지 (현재 0 — 방지 가드).</li>
 *   <li><b>N6b(enum)</b>: 마이그레이션에 native PG enum({@code CREATE TYPE ... AS ENUM}) 금지 (현재 0 — 방지 가드).</li>
 * </ul>
 */
class DataStandardArchTest {

    // ---------------------- N1: 시각 (ratchet) ----------------------
    @Test
    void n1_noNewLocalDateTime() throws Exception {
        Set<String> current = scan(javaUnder("src/main/java"), c -> c.contains("LocalDateTime"));
        Path baseline = moduleRoot().resolve("src/test/resources/datastandard/n1-localdatetime-baseline.txt");
        ratchet("N1", current, baseline,
                "신규 LocalDateTime 사용 — 순간은 UTC OffsetDateTime/Instant (~/wiki/DATA-STANDARD.md N1)");
    }

    // ---------------------- N6a: enum STRING only (must stay 0) ----------------------
    @Test
    void n6a_enumStoredAsStringNotOrdinal() throws Exception {
        Set<String> v = scan(javaUnder("src/main/java"), DataStandardArchTest::usesOrdinalEnum);
        mustBeZero("N6a", v,
                "enum은 EnumType.STRING 저장만. ORDINAL/무인자 @Enumerated 금지 (~/wiki/DATA-STANDARD.md N6)");
    }

    // ---------------------- N6b: no native PG enum in migrations (must stay 0) ----------------------
    @Test
    void n6b_noNativePostgresEnumInMigrations() throws Exception {
        Set<String> v = scan(filesUnder("src/main/resources", ".sql"),
                c -> c.toUpperCase(Locale.ROOT).contains("AS ENUM"));
        mustBeZero("N6b", v,
                "마이그레이션 native PG enum(CREATE TYPE ... AS ENUM) 금지 — varchar + EnumType.STRING (~/wiki/DATA-STANDARD.md N6)");
    }

    // ============================ helpers ============================

    /** @Enumerated 인데 같은 줄에 STRING 없음(=ORDINAL 기본), 또는 명시적 EnumType.ORDINAL. */
    private static boolean usesOrdinalEnum(String content) {
        if (content.contains("EnumType.ORDINAL")) return true;
        for (String line : content.split("\n")) {
            if (line.contains("@Enumerated") && !line.contains("STRING")) return true;
        }
        return false;
    }

    private static Set<String> scan(List<Path> files, Predicate<String> hit) {
        Path root = moduleRoot();
        Set<String> out = new TreeSet<>();
        for (Path f : files) {
            try {
                if (hit.test(Files.readString(f))) {
                    out.add(root.relativize(f).toString().replace('\\', '/'));
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return out;
    }

    /** baseline 대비 신규 위반만 실패(ratchet). baseline 없으면 현재 위반을 동결하고 통과(커밋 필요). */
    private static void ratchet(String id, Set<String> current, Path baselineFile, String message) throws IOException {
        if (!Files.exists(baselineFile)) {
            Files.createDirectories(baselineFile.getParent());
            Files.write(baselineFile, current);
            System.out.println(id + ": baseline 생성(" + current.size() + "건) → 반드시 커밋: " + baselineFile);
            return;
        }
        Set<String> baseline = new TreeSet<>(Files.readAllLines(baselineFile));
        Set<String> introduced = new TreeSet<>(current);
        introduced.removeAll(baseline);
        System.out.println(id + ": current=" + current.size() + " baseline=" + baseline.size()
                + " introduced=" + introduced.size());
        if (!introduced.isEmpty()) {
            fail(id + " 위반 — " + message + "\n  " + String.join("\n  ", introduced));
        }
    }

    private static void mustBeZero(String id, Set<String> violations, String message) {
        System.out.println(id + ": violations=" + violations.size());
        if (!violations.isEmpty()) {
            fail(id + " 위반 — " + message + "\n  " + String.join("\n  ", violations));
        }
    }

    private static List<Path> javaUnder(String rel) throws IOException {
        return filesUnder(rel, ".java");
    }

    private static List<Path> filesUnder(String rel, String ext) throws IOException {
        Path dir = moduleRoot().resolve(rel);
        assertTrue(Files.isDirectory(dir), dir + " 없음");
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(p -> p.toString().endsWith(ext)).collect(Collectors.toList());
        }
    }

    /** SettlementServiceApplication code-source 위치에서 위로 올라가 src/main/java 를 가진 모듈 루트. */
    private static Path moduleRoot() {
        try {
            Path p = Path.of(SettlementServiceApplication.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            if (!Files.isDirectory(p)) p = p.getParent();
            while (p != null && !Files.isDirectory(p.resolve("src/main/java"))) p = p.getParent();
            if (p == null) throw new IllegalStateException("모듈 루트 탐색 실패");
            return p;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
