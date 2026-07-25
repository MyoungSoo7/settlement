package github.lms.lemuel.settlement;

import github.lms.lemuel.SettlementServiceApplication;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Data Definition Standard 강제 — settlement (근거: {@code ~/wiki/DATA-STANDARD.md}).
 *
 * <ul>
 *   <li><b>N1(시각)</b>: 순간(instant)은 UTC tz-aware(OffsetDateTime/Instant)만, {@code LocalDateTime} 금지.
 *       기존 위반은 baseline 동결(ratchet), <b>신규만</b> 차단.</li>
 *   <li><b>N6a(enum)</b>: {@code EnumType.STRING} 저장만. ORDINAL/무인자 {@code @Enumerated} 금지 (현재 0 — 방지 가드).</li>
 *   <li><b>N6b(enum)</b>: 마이그레이션에 native PG enum({@code CREATE TYPE ... AS ENUM}) 금지 (현재 0 — 방지 가드).</li>
 * </ul>
 *
 * <p><b>왜 소스 스캔인가</b>: N6b 는 마이그레이션 SQL 을 보므로 바이트코드로는 검사할 수 없다. N1·N6a 는
 * ArchUnit 으로도 표현 가능하지만, 세 규칙을 한 곳에서 같은 방식으로 다루려고 소스 스캔으로 통일했다.
 * (ArchUnit 기반 동결이 필요해지면 {@code FreezingArchRule} 을 검토할 것. settlement-service 의 ArchUnit
 * 1.3.0 핀이 Java 25 바이트코드를 못 읽어 클래스를 0개 임포트하던 문제는 1.4.1 로 이미 해소됐다.)
 */
class DataStandardArchTest {

    /**
     * baseline 재생성 스위치 — 의도적 갱신에만 사용한다:
     * {@code DATASTANDARD_BASELINE_WRITE=true ./gradlew :settlement-service:test --tests '*DataStandardArchTest*'}
     * (재생성 후에도 <b>실패</b>시킨다 — 파일을 커밋하고 스위치 없이 다시 돌려 GREEN 을 확인하게 하려는 것.)
     *
     * <p>시스템 프로퍼티가 아니라 <b>환경변수</b>인 이유: gradle 의 {@code -D} 는 데몬 JVM 에만 붙고
     * 테스트 JVM 으로는 전달되지 않는다(별도 {@code systemProperty} 배선 필요). 환경변수는 그대로 상속된다.
     */
    private static final String WRITE_BASELINE = "DATASTANDARD_BASELINE_WRITE";

    private static final String N1_BASELINE = "src/test/resources/datastandard/n1-localdatetime-baseline.txt";

    // ---------------------- N1: 시각 (ratchet) ----------------------
    @Test
    void n1_noNewLocalDateTime() throws IOException {
        ratchet("N1",
                scan(filesUnder("src/main/java", ".java"), c -> c.contains("LocalDateTime")),
                moduleRoot().resolve(N1_BASELINE),
                "순간(instant)은 UTC OffsetDateTime/Instant — LocalDateTime 금지 (~/wiki/DATA-STANDARD.md N1)");
    }

    // ---------------------- N6a: enum STRING only (must stay 0) ----------------------
    @Test
    void n6a_enumStoredAsStringNotOrdinal() throws IOException {
        mustBeZero("N6a",
                scan(filesUnder("src/main/java", ".java"), DataStandardArchTest::usesOrdinalEnum),
                "enum 은 EnumType.STRING 저장만. ORDINAL/무인자 @Enumerated 금지 (~/wiki/DATA-STANDARD.md N6)");
    }

    // ---------------------- N6b: no native PG enum in migrations (must stay 0) ----------------------
    @Test
    void n6b_noNativePostgresEnumInMigrations() throws IOException {
        mustBeZero("N6b",
                scan(filesUnder("src/main/resources", ".sql"),
                        c -> c.toUpperCase(Locale.ROOT).contains("AS ENUM")),
                "마이그레이션 native PG enum(CREATE TYPE ... AS ENUM) 금지 — varchar + EnumType.STRING (~/wiki/DATA-STANDARD.md N6)");
    }

    // ============================ ratchet ============================

