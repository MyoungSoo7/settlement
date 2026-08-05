package github.lms.lemuel.settlement.domain.rerun;

import github.lms.lemuel.settlement.domain.exception.InvalidRerunRequestException;

import java.time.LocalDate;
import java.util.List;

/**
 * 정산 배치 재실행 요청 — 운영자 입력의 사전조건을 도메인에서 강제하는 값 객체.
 *
 * <p><b>왜 도메인인가</b>: 재실행 경로는 앞으로 REST 말고도 늘어날 수 있다(스케줄러 수동 트리거,
 * 운영 MCP 도구). 검증을 컨트롤러의 {@code @Valid} 에 두면 경로마다 다시 짜야 하고 한 곳이 비면
 * "1 년 전 일자로 대량 재정산"이 그대로 통과한다. 팩토리를 유일 생성 경로로 두어 어디서 부르든
 * 같은 게이트를 지나게 한다.
 *
 * <p>setter 없음 · final 필드 · private 생성자 — 생성 후 재부여가 컴파일 단에서 불가능하다.
 */
public final class SettlementRerunRequest {

    private final SettlementRerunScope scope;
    private final LocalDate targetDate;

    private SettlementRerunRequest(SettlementRerunScope scope, LocalDate targetDate) {
        this.scope = scope;
        this.targetDate = targetDate;
    }

    /**
     * 재실행 요청을 생성한다 — 사전조건을 모두 통과한 요청만 존재할 수 있다.
     *
     * @param scope           재실행 단계 (필수)
     * @param targetDate      대상 일자 (필수 — 기본값 보정은 호출측 책임)
     * @param today           기준일 (KST {@code Clock} 에서 파생 — 테스트 가능성을 위해 주입)
     * @param maxLookbackDays 허용 소급 일수 (0 이상)
     * @throws InvalidRerunRequestException 미래 일자·과도한 소급·필수값 누락·설정 오류
     */
    public static SettlementRerunRequest of(SettlementRerunScope scope,
                                            LocalDate targetDate,
                                            LocalDate today,
                                            int maxLookbackDays) {
        if (scope == null) {
            throw new InvalidRerunRequestException("재실행 대상(scope)이 지정되지 않았습니다.");
        }
        if (targetDate == null) {
            throw new InvalidRerunRequestException("재실행 대상 일자(targetDate)가 지정되지 않았습니다.");
        }
        if (today == null) {
            throw new InvalidRerunRequestException("기준일(today)이 지정되지 않았습니다.");
        }
        if (maxLookbackDays < 0) {
            throw new InvalidRerunRequestException(
                    "허용 소급 일수 설정이 음수입니다: " + maxLookbackDays);
        }
        if (targetDate.isAfter(today)) {
            throw new InvalidRerunRequestException(
                    "미래 일자는 재실행할 수 없습니다: targetDate=" + targetDate + ", today=" + today);
        }
        LocalDate earliest = today.minusDays(maxLookbackDays);
        if (targetDate.isBefore(earliest)) {
            throw new InvalidRerunRequestException(
                    "허용 소급 범위를 벗어났습니다: targetDate=" + targetDate
                            + ", 최소 허용일=" + earliest + " (최대 " + maxLookbackDays + "일)");
        }
        return new SettlementRerunRequest(scope, targetDate);
    }

    public SettlementRerunScope scope() {
        return scope;
    }

    public LocalDate targetDate() {
        return targetDate;
    }

    /** 실제 실행할 단계 목록 — {@code ALL} 은 재계산 경로로 전개된다. */
    public List<SettlementRerunScope> steps() {
        return scope.expand();
    }

    @Override
    public String toString() {
        return "SettlementRerunRequest{scope=" + scope + ", targetDate=" + targetDate + '}';
    }
}
