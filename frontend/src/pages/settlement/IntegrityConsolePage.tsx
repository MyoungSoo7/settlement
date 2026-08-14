import React, { useCallback, useEffect, useState } from 'react';
import { integrityApi, type IntegrityVerdict } from '@/api/integrity';
import { formatDecimal } from '@/lib/decimal';
import { apiErrorMessage } from '@/lib/apiError';
import Spinner from '@/components/Spinner';

/**
 * 정합성 검증 콘솔 — settlement-service `/admin/integrity/**` 8종을 한 화면에서 순회한다.
 *
 * <p>여태 이 API 들은 MCP 도구(settlement-copilot)와 curl 로만 볼 수 있었다. 온콜이 "지금 어디가
 * 아픈지" 판단하려면 8번의 호출과 눈대중 대조가 필요했는데, 그 순회를 화면이 대신한다.
 *
 * <p><b>판정은 서버 것을 그대로 쓴다</b>: 각 응답의 `ok`/`reasons` 가 정본이고, 화면은 숫자를
 * 다시 비교해 자체 판정을 만들지 않는다. 판정 로직이 두 곳에 생기면 어긋나는 순간 어느 쪽이
 * 맞는지 알 수 없게 되고, 돈이 걸린 화면에서 그건 최악이다.
 */

type CheckKey =
  | 'ledgerCompleteness' | 'payoutRecon' | 'payoutBounceRecon' | 'holdbackStatus'
  | 'stuck' | 'refundAdjustments' | 'projectionDiff' | 'processedCount';

interface CheckState<T = unknown> {
  loading: boolean;
  data: T | null;
  error: string | null;
}

const EMPTY: CheckState = { loading: false, data: null, error: null };

/**
 * 기본 조회 축은 "어제" 다 — 당일 데이터는 컨슈머·배치가 아직 처리 중일 수 있어
 * 정상인데도 위반으로 보이는 오탐이 난다(서버 grace 와 같은 이유).
 */
const daysAgo = (n: number) => new Date(Date.now() - n * 86_400_000).toISOString().slice(0, 10);

const money = (v: string | null | undefined) => {
  const formatted = formatDecimal(v);
  return formatted === null ? '-' : `${formatted}원`;
};

