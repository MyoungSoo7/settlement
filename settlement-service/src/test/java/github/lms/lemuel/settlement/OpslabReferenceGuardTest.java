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
 * ADR 0020 Phase 5 — settlement 의 order DB(opslab) 직독 완전 제거 가드.
 *
 * <p>settlement-service 의 어떤 production 소스도 schema-qualified {@code opslab.} 를 참조해선 안 된다.
 * 서빙·조회·리포팅은 로컬 프로젝션(settlement_*_view)·자체 테이블로, 대사는 order 내부 API
 * ({@code OrderReconClient})로 처리하므로 settlement 는 order DB 에 직접 연결하지 않는다(cross-DB 0).
 *
 * <p>기존 ArchUnit({@link SettlementProjectionArchitectureTest})은 {@code @Immutable}/ReadModel
 * 매핑만 잡고 native SQL 의 {@code opslab.} 문자열은 보지 못했다 — 이 테스트가 그 빈틈을 메운다.
 */
class OpslabReferenceGuardTest {

    /** schema-qualified 참조(opslab.테이블). "opslab 원천" 같은 산문은 매칭하지 않는다. */
    private static final Pattern OPSLAB_REF = Pattern.compile("opslab\\.");

    @Test
    void noProductionCodeReferencesOpslab() throws IOException {
        Path mainJava = locateMainJavaRoot();

        try (Stream<Path> paths = Files.walk(mainJava)) {
            List<String> violations = paths
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(OpslabReferenceGuardTest::referencesOpslab)
                    .map(Path::toString)
                    .toList();

            assertThat(violations)
                    .as("settlement 은 order DB(opslab)를 직접 읽어선 안 됩니다. 서빙은 로컬 프로젝션으로, "
                            + "대사는 OrderReconClient(order 내부 API)로 전환하세요 (ADR 0020 Phase 5).")
                    .isEmpty();
        }
    }

    private static boolean referencesOpslab(Path javaFile) {
        try {
            return OPSLAB_REF.matcher(Files.readString(javaFile)).find();
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
