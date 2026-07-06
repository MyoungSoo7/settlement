package github.lms.lemuel.company.application.port.out;

import github.lms.lemuel.company.domain.ReputationScore;

public interface SaveReputationPort {

    /**
     * 스냅샷을 저장한다(INSERT-only). 같은 (종목코드, 일자) 스냅샷이 이미 있으면 저장하지 않고
     * false 를 돌려준다 — 첫 스냅샷이 그날의 불변 기록으로 남는다.
     */
    boolean saveIfAbsent(ReputationScore score);
}
