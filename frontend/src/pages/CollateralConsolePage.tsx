import React, { useState } from 'react';
import {
  securedLoanApi,
  newIdempotencyKey,
  DuplicateEnforcementError,
  type SecuredLoan,
  type RevaluationResult,
  type EnforcementResult,
  type RevaluationSource,
  type CollateralOutcome,
} from '@/api/securedLoan';
import { apiErrorMessage } from '@/lib/apiError';
import { useToast } from '@/contexts/useToast';

/**
 * 담보 감시 콘솔 — 재평가·마진콜 판정과 실행(처분·대위변제).
 *
 * <p><b>왜 화면이 필요한가.</b> 재평가·실행 로직은 서비스·정책·테스트가 다 있었는데 어떤 어댑터도
 * 부르지 않아, <b>담보 가치가 반토막 나도 아무 일이 일어나지 않았다</b>. REST 어댑터가 그 구멍을
 * 메웠지만 부르는 화면이 없어 여전히 사람이 손댈 수 없었다.
 *
 * <p><b>재평가는 조회가 아니다.</b> 새 평가액을 넣는 순간 서버가 판정까지 한다 — 140% 미달이면
 * 마진콜을 걸고, 120% 미달이면 청산 경로로 이관한다. 그래서 "조회해 본다"는 마음으로 누를 수 없게
 * 결과 문구와 버튼 이름이 조작임을 말한다.
 *
 * <p><b>운영자용 목록이 없다.</b> 서버의 목록 조회는 호출자 본인 대출만 준다(운영자도 대상 차주를
 * 지정할 수 없다). 그래서 대출번호를 직접 받는다 — 화면이 지어낼 수 있는 것이 아니다.
 */

const fmt = (v: number) =>
  new Intl.NumberFormat('ko-KR', { style: 'currency', currency: 'KRW' }).format(v);

const pct = (ratio: number) => `${(ratio * 100).toFixed(1)}%`;

const OUTCOME: Record<CollateralOutcome, { label: string; tone: string; note: string }> = {
  SUFFICIENT: {
    label: '충족', tone: 'bg-green-50 text-green-800',
    note: '담보유지비율을 충족합니다. 기존 마진콜이 있었다면 해소됐습니다.',
  },
  MARGIN_CALL: {
    label: '마진콜', tone: 'bg-amber-50 text-amber-900',
    note: '유지비율 140% 미달 — 추가담보를 요구해야 합니다.',
  },
  LIQUIDATION: {
    label: '청산 이관', tone: 'bg-red-50 text-red-800',
    note: '청산선 120% 미달 — 강제처분 경로로 넘어갔습니다(연체·기한이익상실).',
  },
};

const SOURCES: { value: RevaluationSource; label: string }[] = [
  { value: 'MANUAL', label: '수기 (감정가 등)' },
  { value: 'MARKET_SERVICE', label: '시세 서비스' },
  { value: 'COMMON_DATA_SERVICE', label: '공공데이터' },
];

const inputClass = 'mt-1 w-full rounded border px-3 py-2';
const buttonClass = 'rounded px-4 py-2 text-sm font-semibold disabled:opacity-50';

/** 힌트는 {@code <label>} 밖이다 — 안에 넣으면 힌트 문장까지 입력칸의 접근성 이름이 된다. */
const Field: React.FC<{ label: string; hint?: string; children: React.ReactNode }> =
  ({ label, hint, children }) => (
    <div className="text-sm">
      <label className="block">
        <span className="text-gray-600">{label}</span>
        {children}
      </label>
      {hint && <span className="mt-1 block text-xs text-gray-500">{hint}</span>}
    </div>
  );

