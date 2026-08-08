package github.lms.lemuel.insurance.application.port.in;

import github.lms.lemuel.insurance.domain.BancaRuleEvaluator.BancaRuleViolation;

import java.time.Year;
import java.util.List;

/**
 * 방카슈랑스 25%룰 모니터링 배치 유스케이스.
 *
 * <p>당해연도 방카 신계약(effective_date 기준)을 은행 × 부문(생보/손보) × 원수사로 집계해
 * 특정 원수사 비중이 부문 내 상한(25%)을 초과한 은행을 찾아낸다. 자산총액 2조 미만으로
 * 등록된 은행은 적용 면제, 미등록 은행은 적용 대상(fail-closed)이다.
 * 위반 1건당 {@code lemuel.insurance.banca_rule_violated} 를 발행한다 — 운영 관제·
 * 컴플라이언스가 소비해 판매 중지 등 조치를 트리거할 수 있다.
 */
public interface MonitorBancaConcentrationUseCase {

    BancaMonitorResult checkYear(Year year);

    /**
     * @param aggregatedPairs 집계된 (은행, 부문, 원수사) 조합 수
     * @param exemptBankCount 자산 2조 미만으로 면제된 은행 수 — 필터가 실제로 돌았다는 증빙
     * @param violations      상한 초과 위반 목록
     */
    record BancaMonitorResult(int aggregatedPairs, int exemptBankCount,
                              List<BancaRuleViolation> violations) {
    }
}
