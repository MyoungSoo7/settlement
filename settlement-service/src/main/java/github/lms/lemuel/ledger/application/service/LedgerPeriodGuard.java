package github.lms.lemuel.ledger.application.service;

import github.lms.lemuel.ledger.application.port.out.LoadLedgerPeriodPort;
import github.lms.lemuel.ledger.domain.exception.LedgerPeriodClosedException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.YearMonth;

/**
 * 기간 원장 잠금(period lock) 가드 — 분개 생성 초크포인트가 공유하는 단일 정책 지점.
 *
 * <p>두 규칙(ADR 확정):
 * <ol>
 *   <li><b>신규 원분개</b>({@link #assertOpenForNewEntry}): 대상 {@code settlementDate} 가 CLOSED 기간에
 *       속하면 {@link LedgerPeriodClosedException} 로 거부한다 — 마감된 기간에는 신규 전기 불가.</li>
 *   <li><b>역분개</b>({@link #resolveOpenPostingDate}): 마감 기간을 재개봉하지 않는다. 요청 일자가 CLOSED
 *       기간이면 <b>다음 OPEN 기간의 1일</b>로 재지정해 반환한다(경고 로그). 요청 일자가 OPEN 이면 그대로 반환.</li>
 * </ol>
 *
 * <p>기간 행이 없으면 암묵적 OPEN 이므로 마감 이력이 없는 시스템에서는 두 메서드 모두 무해하게 통과한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LedgerPeriodGuard {

    private final LoadLedgerPeriodPort loadPeriodPort;

    /**
     * 신규 원분개 전기 가드 — 대상 일자가 마감 기간에 속하면 거부한다.
     *
     * @return 검증을 통과한 전기 일자(변경 없이 그대로)
     * @throws LedgerPeriodClosedException 대상 월이 CLOSED 인 경우
     */
    public LocalDate assertOpenForNewEntry(LocalDate settlementDate) {
        if (settlementDate == null) {
            throw new IllegalArgumentException("settlementDate 필수");
        }
        YearMonth ym = YearMonth.from(settlementDate);
        if (loadPeriodPort.isClosed(ym)) {
            throw new LedgerPeriodClosedException(ym, settlementDate);
        }
        return settlementDate;
    }

    /**
     * 역분개 전기 일자 해소 — 마감 기간이면 다음 OPEN 기간(1일)으로 재지정한다.
     *
     * @return 요청 일자가 OPEN 이면 그대로, CLOSED 면 다음 OPEN 월의 1일
     */
    public LocalDate resolveOpenPostingDate(LocalDate requestedDate) {
        if (requestedDate == null) {
            throw new IllegalArgumentException("requestedDate 필수");
        }
        YearMonth ym = YearMonth.from(requestedDate);
        if (!loadPeriodPort.isClosed(ym)) {
            return requestedDate;
        }
        YearMonth cursor = ym.plusMonths(1);
        while (loadPeriodPort.isClosed(cursor)) {
            cursor = cursor.plusMonths(1);
        }
        LocalDate retargeted = cursor.atDay(1);
        log.warn("역분개 전기 일자 {} 가 마감 기간 {} 에 속함 → 재개봉 금지, 다음 OPEN 기간 {}({}) 로 재지정",
                requestedDate, ym, cursor, retargeted);
        return retargeted;
    }
}
