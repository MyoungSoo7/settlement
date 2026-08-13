package github.lms.lemuel.deposit.application.port.in;

import github.lms.lemuel.deposit.domain.DepositOffsetShortfall;

import java.math.BigDecimal;
import java.util.List;

/**
 * 상계 부족분(shortfall)의 해소 경로 — 운영자 주도.
 *
 * <p>부족분은 <b>기록만 되고 스스로 사라지지 않는다.</b> 자동 재상계 배치를 두지 않은 것은
 * 의도적이다 — 재상계는 셀러 잔고에서 돈을 다시 가져오는 행위라, 언제·몇 번 시도할지가
 * 기술 기본값이 아니라 정책이다. 대신 운영자가 <b>현재 가용 잔고로 지금 덮을 수 있는지</b>를
 * 보고 승인하는 경로를 연다.
 *
 * <p>{@link #resolveFromAvailable} 는 실제로 잔고를 차감한다 — 상태만 RESOLVED 로 바꾸고
 * 돈은 그대로 두면, 회수했다고 적힌 장부와 실제 잔고가 어긋난다. 덮을 수 없으면 아무것도
 * 바꾸지 않고 거부한다(부분 해소 없음 — 부분은 새 부족분을 낳아 추적을 갈래로 만든다).
 */
public interface ManageShortfallUseCase {

    /** 미해소(OPEN) 부족분 전체 — 운영 콘솔·지표의 입력. */
    List<DepositOffsetShortfall> findOpenShortfalls();

    /**
     * 현재 가용 잔고로 부족분을 덮고 RESOLVED 로 닫는다.
     *
     * @return 실제 차감한 금액(= 부족분 전액)
     * @throws github.lms.lemuel.deposit.domain.exception.InsufficientDepositException 가용액이 부족분에 못 미칠 때
     */
    BigDecimal resolveFromAvailable(Long shortfallId);

    /** 회수를 포기하고 상각한다 — 잔고는 건드리지 않는다(돈이 아니라 판단의 기록). */
    void writeOff(Long shortfallId);
}
