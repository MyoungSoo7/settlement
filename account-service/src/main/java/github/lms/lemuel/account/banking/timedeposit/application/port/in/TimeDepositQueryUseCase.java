package github.lms.lemuel.account.banking.timedeposit.application.port.in;

import github.lms.lemuel.account.banking.timedeposit.domain.TimeDeposit;

import java.util.List;

/**
 * 정기예금 조회 인바운드 포트 — 본인 계좌만.
 *
 * <p>두 메서드 모두 예금주 식별자를 <b>첫 인자로 강제</b>한다. "전체 조회 후 필터"가 아니라
 * "본인 것만 조회"를 시그니처 수준에서 못 박아, 필터를 빠뜨린 신규 호출자가 남의 계좌를 읽는
 * 경로가 애초에 생기지 않게 한다.
 */
public interface TimeDepositQueryUseCase {

    /** 단건 조회 — 남의 계좌면 {@code TimeDepositAccessDeniedException}(403). */
    TimeDeposit get(String depositorId, Long depositId);

    /** 본인 계좌 전체 (해지분 포함). */
    List<TimeDeposit> listMine(String depositorId);
}
