package github.lms.lemuel.insurance.domain;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 가입설계(ProposalQuote) 상태머신.
 *
 * <p>허용 전이는 아래 2개뿐이다:
 * <ol>
 *   <li>QUOTED → CONVERTED : 청약 전환 — 산출 보험료가 서버 주입으로 청약에 넘어간다 (D-P3)</li>
 *   <li>QUOTED → EXPIRED : 유효기간(30일) 경과 — 만기 스윕 배치가 집행</li>
 * </ol>
 *
 * <p>재산출 전이는 없다 — 조건이 바뀌면 새 설계를 만든다(D-P2: INSERT-only 스냅샷).
 * Terminal 상태(CONVERTED·EXPIRED)에서 나가는 전이는 없다.
 * V8 마이그레이션의 {@code chk_proposal_status} CHECK 제약과 값이 1:1 로 일치한다.
 */
public enum ProposalStatus {

    /** 산출 완료. 전환 또는 만기 대기. */
    QUOTED,

    /** 청약 전환됨 (terminal). converted_application_id 가 함께 기록된다. */
    CONVERTED,

    /** 유효기간 경과 (terminal). */
    EXPIRED;

    private static final Map<ProposalStatus, Set<ProposalStatus>> ALLOWED;

    static {
        ALLOWED = new EnumMap<>(ProposalStatus.class);
        ALLOWED.put(QUOTED, EnumSet.of(CONVERTED, EXPIRED));
        ALLOWED.put(CONVERTED, EnumSet.noneOf(ProposalStatus.class));
        ALLOWED.put(EXPIRED, EnumSet.noneOf(ProposalStatus.class));
    }

    /** 이 상태에서 {@code target} 으로의 전이가 허용되는가. */
    public boolean canTransitionTo(ProposalStatus target) {
        return ALLOWED.get(this).contains(target);
    }

    /** Terminal 상태 여부. Terminal 에서 나가는 전이는 없다. */
    public boolean isTerminal() {
        return this == CONVERTED || this == EXPIRED;
    }
}