const CollateralConsolePage: React.FC = () => {
  const { showToast } = useToast();

  const [loanInput, setLoanInput] = useState('');
  /** 조회를 마친 대출. 입력칸이 아니라 <b>이 값</b>으로 조작한다. */
  const [loan, setLoan] = useState<SecuredLoan | null>(null);
  const [looked, setLooked] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const [revaluedValue, setRevaluedValue] = useState('');
  const [source, setSource] = useState<RevaluationSource>('MANUAL');
  const [revaluation, setRevaluation] = useState<RevaluationResult | null>(null);

  const [proceeds, setProceeds] = useState('');
  const [enforcement, setEnforcement] = useState<EnforcementResult | null>(null);

  const parsed = Number(loanInput.trim());
  const validLoanId = loanInput.trim() !== '' && Number.isInteger(parsed) && parsed > 0;

  /** 대출번호를 고치면 조회 결과와 조작 결과를 모두 버린다 — 남은 숫자가 다음 판단을 오염시킨다. */
  const changeLoan = (value: string) => {
    setLoanInput(value);
    setLoan(null);
    setLooked(false);
    setRevaluation(null);
    setEnforcement(null);
    setError(null);
  };

  const lookup = async () => {
    if (!validLoanId) return;
    setBusy(true);
    setError(null);
    try {
      setLoan(await securedLoanApi.detail(parsed));
      setLooked(true);
    } catch (err) {
      setError(apiErrorMessage(err, '대출을 조회하지 못했습니다.'));
    } finally {
      setBusy(false);
    }
  };

  const refresh = async (loanId: number) => {
    try {
      setLoan(await securedLoanApi.detail(loanId));
    } catch {
      // 조작은 성공했다. 갱신 실패로 결과 표시를 지우지 않는다.
    }
  };

  const run = async (label: string, action: () => Promise<void>) => {
    setBusy(true);
    setError(null);
    try {
      await action();
    } catch (err) {
      setError(err instanceof DuplicateEnforcementError
        ? err.message
        : apiErrorMessage(err, `${label} 처리에 실패했습니다.`));
    } finally {
      setBusy(false);
    }
  };

  const collateral = loan?.collateral ?? null;
  const canOperate = loan !== null && collateral !== null;

  const revalue = () => void run('재평가', async () => {
    const result = await securedLoanApi.revalue(loan!.loanId, Number(revaluedValue), source);
    setRevaluation(result);
    setRevaluedValue('');
    await refresh(loan!.loanId);
    showToast(`재평가 판정: ${OUTCOME[result.outcome].label} (${pct(result.coverageRatio)})`,
      result.outcome === 'SUFFICIENT' ? 'success' : 'info');
  });

  const dispose = () => {
    if (!window.confirm(
      `대출 #${loan!.loanId} 의 담보를 처분합니다.\n\n`
      + `매각대금 ${fmt(Number(proceeds))} 으로 채권을 회수하고 부족분은 상각합니다.\n`
      + '전표와 상각이 남으며 되돌리는 경로가 없습니다.\n\n계속하시겠습니까?')) return;
    // 키는 조작 1회당 하나다. 여기서 만들어야 재시도에도 같은 키가 유지된다 —
    // 호출부마다 새로 만들면 서버의 중복 방어가 영영 발동하지 않는다.
    const key = newIdempotencyKey();
    void run('처분', async () => {
      const result = await securedLoanApi.dispose(loan!.loanId, Number(proceeds), key);
      setEnforcement(result);
      setProceeds('');
      await refresh(loan!.loanId);
      showToast(`처분 완료 — 회수 ${fmt(result.recovered)}`, 'success');
    });
  };

  const subrogate = () => {
    if (!window.confirm(
      `대출 #${loan!.loanId} 에 보증기관 대위변제를 청구합니다.\n\n`
      + '회수액은 보증비율(85%)만큼이고 미보증분은 상각됩니다 — 보증부라도 손실이 0 이 아닙니다.\n'
      + '되돌리는 경로가 없습니다.\n\n계속하시겠습니까?')) return;
    const key = newIdempotencyKey();
    void run('대위변제', async () => {
      const result = await securedLoanApi.subrogate(loan!.loanId, key);
      setEnforcement(result);
      await refresh(loan!.loanId);
      showToast(`대위변제 완료 — 회수 ${fmt(result.recovered)}`, 'success');
    });
  };

  return (
    <div className="min-h-screen bg-gray-50 py-8 px-4 sm:px-6 lg:px-8">
      <div className="max-w-4xl mx-auto space-y-4">
        <div>
          <h1 className="text-2xl font-bold text-gray-900">담보 감시</h1>
          <p className="text-sm text-gray-500 mt-1">
            재평가는 판정을 동반하는 <b>조작</b>입니다 — 140% 미달이면 마진콜, 120% 미달이면 청산
            경로로 이관됩니다. 처분·대위변제는 전표와 상각을 남기며 되돌릴 수 없습니다.
          </p>
        </div>

        <section className="bg-white rounded-xl border border-gray-200 p-4 space-y-3"
          data-testid="loan-lookup">
          <div className="flex flex-wrap items-end gap-2">
            <Field label="대출번호"
              hint="운영자용 목록 조회가 없어 번호를 직접 넣습니다(서버 제약)">
              <input value={loanInput} inputMode="numeric"
                onChange={(e) => changeLoan(e.target.value)}
                className="mt-1 block w-40 rounded border px-3 py-2 font-mono" />
            </Field>
            <button type="button" onClick={() => void lookup()} disabled={!validLoanId || busy}
              className={`${buttonClass} border border-gray-300 bg-white text-gray-700`}>
              대출 조회
            </button>
          </div>

          {error && <p role="alert" className="text-sm text-red-600">{error}</p>}

          {looked && loan === null && (
            <p className="text-sm text-gray-600" data-testid="loan-missing">
              대출 #{parsed} 을 찾을 수 없습니다.
            </p>
          )}

          {loan && (
            <dl className="grid gap-3 rounded bg-gray-50 p-3 text-sm sm:grid-cols-4"
              data-testid="loan-summary">
              <div><dt className="text-gray-500">상품</dt><dd>{loan.productType}</dd></div>
              <div><dt className="text-gray-500">상태</dt><dd>{loan.status}</dd></div>
              <div><dt className="text-gray-500">잔액</dt>
                <dd className="font-bold" data-testid="loan-outstanding">{fmt(loan.outstanding)}</dd></div>
              <div><dt className="text-gray-500">담보 평가액</dt>
                <dd className="font-bold" data-testid="collateral-value">
                  {collateral ? fmt(collateral.appraisedValue) : '—'}
                </dd></div>
            </dl>
          )}

          {loan && !collateral && (
            <p role="alert" className="text-sm text-amber-800" data-testid="no-collateral">
              이 대출에는 담보가 없습니다(무담보 상품). 재평가·처분·대위변제가 성립하지 않습니다.
            </p>
          )}
        </section>

        {canOperate && (
          <>
            {/* 재평가 — 판정까지 서버가 한다 */}
            <section className="bg-white rounded-xl border border-gray-200 p-4 space-y-3"
              data-testid="revalue-panel">
              <div>
                <h2 className="font-semibold text-gray-900">재평가 · 마진콜 판정</h2>
                <p className="text-sm text-gray-500 mt-1">
                  새 평가액을 넣으면 서버가 담보유지비율을 다시 판정합니다. 값은 시스템이 스스로
                  알 수 없어(감정가·시세 스냅샷) 사람이 들고 와야 합니다.
                </p>
              </div>
              <div className="grid gap-3 sm:grid-cols-3">
                <Field label="새 평가액">
                  <input value={revaluedValue} inputMode="numeric"
                    onChange={(e) => setRevaluedValue(e.target.value)} className={inputClass} />
                </Field>
                <Field label="평가 출처">
                  <select value={source} className={inputClass}
                    onChange={(e) => setSource(e.target.value as RevaluationSource)}>
                    {SOURCES.map((s) => <option key={s.value} value={s.value}>{s.label}</option>)}
                  </select>
                </Field>
              </div>
              <button type="button" onClick={revalue}
                disabled={busy || revaluedValue.trim() === '' || Number(revaluedValue) <= 0}
                className={`${buttonClass} bg-blue-600 text-white`}>
                재평가하고 판정
              </button>

              {revaluation && (
                <div className={`rounded p-3 text-sm ${OUTCOME[revaluation.outcome].tone}`}
                  data-testid="revalue-result">
                  <p className="font-bold" data-testid="revalue-outcome">
                    {OUTCOME[revaluation.outcome].label} · 담보유지비율 {pct(revaluation.coverageRatio)}
                  </p>
                  <p className="mt-1">{OUTCOME[revaluation.outcome].note}</p>
                  {revaluation.outcome === 'MARGIN_CALL' && revaluation.requiredAmount !== null && (
                    <p className="mt-1" data-testid="required-amount">
                      추가담보 요구액 <b>{fmt(revaluation.requiredAmount)}</b>
                    </p>
                  )}
                </div>
              )}
            </section>

            {/* 실행 — 1회성, 상각을 남긴다 */}
            <section className="bg-white rounded-xl border-2 border-red-300 p-4 space-y-3"
              data-testid="enforce-panel">
              <div>
                <h2 className="font-semibold text-gray-900">실행 (처분 · 대위변제)</h2>
                <p className="text-sm text-gray-500 mt-1">
                  기한이익상실 이후의 회수 경로입니다. 전표와 상각을 남기는 <b>1회성 조작</b>이라
                  되돌릴 수 없습니다.
                </p>
              </div>

              <div className="flex flex-wrap items-end gap-3">
                <Field label="매각대금" hint="담보 처분 시 실제 회수된 금액">
                  <input value={proceeds} inputMode="numeric"
                    onChange={(e) => setProceeds(e.target.value)}
                    className="mt-1 block w-48 rounded border px-3 py-2" />
                </Field>
                <button type="button" onClick={dispose}
                  disabled={busy || proceeds.trim() === '' || Number(proceeds) <= 0}
                  className={`${buttonClass} bg-red-600 text-white`}>
                  담보 처분
                </button>
                <button type="button" onClick={subrogate} disabled={busy}
                  className={`${buttonClass} border border-gray-300 bg-white text-gray-700`}>
                  대위변제 청구
                </button>
              </div>
              <p className="text-xs text-gray-500">
                대위변제는 매각대금을 쓰지 않습니다 — 보증기관이 보증비율만큼 갚고 나머지는 상각됩니다.
              </p>

              {enforcement && (
                <dl className="grid gap-3 rounded bg-gray-50 p-3 text-sm sm:grid-cols-4"
                  data-testid="enforce-result">
                  <div><dt className="text-gray-500">회수</dt>
                    <dd className="font-bold" data-testid="recovered">{fmt(enforcement.recovered)}</dd></div>
                  <div><dt className="text-gray-500">잉여</dt>
                    <dd data-testid="surplus">{fmt(enforcement.surplus)}</dd></div>
                  {/* 상각액을 숨기면 "회수했다"만 남아 손실이 장부에서만 보인다. */}
                  <div><dt className="text-gray-500">상각</dt>
                    <dd className="font-bold text-red-700" data-testid="written-off">
                      {fmt(enforcement.writtenOff)}
                    </dd></div>
                  <div><dt className="text-gray-500">종단 상태</dt>
                    <dd data-testid="final-status">{enforcement.finalStatus}</dd></div>
                </dl>
              )}
            </section>
          </>
        )}
      </div>
    </div>
  );
};

export default CollateralConsolePage;
