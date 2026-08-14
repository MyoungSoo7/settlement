import React, { useCallback, useEffect, useState } from 'react';
import {
  commissionRateApi,
  percentToRate,
  rateToPercent,
  type CommissionRatePolicy,
  type RateScope,
  type SellerTier,
  type RateSimulation,
} from '@/api/commissionRate';
import { apiErrorMessage } from '@/lib/apiError';
import { useToast } from '@/contexts/useToast';
import Spinner from '@/components/Spinner';

/**
 * 수수료율 정책 콘솔 (ADMIN 전용, ADR 0032).
 *
 * <p>이 화면에는 "수정" 이 없다. 요율 변경은 <b>기존 정책을 닫고 새 정책을 등록</b>하는 것이고,
 * 그래서 이 테이블 자체가 이력이 된다. 화면이 수정을 흉내 내면 "그때 왜 그 요율이었나"를
 * 설명할 근거가 사라진다.
 *
 * <p>정책은 <b>미래에만</b> 건다. 이미 정산이 생성된 구간으로 소급하면 서버가 400 으로 막고,
 * 그 경우 정식 경로는 역정산이다 — 오류 문구를 그대로 노출하지 않고 그 사실을 읽어 준다.
 *
 * <p>등록 확인창에 "이미 생성된 정산은 바뀌지 않는다"를 적는다. 정산은 생성 시점 요율을 스냅샷으로
 * 보존하므로, 운영자가 소급 효과를 기대하고 등록하면 기대와 결과가 어긋난다.
 *
 * <p>요율은 퍼센트로 입력받아 소수로 바꿔 보내되 <b>부동소수 나눗셈을 쓰지 않는다</b>
 * (`3.5 / 100` 이 0.034999... 가 되는 것을 피한다 — 금액에 그대로 실린다).
 */

const TIERS: SellerTier[] = ['NORMAL', 'VIP', 'STRATEGIC'];

