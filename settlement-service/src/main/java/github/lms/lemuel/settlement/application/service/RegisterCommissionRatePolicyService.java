package github.lms.lemuel.settlement.application.service;

import github.lms.lemuel.settlement.application.port.in.RegisterCommissionRatePolicyUseCase;
import github.lms.lemuel.settlement.application.port.out.CountSettlementsInPeriodPort;
import github.lms.lemuel.settlement.application.port.out.SaveCommissionRatePolicyPort;
import github.lms.lemuel.settlement.domain.CommissionRatePolicy;
import github.lms.lemuel.settlement.domain.exception.RetroactiveRatePolicyException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * 요율 정책 등록 (ADR 0032).
 *
 * <p><b>소급을 날짜가 아니라 데이터로 판정한다</b>(결정 ⑤). 발효일이 과거라는 사실만으로는 문제가 아니다 —
 * 계약은 8/1부터인데 등록이 8/7로 늦어지는 일은 정상적으로 일어난다. 문제가 되는 건 그 구간에
 * <b>이미 정산이 생성된</b> 경우다: 생성된 정산은 요율 스냅샷이라 재계산되지 않으므로(ADR 0004·0014),
 * 정책만 바꾸면 장부와 정책이 어긋난 채 남는다.
 *
 * <p>무조건 차단하지 않는 이유는 정상 사례까지 막으면 운영자가 DB 를 직접 만지는 쪽으로 새기 때문이다.
 * 진짜 소급 보정이 필요하면 정식 경로는 {@code SettlementAdjustment}(ADR 0004)다.
 *
 * <p>기간 중첩은 여기서 다투지 않는다 — DB {@code EXCLUDE} 제약이 입력 시점에 막는다.
 */
@Service
@Transactional
public class RegisterCommissionRatePolicyService implements RegisterCommissionRatePolicyUseCase {

    private static final Logger log = LoggerFactory.getLogger(RegisterCommissionRatePolicyService.class);

    private final SaveCommissionRatePolicyPort savePort;
    private final CountSettlementsInPeriodPort countPort;

    public RegisterCommissionRatePolicyService(SaveCommissionRatePolicyPort savePort,
                                               CountSettlementsInPeriodPort countPort) {
        this.savePort = savePort;
        this.countPort = countPort;
    }

    @Override
    public CommissionRatePolicy register(RegisterPolicyCommand command, LocalDate today) {
        rejectIfSettlementsAlreadyExist(command, today);
        CommissionRatePolicy saved = savePort.save(command);
        log.info("요율 정책 등록: scope={}:{}, rate={}, from={}, to={}, by={}",
                command.scope(), command.scopeKey(), command.rate(),
                command.effectiveFrom(), command.effectiveTo(), command.createdBy());
        return saved;
    }

    /** 소급 구간(발효일 ~ 오늘)에 정산이 하나라도 있으면 거부한다. 미래 발효는 조회조차 하지 않는다. */
    private void rejectIfSettlementsAlreadyExist(RegisterPolicyCommand command, LocalDate today) {
        LocalDate from = command.effectiveFrom();
        if (from == null || !from.isBefore(today)) {
            return;   // 오늘·미래 발효 — 소급이 아니다
        }
        long affected = countPort.countInPeriod(command.scope(), command.scopeKey(), from, today);
        if (affected > 0) {
            throw new RetroactiveRatePolicyException(
                    "소급 구간에 이미 생성된 정산이 " + affected + "건 있어 요율 정책을 등록할 수 없습니다"
                            + " (구간 " + from + " ~ " + today + ")."
                            + " 생성된 정산은 요율 스냅샷이라 재계산되지 않으므로 정책만 바꾸면 장부와 어긋납니다."
                            + " 소급 보정이 필요하면 SettlementAdjustment(역정산)를 사용하세요.");
        }
    }
}