const dateTime = (s: string | null | undefined) =>
  s ? new Date(s).toLocaleString('ko-KR', { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' }) : '-';

/** 판정 배지 — 조회 전/조회 중/정상/위반 네 가지만 구분한다. */
const Verdict: React.FC<{ state: CheckState<IntegrityVerdict | unknown> }> = ({ state }) => {
  if (state.loading) return <span className="text-xs px-2 py-0.5 rounded-full bg-gray-100 text-gray-500">확인 중</span>;
  if (state.error) return <span className="text-xs px-2 py-0.5 rounded-full bg-orange-100 text-orange-700">조회 실패</span>;
  if (!state.data) return <span className="text-xs px-2 py-0.5 rounded-full bg-gray-100 text-gray-400">조회 전</span>;

  const verdict = state.data as Partial<IntegrityVerdict>;
  if (verdict.ok === undefined) {
    return <span className="text-xs px-2 py-0.5 rounded-full bg-blue-100 text-blue-700">원자료</span>;
  }
  return verdict.ok
    ? <span className="text-xs px-2 py-0.5 rounded-full bg-green-100 text-green-800 font-semibold">정상</span>
    : <span className="text-xs px-2 py-0.5 rounded-full bg-red-100 text-red-800 font-semibold">위반</span>;
};

const Metric: React.FC<{ label: string; value: React.ReactNode; warn?: boolean }> = ({ label, value, warn }) => (
  <div>
    <dt className="text-[11px] text-gray-500">{label}</dt>
    <dd className={`text-sm font-semibold ${warn ? 'text-red-700' : 'text-gray-900'}`}>{value}</dd>
  </div>
);

/** 드릴다운 id 목록 — 서버가 상한 절단해 내려주므로 "외 N건" 표기는 하지 않는다. */
const IdList: React.FC<{ label: string; ids: number[] }> = ({ label, ids }) =>
  ids.length === 0 ? null : (
    <div className="mt-2">
      <p className="text-[11px] text-gray-500 mb-1">{label} ({ids.length})</p>
      <p className="font-mono text-xs text-red-700 break-all">{ids.join(', ')}</p>
    </div>
  );

const Card: React.FC<{
  title: string;
  inv: string;
  hint: string;
  state: CheckState;
  onRun: () => void;
  children?: React.ReactNode;
}> = ({ title, inv, hint, state, onRun, children }) => {
  const reasons = (state.data as Partial<IntegrityVerdict> | null)?.reasons ?? [];
  return (
    <section className="bg-white rounded-xl border border-gray-200 p-4 flex flex-col">
      <header className="flex items-start justify-between gap-2 mb-1">
        <div className="min-w-0">
          <h3 className="font-bold text-gray-900 text-sm flex items-center gap-2">
            <span className="text-[10px] font-mono text-gray-400 shrink-0">{inv}</span>
            {title}
          </h3>
          <p className="text-[11px] text-gray-500 mt-0.5">{hint}</p>
        </div>
        <div className="flex items-center gap-2 shrink-0">
          <Verdict state={state} />
          <button
            onClick={onRun}
            disabled={state.loading}
            className="text-xs px-2 py-1 rounded border border-gray-200 text-gray-600 hover:bg-gray-50 disabled:opacity-40"
          >
            조회
          </button>
        </div>
      </header>

      {state.error && <p className="text-xs text-orange-700 mt-2">{state.error}</p>}

      {reasons.length > 0 && (
        <ul className="mt-2 space-y-1">
          {reasons.map((reason) => (
            <li key={reason} className="text-xs text-red-700 bg-red-50 border border-red-100 rounded px-2 py-1">
              {reason}
            </li>
          ))}
        </ul>
      )}

      {state.data != null && <div className="mt-3">{children}</div>}
    </section>
  );
};

const IntegrityConsolePage: React.FC = () => {
  const [date, setDate] = useState(daysAgo(1));
  const [graceMinutes, setGraceMinutes] = useState(30);
  const [thresholdMinutes, setThresholdMinutes] = useState(30);
  const [from, setFrom] = useState(daysAgo(7));
  const [to, setTo] = useState(daysAgo(1));

  const [checks, setChecks] = useState<Record<CheckKey, CheckState>>({
    ledgerCompleteness: EMPTY, payoutRecon: EMPTY, payoutBounceRecon: EMPTY, holdbackStatus: EMPTY,
    stuck: EMPTY, refundAdjustments: EMPTY, projectionDiff: EMPTY, processedCount: EMPTY,
  });

  const run = useCallback(async <T,>(key: CheckKey, call: () => Promise<T>) => {
    setChecks((prev) => ({ ...prev, [key]: { loading: true, data: prev[key].data, error: null } }));
    try {
      const data = await call();
      setChecks((prev) => ({ ...prev, [key]: { loading: false, data, error: null } }));
    } catch (err) {
      setChecks((prev) => ({
        ...prev,
        [key]: { loading: false, data: null, error: apiErrorMessage(err, '조회에 실패했습니다.') },
      }));
    }
  }, []);

  const runners: Record<CheckKey, () => Promise<void>> = {
    ledgerCompleteness: () => run('ledgerCompleteness', () => integrityApi.ledgerCompleteness(date, graceMinutes)),
    payoutRecon: () => run('payoutRecon', () => integrityApi.payoutRecon(date)),
    payoutBounceRecon: () => run('payoutBounceRecon', () => integrityApi.payoutBounceRecon()),
    holdbackStatus: () => run('holdbackStatus', () => integrityApi.holdbackStatus()),
    stuck: () => run('stuck', () => integrityApi.stuck(thresholdMinutes)),
    refundAdjustments: () => run('refundAdjustments', () => integrityApi.refundAdjustments(from, to)),
    projectionDiff: () => run('projectionDiff', () => integrityApi.projectionDiff(date)),
    processedCount: () => run('processedCount', () => integrityApi.processedCount(from, to)),
  };

  /**
   * 전체 순회 — 하나가 실패해도 나머지 판정은 봐야 하므로 개별 실패를 잡아 카드에만 남긴다
   * (run 이 이미 예외를 흡수하므로 all 이 거부되지 않는다).
   */
  const runAll = useCallback(async () => {
    await Promise.all(Object.values(runners).map((fn) => fn()));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [date, graceMinutes, thresholdMinutes, from, to]);

  useEffect(() => { void runAll(); }, []); // eslint-disable-line react-hooks/exhaustive-deps

  const anyLoading = Object.values(checks).some((c) => c.loading);
  const verdicts = Object.values(checks)
    .map((c) => (c.data as Partial<IntegrityVerdict> | null)?.ok)
    .filter((ok): ok is boolean => ok !== undefined);
  const violations = verdicts.filter((ok) => !ok).length;

  const ledger = checks.ledgerCompleteness.data as import('@/api/integrity').LedgerCompletenessReport | null;
  const payout = checks.payoutRecon.data as import('@/api/integrity').PayoutReconReport | null;
  const bounce = checks.payoutBounceRecon.data as import('@/api/integrity').PayoutBounceReconReport | null;
  const holdback = checks.holdbackStatus.data as import('@/api/integrity').HoldbackStatusReport | null;
  const stuck = checks.stuck.data as import('@/api/integrity').StuckStateReport | null;
  const refund = checks.refundAdjustments.data as import('@/api/integrity').RefundAdjustmentReport | null;
  const projection = checks.projectionDiff.data as import('@/api/integrity').ProjectionDiffReport | null;
  const events = checks.processedCount.data as import('@/api/integrity').ProcessedEventCount[] | null;

  return (
    <div>
      <div className="flex flex-wrap items-center justify-between gap-3 mb-6">
        <div>
          <h2 className="text-xl font-bold text-gray-900">정합성 검증</h2>
          <p className="text-sm text-gray-500 mt-0.5">
            원장·지급·홀드백·체류·환불조정·프로젝션 8종 점검. 판정은 서버(`ok`/`reasons`)가 내린다.
          </p>
        </div>
        <div className="flex items-center gap-2">
          {verdicts.length > 0 && (
            <span className={`text-sm font-semibold ${violations > 0 ? 'text-red-700' : 'text-green-700'}`}>
              {violations > 0 ? `위반 ${violations}건` : `정상 ${verdicts.length}종`}
            </span>
          )}
          <button
            onClick={() => void runAll()}
            disabled={anyLoading}
            className="px-4 py-2 rounded-lg bg-gray-900 text-white text-sm font-semibold hover:bg-gray-800 disabled:opacity-40"
          >
            {anyLoading ? '점검 중…' : '전체 점검'}
          </button>
        </div>
      </div>

      {/* 조회 조건 — 점검마다 쓰는 축이 달라 한 줄에 모아 둔다 */}
      <div className="bg-white rounded-xl border border-gray-200 p-4 mb-6 grid grid-cols-2 md:grid-cols-5 gap-3">
        <label className="block">
          <span className="text-[11px] text-gray-500">기준일</span>
          <input type="date" value={date} onChange={(e) => setDate(e.target.value)} className="input" />
        </label>
        <label className="block">
          <span className="text-[11px] text-gray-500">grace(분)</span>
          <input type="number" min={0} value={graceMinutes}
            onChange={(e) => setGraceMinutes(Number(e.target.value))} className="input" />
        </label>
        <label className="block">
          <span className="text-[11px] text-gray-500">체류 임계(분)</span>
          <input type="number" min={1} value={thresholdMinutes}
            onChange={(e) => setThresholdMinutes(Number(e.target.value))} className="input" />
        </label>
        <label className="block">
          <span className="text-[11px] text-gray-500">기간 시작</span>
          <input type="date" value={from} onChange={(e) => setFrom(e.target.value)} className="input" />
        </label>
        <label className="block">
          <span className="text-[11px] text-gray-500">기간 종료</span>
          <input type="date" value={to} onChange={(e) => setTo(e.target.value)} className="input" />
        </label>
      </div>

      {anyLoading && checks.ledgerCompleteness.data === null && (
        <div className="flex justify-center py-6"><Spinner /></div>
      )}

      <div className="grid grid-cols-1 xl:grid-cols-2 gap-4">
        <Card title="원장 완전성" inv="INV-5" state={checks.ledgerCompleteness} onRun={runners.ledgerCompleteness}
          hint="확정 정산·환불 조정에 대응하는 분개가 실제로 있는가 (시산표가 못 잡는 통짜 누락)">
          {ledger && (
            <>
              <dl className="grid grid-cols-2 sm:grid-cols-4 gap-3">
                <Metric label="확정 정산" value={`${ledger.confirmedSettlements}건`} />
                <Metric label="분개 row" value={`${ledger.ledgerEntryRows}건`} />
                <Metric label="기대 총액" value={money(ledger.confirmedPaymentTotal)} />
                <Metric label="분개 총액" value={money(ledger.ledgerPostedTotal)}
                  warn={ledger.confirmedPaymentTotal !== ledger.ledgerPostedTotal} />
                <Metric label="grace 내 대기" value={`${ledger.pendingWithinGrace}건`} />
                <Metric label="Outbox 대기" value={`${ledger.ledgerOutboxPending}건`} />
                <Metric label="Outbox 실패" value={`${ledger.ledgerOutboxFailed}건`}
                  warn={ledger.ledgerOutboxFailed > 0} />
                <Metric label="최고령 대기" value={`${ledger.ledgerOutboxOldestPendingAgeSec}초`} />
              </dl>
              <IdList label="분개 누락 정산" ids={ledger.missingSettlementIds} />
              <IdList label="금액 불일치 정산" ids={ledger.amountMismatchedSettlementIds} />
              <IdList label="역분개 누락 조정" ids={ledger.missingReverseAdjustmentIds} />
            </>
          )}
        </Card>

        <Card title="지급 대사" inv="INV-6" state={checks.payoutRecon} onRun={runners.payoutRecon}
          hint="확정 정산 net ↔ payout 금액·중복 (과다 지급·이중 payout 이 위반)">
          {payout && (
            <>
              <dl className="grid grid-cols-2 sm:grid-cols-4 gap-3">
                <Metric label="확정 정산" value={`${payout.confirmedSettlements}건`} />
                <Metric label="net 합계" value={money(payout.confirmedNetTotal)} />
                <Metric label="활성 payout" value={`${payout.activePayouts}건`} />
                <Metric label="payout 합계" value={money(payout.activePayoutTotal)} />
                <Metric label="완료 payout" value={`${payout.completedPayouts}건`} />
                <Metric label="과다 지급" value={`${payout.overpaidPayouts.length}건`}
                  warn={payout.overpaidPayouts.length > 0} />
                <Metric label="합계 초과 정산" value={`${payout.overTotalSettlements.length}건`}
                  warn={payout.overTotalSettlements.length > 0} />
                <Metric label="payout 미생성" value={`${payout.settlementsWithoutPayout.length}건`} />
              </dl>
              <IdList label="중복 payout 정산" ids={payout.duplicatePayoutSettlementIds} />
              {payout.overpaidPayouts.length > 0 && (
                <div className="mt-2 text-xs text-red-700">
                  {payout.overpaidPayouts.map((o) => (
                    <p key={o.payoutId} className="font-mono">
                      payout #{o.payoutId} / 정산 #{o.settlementId}: {money(o.payoutAmount)} &gt; {money(o.netAmount)}
                    </p>
                  ))}
                </div>
              )}
            </>
          )}
        </Card>

        <Card title="반송 재지급 대사" inv="INV-13" state={checks.payoutBounceRecon} onRun={runners.payoutBounceRecon}
          hint="반송 payout ↔ 재지급 payout 1:1 (금액 불일치·이중지급 가드 우회가 위반)">
          {bounce && (
            <>
              <dl className="grid grid-cols-2 sm:grid-cols-4 gap-3">
                <Metric label="총 반송" value={`${bounce.totalBounces}건`} />
                <Metric label="재지급 완료" value={`${bounce.resolvedBounces}건`} />
                <Metric label="미해결" value={`${bounce.unresolvedBounces}건`} />
                <Metric label="금액 불일치" value={`${bounce.amountMismatches.length}건`}
                  warn={bounce.amountMismatches.length > 0} />
              </dl>
              <IdList label="가드 우회 재지급 payout" ids={bounce.reissuedWithSettlement} />
              <IdList label="고아 수동 payout" ids={bounce.orphanNullSettlementPayoutIds} />
            </>
          )}
        </Card>

        <Card title="홀드백 해제" inv="INV-7" state={checks.holdbackStatus} onRun={runners.holdbackStatus}
          hint="해제 기한이 지났는데 안 풀린 보류금 (해제 스케줄러 침묵 정지 감지)">
          {holdback && (
            <>
              <dl className="grid grid-cols-2 sm:grid-cols-4 gap-3">
                <Metric label="기한 경과" value={`${holdback.overdueCount}건`} warn={holdback.overdueCount > 0} />
                <Metric label="경과 금액" value={money(holdback.overdueAmountTotal)}
                  warn={holdback.overdueCount > 0} />
                <Metric label="미해제 총액" value={money(holdback.totalHeld)} />
                <Metric label="해제 완료" value={money(holdback.totalReleased)} />
                <Metric label="마지막 해제" value={dateTime(holdback.lastReleasedAt)} />
              </dl>
              <IdList label="기한 경과 정산" ids={holdback.overdueSettlementIds} />
            </>
          )}
        </Card>

        <Card title="상태 체류" inv="INV-11" state={checks.stuck} onRun={runners.stuck}
          hint="SENDING payout 이 1순위 — 재시도 전 펌뱅킹 거래 조회 필수(이중지급 위험)">
          {stuck && (
            <>
              <dl className="grid grid-cols-2 sm:grid-cols-4 gap-3">
                <Metric label="SENDING payout" value={`${stuck.stuckSendingPayouts.length}건`}
                  warn={stuck.stuckSendingPayouts.length > 0} />
                <Metric label="PROCESSING 정산" value={`${stuck.stuckSettlements.length}건`} />
                <Metric label="확정 지연" value={`${stuck.overdueConfirmations.length}건`} />
                <Metric label="PG대사 RUNNING" value={`${stuck.stuckPgReconRuns.length}건`} />
                <Metric label="Outbox 체류" value={`${stuck.stuckLedgerOutboxPending}건`} />
                <Metric label="Outbox 실패" value={`${stuck.ledgerOutboxFailed}건`}
                  warn={stuck.ledgerOutboxFailed > 0} />
              </dl>
              {stuck.stuckSendingPayouts.length > 0 && (
                <div className="mt-2 text-xs text-red-700">
                  {stuck.stuckSendingPayouts.map((p) => (
                    <p key={p.payoutId} className="font-mono">
                      payout #{p.payoutId} / 정산 #{p.settlementId} · {money(p.amount)} · {dateTime(p.sentAt)}
                    </p>
                  ))}
                </div>
              )}
            </>
          )}
        </Card>

        <Card title="지연 환불 조정" inv="INV-8" state={checks.refundAdjustments} onRun={runners.refundAdjustments}
          hint="완료된 환불에 정산 조정이 붙었는가 (일일 대사가 못 보는 지연 환불)">
          {refund && (
            <>
              <dl className="grid grid-cols-2 sm:grid-cols-4 gap-3">
                <Metric label="완료 환불" value={`${refund.completedRefunds}건`} />
                <Metric label="환불 총액" value={money(refund.completedRefundTotal)} />
                <Metric label="조정 존재" value={`${refund.adjustedRefunds}건`} />
                <Metric label="조정 누락액" value={money(refund.missingAmountTotal)}
                  warn={refund.missingRefundIds.length > 0} />
              </dl>
              {refund.truncated && (
                <p className="mt-2 text-[11px] text-orange-700">
                  원천 목록이 상한에서 절단됐습니다 — 완전 검사가 아닙니다(기간을 좁혀 재조회).
                </p>
              )}
              <IdList label="조정 누락 환불" ids={refund.missingRefundIds} />
            </>
          )}
        </Card>

        <Card title="프로젝션 diff" inv="INV-12" state={checks.projectionDiff} onRun={runners.projectionDiff}
          hint="order 원천 결제 ↔ settlement 프로젝션 행 집합 (체크섬 1차 → 불일치 시 행 특정)">
          {projection && (
            <>
              <dl className="grid grid-cols-2 sm:grid-cols-4 gap-3">
                <Metric label="체크섬" value={projection.checksumMatched ? '일치' : '불일치'}
                  warn={!projection.checksumMatched} />
                <Metric label="order 건수" value={`${projection.orderCount}건`} />
                <Metric label="프로젝션 건수" value={`${projection.projectionCount}건`}
                  warn={projection.orderCount !== projection.projectionCount} />
                <Metric label="누락 금액" value={money(projection.missingInProjectionAmount)}
                  warn={projection.missingInProjectionCount > 0} />
                <Metric label="누락" value={`${projection.missingInProjectionCount}건`}
                  warn={projection.missingInProjectionCount > 0} />
                <Metric label="고아" value={`${projection.orphanInProjectionCount}건`}
                  warn={projection.orphanInProjectionCount > 0} />
                <Metric label="금액 불일치" value={`${projection.amountMismatchCount}건`}
                  warn={projection.amountMismatchCount > 0} />
                <Metric label="entity" value={projection.entity} />
              </dl>
              {projection.truncated && (
                <p className="mt-2 text-[11px] text-orange-700">키 수집이 절단됐습니다 — 완전 검사가 아닙니다.</p>
              )}
              <IdList label="프로젝션 누락 payment" ids={projection.missingInProjectionIds} />
              <IdList label="고아 payment" ids={projection.orphanInProjectionIds} />
            </>
          )}
        </Card>

        <Card title="이벤트 소비 건수" inv="INV-10" state={checks.processedCount} onRun={runners.processedCount}
          hint="컨슈머 그룹별 처리 건수 — 발행측과의 대조·판정은 여기서 하지 않는다(원자료)">
          {events && (
            events.length === 0
              ? <p className="text-xs text-gray-400">기간 내 소비 기록이 없습니다.</p>
              : (
                <table className="w-full text-xs">
                  <thead className="text-gray-500">
                    <tr>
                      <th className="text-left font-medium pb-1">컨슈머 그룹</th>
                      <th className="text-left font-medium pb-1">이벤트</th>
                      <th className="text-right font-medium pb-1">건수</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-gray-100">
                    {events.map((e) => (
                      <tr key={`${e.consumerGroup}:${e.eventType}`}>
                        <td className="py-1 font-mono">{e.consumerGroup}</td>
                        <td className="py-1 font-mono text-gray-600">{e.eventType}</td>
                        <td className="py-1 text-right font-semibold">{e.count}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )
          )}
        </Card>
      </div>
    </div>
  );
};

export default IntegrityConsolePage;