const CommissionRateConsolePage: React.FC = () => {
  const { showToast } = useToast();

  const [policies, setPolicies] = useState<CommissionRatePolicy[]>([]);
  const [includeClosed, setIncludeClosed] = useState(false);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);

  const [scope, setScope] = useState<RateScope>('SELLER');
  const [scopeKey, setScopeKey] = useState('');
  const [percent, setPercent] = useState('');
  const [effectiveFrom, setEffectiveFrom] = useState('');
  const [effectiveTo, setEffectiveTo] = useState('');
  const [reason, setReason] = useState('');
  const [formError, setFormError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  const [simSellerId, setSimSellerId] = useState('');
  const [simTier, setSimTier] = useState<SellerTier>('NORMAL');
  const [simAt, setSimAt] = useState('');
  const [simulation, setSimulation] = useState<RateSimulation | null>(null);

  const load = useCallback(async (withClosed: boolean) => {
    setLoading(true);
    setLoadError(null);
    try {
      setPolicies(await commissionRateApi.list(withClosed));
    } catch (err) {
      setPolicies([]);
      setLoadError(apiErrorMessage(err, '정책 목록을 불러오지 못했습니다.'));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { void load(includeClosed); }, [includeClosed, load]);

  const handleRegister = async () => {
    setFormError(null);
    const rate = percentToRate(percent);
    if (!scopeKey.trim()) {
      setFormError('적용 대상(셀러 ID 또는 등급)을 입력하세요.');
      return;
    }
    if (rate === null) {
      setFormError('요율을 숫자로 입력하세요. 예: 2.5');
      return;
    }
    // 사유 없는 요율 변경은 감사 근거를 남기지 않는다 — 서버도 필수지만 여기서 먼저 막는다.
    if (!reason.trim()) {
      setFormError('사유는 필수입니다 — 왜 이 요율인지가 남아야 합니다.');
      return;
    }
    if (!effectiveFrom) {
      setFormError('발효일을 지정하세요.');
      return;
    }

    if (!window.confirm(
      `${scope}:${scopeKey.trim()} 에 ${percent}% (${rate}) 정책을 등록합니다.\n`
      + `발효 ${effectiveFrom}${effectiveTo ? ` ~ ${effectiveTo}` : ' 부터 무기한'}\n\n`
      + '이미 생성된 정산은 등록 시점 요율을 그대로 유지합니다(스냅샷 보존). 진행할까요?')) return;

    setSaving(true);
    try {
      await commissionRateApi.register({
        scope, scopeKey: scopeKey.trim(), rate,
        effectiveFrom, effectiveTo: effectiveTo || null, reason: reason.trim(),
      });
      showToast('정책을 등록했습니다.', 'success');
      setScopeKey('');
      setPercent('');
      setReason('');
      await load(includeClosed);
    } catch (err) {
      // 소급 거부는 잘못된 조작이 아니라 "다른 경로를 쓰라"는 안내다.
      const message = apiErrorMessage(err, '정책 등록에 실패했습니다.');
      setFormError(message.includes('역정산') || message.includes('SettlementAdjustment')
        ? `${message}\n이미 정산이 만들어진 구간은 정책이 아니라 역정산으로 정정합니다.`
        : message);
    } finally {
      setSaving(false);
    }
  };

  const handleClose = async (policy: CommissionRatePolicy) => {
    if (!window.confirm(
      `${policy.scope}:${policy.scopeKey} 정책(${rateToPercent(policy.rate)}%)을 종료합니다.\n`
      + '요율을 바꾸려면 종료 후 새 정책을 등록해야 합니다 — 이 행은 이력으로 남습니다. 진행할까요?')) return;
    try {
      await commissionRateApi.close(policy.id);
      showToast('정책을 종료했습니다.', 'success');
      await load(includeClosed);
    } catch (err) {
      showToast(apiErrorMessage(err, '종료에 실패했습니다.'), 'error');
    }
  };

  const handleSimulate = async () => {
    try {
      setSimulation(await commissionRateApi.simulate({
        sellerId: simSellerId.trim() ? Number(simSellerId) : undefined,
        tier: simTier,
        at: simAt || undefined,
      }));
    } catch (err) {
      showToast(apiErrorMessage(err, '요율 확인에 실패했습니다.'), 'error');
    }
  };

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-bold text-gray-900">수수료율</h1>
        <p className="text-sm text-gray-500 mt-1">
          셀러·등급별 수수료율 정책. 변경은 <b>종료 후 신규 등록</b>이며, 정책은 미래 구간에만 겁니다.
          이미 생성된 정산은 그때의 요율을 그대로 유지합니다.
        </p>
      </div>

      <div className="grid grid-cols-1 xl:grid-cols-3 gap-6">
        <div className="xl:col-span-2 space-y-4">
          <div className="bg-white rounded-xl border border-gray-200 overflow-hidden">
            <div className="px-4 py-3 border-b border-gray-100 flex items-center justify-between">
              <h3 className="font-bold text-gray-900">정책 목록</h3>
              <label htmlFor="include-closed" className="flex items-center gap-2 text-sm text-gray-600">
                <input id="include-closed" type="checkbox" checked={includeClosed}
                  onChange={(e) => setIncludeClosed(e.target.checked)} />
                종료된 정책도 보기
              </label>
            </div>

            {loading && <div className="py-12 flex justify-center"><Spinner size="lg" message="정책 로드 중..." /></div>}
            {loadError && <p className="px-4 py-8 text-center text-red-600">{loadError}</p>}

            {!loading && !loadError && policies.length === 0 && (
              <p className="px-4 py-10 text-center text-gray-500">
                등록된 정책이 없습니다 — 모든 셀러가 <b>등급 기본율</b>(NORMAL 3.5% · VIP 2.5% · STRATEGIC 2.0%)로
                정산됩니다.
              </p>
            )}

            {!loading && !loadError && policies.length > 0 && (
              <div className="overflow-x-auto">
                <table className="w-full text-sm">
                  <thead className="bg-gray-50 border-b border-gray-200">
                    <tr>
                      {['ID', '적용 대상', '요율', '기간', '사유', '등록자', ''].map((h) => (
                        <th key={h} className="px-4 py-2.5 text-left text-xs font-semibold text-gray-500 uppercase">{h}</th>
                      ))}
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-gray-100">
                    {policies.map((p) => (
                      <tr key={p.id} className={p.closed ? 'bg-gray-50 text-gray-400' : ''}>
                        <td className="px-4 py-2.5 font-mono text-xs">{p.id}</td>
                        <td className="px-4 py-2.5 font-medium text-gray-800">{`${p.scope}:${p.scopeKey}`}</td>
                        <td className="px-4 py-2.5 font-semibold">{`${rateToPercent(p.rate)}%`}</td>
                        <td className="px-4 py-2.5 text-xs">
                          {p.effectiveFrom} ~ {p.effectiveTo ?? '무기한'}
                        </td>
                        <td className="px-4 py-2.5">{p.reason}</td>
                        <td className="px-4 py-2.5 text-xs text-gray-500">{p.createdBy}</td>
                        <td className="px-4 py-2.5">
                          {p.closed
                            ? <span className="text-xs font-semibold px-2 py-0.5 rounded-full bg-gray-200 text-gray-500">종료됨</span>
                            : (
                              <button onClick={() => handleClose(p)}
                                className="text-xs font-semibold text-red-600 hover:text-red-800">
                                종료
                              </button>
                            )}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </div>

          <div className="bg-white rounded-xl border border-gray-200 p-4 space-y-3">
            <h3 className="font-bold text-gray-900">요율 확인 (미리보기)</h3>
            <p className="text-xs text-gray-500">
              특정 셀러·등급에 어떤 정책이 이기는지 확정 없이 확인합니다. 정산의
              <code className="mx-1">commission_rate_source</code>와 다르면 정산 이후 정책이 바뀐 것이며,
              그 경우 <b>과거 정산이 맞습니다</b>.
            </p>
            <div className="flex flex-wrap items-end gap-3">
              <input placeholder="셀러 ID(선택)" value={simSellerId} inputMode="numeric"
                onChange={(e) => setSimSellerId(e.target.value)} className="input w-40" />
              <div>
                <label htmlFor="sim-tier" className="block text-xs font-medium text-gray-600 mb-1">등급</label>
                <select id="sim-tier" value={simTier} className="input"
                  onChange={(e) => setSimTier(e.target.value as SellerTier)}>
                  {TIERS.map((t) => <option key={t} value={t}>{t}</option>)}
                </select>
              </div>
              <div>
                <label htmlFor="sim-at" className="block text-xs font-medium text-gray-600 mb-1">기준일(선택)</label>
                <input id="sim-at" type="date" value={simAt}
                  onChange={(e) => setSimAt(e.target.value)} className="input" />
              </div>
              <button onClick={handleSimulate}
                className="px-4 py-2 bg-gray-800 text-white text-sm font-semibold rounded-lg hover:bg-gray-900">
                요율 확인
              </button>
            </div>

            {simulation && (
              <div data-testid="simulation-result"
                className="mt-2 flex flex-wrap items-center gap-x-6 gap-y-1 text-sm bg-gray-50 rounded-lg px-3 py-2">
                <span>적용 요율 <b>{`${rateToPercent(simulation.rate)}%`}</b></span>
                <span>근거 <b>{simulation.source}</b></span>
                <span className="text-gray-500">{simulation.tier} · {simulation.at}</span>
              </div>
            )}
          </div>
        </div>

        <div className="bg-white rounded-xl border border-gray-200 p-5 h-fit xl:sticky xl:top-8 space-y-3">
          <h3 className="font-bold text-gray-900">새 정책 등록</h3>
          <p className="text-xs text-amber-700 bg-amber-50 border border-amber-200 rounded-lg px-3 py-2">
            기존 정책을 <b>수정하는 기능은 없습니다</b>. 요율을 바꾸려면 기존 정책을 종료하고 새로 등록하세요 —
            이력이 곧 이 목록입니다.
          </p>

          <div>
            <label htmlFor="scope" className="block text-xs font-medium text-gray-600 mb-1">적용 범위</label>
            <select id="scope" value={scope} className="input"
              onChange={(e) => setScope(e.target.value as RateScope)}>
              <option value="SELLER">SELLER — 개별 계약(등급보다 우선)</option>
              <option value="TIER">TIER — 등급 전체</option>
            </select>
          </div>
          <div>
            <label htmlFor="scope-key" className="block text-xs font-medium text-gray-600 mb-1">
              적용 대상 {scope === 'SELLER' ? '(셀러 ID)' : '(등급명)'}
            </label>
            <input id="scope-key" placeholder="셀러 ID 또는 등급" value={scopeKey}
              onChange={(e) => setScopeKey(e.target.value)} className="input font-mono" />
          </div>
          <div>
            <label htmlFor="rate" className="block text-xs font-medium text-gray-600 mb-1">요율 (%)</label>
            <input id="rate" placeholder="예: 2.5" value={percent} inputMode="decimal"
              onChange={(e) => setPercent(e.target.value)} className="input" />
            <p className="text-xs text-gray-400 mt-1">
              서버에는 소수로 저장됩니다 {percentToRate(percent) ? `→ ${percentToRate(percent)}` : ''}
            </p>
          </div>
          <div className="grid grid-cols-2 gap-3">
            <div>
              <label htmlFor="from" className="block text-xs font-medium text-gray-600 mb-1">발효일</label>
              <input id="from" type="date" value={effectiveFrom}
                onChange={(e) => setEffectiveFrom(e.target.value)} className="input" />
            </div>
            <div>
              <label htmlFor="to" className="block text-xs font-medium text-gray-600 mb-1">종료일(선택)</label>
              <input id="to" type="date" value={effectiveTo}
                onChange={(e) => setEffectiveTo(e.target.value)} className="input" />
            </div>
          </div>
          <div>
            <label htmlFor="reason" className="block text-xs font-medium text-gray-600 mb-1">사유 *</label>
            <input id="reason" placeholder="왜 이 요율인가" value={reason}
              onChange={(e) => setReason(e.target.value)} className="input" />
          </div>

          {formError && (
            <p className="text-sm text-red-700 bg-red-50 border border-red-200 rounded-lg px-3 py-2 whitespace-pre-line">
              {formError}
            </p>
          )}

          <button onClick={handleRegister} disabled={saving}
            className="w-full py-2.5 bg-blue-600 text-white text-sm font-semibold rounded-lg hover:bg-blue-700 disabled:opacity-50">
            {saving ? '등록 중...' : '정책 등록'}
          </button>
        </div>
      </div>
    </div>
  );
};

export default CommissionRateConsolePage;