    /**
     * baseline 대비 <b>신규</b> 위반만 실패시킨다.
     *
     * <p><b>키는 경로가 아니라 단순 클래스명이다.</b> 경로 키는 위반이 하나도 늘지 않았는데 패키지 이동만으로
     * "신규 도입" 오판을 낸다 — 실제로 {@code SettlementDetailDto} 를
     * {@code adapter.out.persistence.querydsl.dto} → {@code application.port.out.dto} 로 옮긴 것만으로
     * (위반 67건 그대로) CI 가 깨졌다. FQN 도 패키지가 바뀌면 함께 바뀌므로 이동 내성이 없다.
     * 단순 클래스명이 겹치면 한쪽 위반이 가려지므로 {@link #keyBySimpleName} 이 충돌을 먼저 실패시킨다.
     *
     * <p>양방향으로 조인다: 신규 도입은 물론, <b>더 이상 위반이 아닌 baseline 항목도 실패</b>시켜 제거를
     * 강제한다(단조 감소 — 고친 파일이 조용히 되돌아오지 못하게).
     */
    private static void ratchet(String id, List<Path> hits, Path baselineFile, String message) throws IOException {
        Map<String, String> current = keyBySimpleName(id, hits);

        if (Boolean.parseBoolean(System.getenv(WRITE_BASELINE))) {
            Files.createDirectories(baselineFile.getParent());
            Files.write(baselineFile, current.keySet());
            fail(id + ": baseline 재생성(" + current.size() + "건) → 커밋한 뒤 " + WRITE_BASELINE
                    + " 없이 다시 실행해 GREEN 을 확인하라: " + baselineFile);
        }
        if (!Files.exists(baselineFile)) {
            // fail-open 금지: baseline 이 사라지면 가드가 조용히 자기치유하며 통과해 버린다.
            fail(id + " baseline 없음 — 가드가 무력화된 상태다: " + baselineFile
                    + "\n  의도한 (재)생성이면 " + WRITE_BASELINE + "=true 로 명시적으로 만들고 커밋하라.");
        }

        Set<String> baseline = new TreeSet<>(Files.readAllLines(baselineFile));
        baseline.removeIf(String::isBlank);

        Set<String> introduced = new TreeSet<>(current.keySet());
        introduced.removeAll(baseline);

        Set<String> resolved = new TreeSet<>(baseline);
        resolved.removeAll(current.keySet());

        System.out.println(id + ": current=" + current.size() + " baseline=" + baseline.size()
                + " introduced=" + introduced.size() + " resolved=" + resolved.size());

        if (!introduced.isEmpty()) {
            fail(id + " 위반 — " + message + "\n  신규:\n    " + introduced.stream()
                    .map(name -> name + "  (" + current.get(name) + ")")
                    .collect(joining("\n    ")));
        }
        if (!resolved.isEmpty()) {
            fail(id + " baseline 정리 필요 — 아래는 더 이상 위반이 아니다. baseline 에서 제거해 되돌아오지 못하게 하라"
                    + "(ratchet 은 한 방향으로만 조인다):\n    " + String.join("\n    ", resolved));
        }
    }

    private static void mustBeZero(String id, List<Path> violations, String message) {
        System.out.println(id + ": violations=" + violations.size());
        if (!violations.isEmpty()) {
            fail(id + " 위반 — " + message + "\n    "
                    + violations.stream().map(DataStandardArchTest::rel).collect(joining("\n    ")));
        }
    }

    // ============================ helpers ============================

    /** {@code @Enumerated} 인데 같은 줄에 STRING 없음(=ORDINAL 기본), 또는 명시적 EnumType.ORDINAL. */
    private static boolean usesOrdinalEnum(String content) {
        if (content.contains("EnumType.ORDINAL")) return true;
        for (String line : content.split("\n")) {
            if (line.contains("@Enumerated") && !line.contains("STRING")) return true;
        }
        return false;
    }

    /** 파일 목록을 {단순 클래스명 → 모듈상대경로} 로. 같은 단순명이 둘이면 위반이 가려지므로 실패시킨다. */
    private static Map<String, String> keyBySimpleName(String id, List<Path> hits) {
        Map<String, String> byName = new TreeMap<>();
        List<String> collisions = new ArrayList<>();
        for (Path f : hits) {
            String name = f.getFileName().toString().replaceFirst("\\.java$", "");
            String prev = byName.put(name, rel(f));
            if (prev != null) collisions.add(name + ": " + prev + "  ↔  " + rel(f));
        }
        if (!collisions.isEmpty()) {
            fail(id + " baseline 키 충돌 — 단순 클래스명이 겹쳐 한쪽 위반이 가려진다."
                    + " 클래스명을 구분하거나 이 규칙을 FQN 키로 승격하라:\n    " + String.join("\n    ", collisions));
        }
        return byName;
    }

    private static List<Path> scan(List<Path> files, Predicate<String> hit) {
        List<Path> out = new ArrayList<>();
        for (Path f : files) {
            try {
                if (hit.test(Files.readString(f))) out.add(f);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return out;
    }

    private static List<Path> filesUnder(String rel, String ext) throws IOException {
        Path dir = moduleRoot().resolve(rel);
        assertTrue(Files.isDirectory(dir), dir + " 없음");
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(p -> p.toString().endsWith(ext)).collect(toList());
        }
    }

    private static String rel(Path f) {
        return moduleRoot().relativize(f).toString().replace('\\', '/');
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
