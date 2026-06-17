package github.lms.lemuel.settlement;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR 0020 Phase 5.5 — opslab 직독 경계 가드.
 *
 * <p>settlement-service 의 모든 production 소스에서 schema-qualified {@code opslab.} 참조는
 * <b>대사(audit) 도구로만</b> 허용된다. 대사 도구는 {@link github.lms.lemuel.architecture.AuditCrossRead}
 * 로 명시되어야 하며, 그 외(서빙·조회·리포팅) 코드가 order 원천을 직접 읽으면 이 테스트가 실패한다.
 *
 * <p>기존 {@link SettlementProjectionArchitectureTest} 는 {@code @Immutable}/ReadModel 매핑만
 * 잡고 native SQL 의 {@code opslab.} 문자열은 보지 못했다 — 이 테스트가 그 빈틈을 메운다.
 */
class OpslabReferenceGuardTest {

    /** schema-qualified 참조(opslab.테이블). "opslab 원천" 같은 산문은 매칭하지 않는다. */
    private static final Pattern OPSLAB_REF = Pattern.compile("opslab\\.");

    /** 애너테이션 정의 파일 자신은 javadoc 에 예시로 opslab. 를 담으므로 제외. */
    private static final String ANNOTATION_DEFINITION = "AuditCrossRead.java";

    @Test
    void onlyAuditCrossReadClassesMayReferenceOpslab() throws IOException {
        Path mainJava = locateMainJavaRoot();

        try (Stream<Path> paths = Files.walk(mainJava)) {
            List<String> violations = paths
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.getFileName().toString().equals(ANNOTATION_DEFINITION))
                    .filter(OpslabReferenceGuardTest::referencesOpslab)
                    .filter(p -> !isAuditCrossRead(p))
                    .map(Path::toString)
                    .toList();

            assertThat(violations)
                    .as("서빙/조회/리포팅 코드가 opslab.* 를 직접 읽고 있습니다. "
                            + "로컬 프로젝션(settlement_*_view)으로 전환하거나, 대사 도구라면 "
                            + "@AuditCrossRead 로 의도를 명시하세요 (ADR 0020 Phase 5.5).")
                    .isEmpty();
        }
    }

    private static boolean referencesOpslab(Path javaFile) {
        return OPSLAB_REF.matcher(read(javaFile)).find();
    }

    private static boolean isAuditCrossRead(Path javaFile) {
        return read(javaFile).contains("@AuditCrossRead");
    }

    private static String read(Path javaFile) {
        try {
            return Files.readString(javaFile);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Gradle 테스트(모듈 디렉터리)·IDE(루트) 양쪽에서 src/main/java 를 찾는다. */
    private static Path locateMainJavaRoot() {
        for (String candidate : new String[]{"src/main/java", "settlement-service/src/main/java"}) {
            Path p = Path.of(candidate);
            if (Files.isDirectory(p)) {
                return p;
            }
        }
        throw new IllegalStateException("settlement-service src/main/java 를 찾지 못했습니다 (cwd="
                + Path.of("").toAbsolutePath() + ")");
    }
}
