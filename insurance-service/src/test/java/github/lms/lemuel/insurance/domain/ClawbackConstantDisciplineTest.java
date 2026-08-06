package github.lms.lemuel.insurance.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D6 상수 규율 가드 — "환수 창구 24개월"의 숫자 24 는
 * {@link CommissionConstants#CLAWBACK_WINDOW_MONTHS} <b>한 곳에서만</b> 선언되어야 하고,
 * 비-테스트 코드(main 소스 트리 전체 — java·sql·yml)에는 매직넘버가 0건이어야 한다.
 *
 * <p>이 테스트가 없으면 "상수를 하나 뒀다"는 주장은 문서 규율에 그친다.
 * 두 번째 선언(예: {@code REINSTATEMENT_WINDOW_MONTHS = 24})이나 주석·메시지의 하드코딩이
 * 슬그머니 들어와도 아무도 막지 못한다. 여기서 기계로 막는다.
 *
 * <p>스캔 대상은 {@code insurance-service/src/main} 전체다. 주석·SQL 코멘트도 예외가 아니다 —
 * 값이 바뀌었을 때 코드와 어긋나 거짓말이 되는 지점이 정확히 거기이기 때문이다.
 */
@DisplayName("D6 상수 규율 (CLAWBACK_WINDOW_MONTHS 단일 선언)")
class ClawbackConstantDisciplineTest {

    /** 앞뒤로 숫자가 붙지 않은 독립 토큰 24 (예: 8124·2024 는 매치되지 않는다). */
    private static final Pattern BARE_24 = Pattern.compile("(?<![0-9])24(?![0-9])");

    /** 유일하게 허용되는 선언 지점. */
    private static final String DECLARATION_FILE = "CommissionConstants.java";
    private static final String DECLARATION_SNIPPET = "CLAWBACK_WINDOW_MONTHS = 24;";

    /** 스캔 결과 한 건 — 파일 상대경로 + 줄번호 + 줄 내용. */
    private record Occurrence(String file, int lineNo, String line) {
        @Override
        public String toString() {
            return file + ":" + lineNo + " → " + line.strip();
        }
    }

    @Test
    @DisplayName("숫자 24 는 main 소스 전체에서 CLAWBACK_WINDOW_MONTHS 선언 1줄에만 등장한다")
    void bare24AppearsOnlyInTheSingleDeclaration() {
        List<Occurrence> occurrences = scanMainSources();

        assertThat(occurrences)
                .as("D6: 24 는 CommissionConstants.CLAWBACK_WINDOW_MONTHS 한 곳에서만 선언한다. "
                        + "아래는 그 밖에서 발견된 매직넘버들이다")
                .hasSize(1);

        Occurrence only = occurrences.get(0);
        assertThat(only.file()).endsWith(DECLARATION_FILE);
        assertThat(only.line()).contains(DECLARATION_SNIPPET);
    }

    @Test
    @DisplayName("환수 계산식의 창구 값은 상수에서만 나온다 — 상수를 바꾸면 계산 결과가 따라 바뀐다")
    void calculatorReadsWindowFromTheConstant() {
        // 상수를 읽지 않고 24 를 하드코딩했다면 이 등식은 상수와 무관하게 고정된다.
        int window = CommissionConstants.CLAWBACK_WINDOW_MONTHS;

        // m = window → 창구 종료(0), m = window-1 → 1/window 만 환수.
        java.time.LocalDate effective = java.time.LocalDate.of(2024, 1, 1);
        java.math.BigDecimal paid = new java.math.BigDecimal("1200.00");

        assertThat(ClawbackCalculator.calculate(
                paid, effective, effective.plusMonths(window), PolicyStatus.SURRENDERED))
                .isEqualByComparingTo(java.math.BigDecimal.ZERO);

        assertThat(ClawbackCalculator.calculate(
                paid, effective, effective.plusMonths(window - 1L), PolicyStatus.SURRENDERED))
                .isEqualByComparingTo(paid.divide(
                        java.math.BigDecimal.valueOf(window),
                        CommissionConstants.AMOUNT_SCALE,
                        java.math.RoundingMode.DOWN));
    }

    @Test
    @DisplayName("부활 창구(D7)도 같은 상수를 참조한다 — 리터럴 재선언 없음")
    void reinstatementWindowSharesTheSameConstant() {
        assertThat(Policy.REINSTATEMENT_WINDOW_MONTHS)
                .isEqualTo(CommissionConstants.CLAWBACK_WINDOW_MONTHS);
    }

    // ────────────────────────────────────────────────────────────────────
    // 스캔
    // ────────────────────────────────────────────────────────────────────

    private List<Occurrence> scanMainSources() {
        Path mainRoot = resolveMainRoot();
        List<Occurrence> found = new ArrayList<>();

        try (Stream<Path> files = Files.walk(mainRoot)) {
            files.filter(Files::isRegularFile).sorted().forEach(file -> {
                List<String> lines;
                try {
                    lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                } catch (IOException ex) {
                    throw new UncheckedIOException("main 소스를 읽을 수 없습니다: " + file, ex);
                }
                for (int i = 0; i < lines.size(); i++) {
                    Matcher m = BARE_24.matcher(lines.get(i));
                    if (m.find()) {
                        found.add(new Occurrence(
                                mainRoot.relativize(file).toString(), i + 1, lines.get(i)));
                    }
                }
            });
        } catch (IOException ex) {
            throw new UncheckedIOException("main 소스 트리를 순회할 수 없습니다: " + mainRoot, ex);
        }
        return found;
    }

    /** 테스트 작업 디렉터리가 모듈 루트든 리포 루트든 동작하도록 해석한다. */
    private Path resolveMainRoot() {
        Path fromModule = Paths.get("src", "main");
        if (Files.isDirectory(fromModule)) {
            return fromModule;
        }
        Path fromRepoRoot = Paths.get("insurance-service", "src", "main");
        if (Files.isDirectory(fromRepoRoot)) {
            return fromRepoRoot;
        }
        throw new IllegalStateException(
                "insurance-service main 소스 트리를 찾지 못했습니다. cwd=" + Paths.get("").toAbsolutePath());
    }
}
