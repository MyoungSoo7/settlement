import React, { useCallback, useEffect, useState } from 'react';
import {
  timeDepositApi, savingsApi, pensionApi,
  PENSION_RULES, BENEFIT_RULES,
  type TimeDeposit, type InstallmentSavings, type RetirementPension,
  type Compounding, type SavingsType, type PensionScheme,
  type BenefitType, type ContributionSource, type MidWithdrawalReason,
} from '@/api/banking';
import { apiErrorMessage } from '@/lib/apiError';
import { useAuth } from '@/contexts/useAuth';
import { useToast } from '@/contexts/useToast';

/**
 * 수신 상품 3종 — 정기예금 · 적금 · 퇴직연금.
 *
 * <p>세 상품 모두 백엔드는 완성돼 있었는데 화면이 없었고, 게다가 {@code /api/banking/**} 가
 * 게이트웨이에 열려 있지 않아 <b>화면을 만들어도 404 였다</b>(2026-08-22 배선).
 *
 * <p><b>화면이 도메인 규칙을 미리 막는다.</b> 서버 판정을 대신하려는 게 아니라 불가능한 입력을
 * 애초에 못 하게 하려는 것이다 — DB 형 퇴직연금은 중도인출이 제도적으로 없고, 납입 주체도
 * 제도마다 다르며(DB=회사, DC=회사+가입자, IRP=가입자), 연금 수령은 가입 10년을 요구한다.
 * 이걸 화면이 모르면 운영자는 400 을 받고서야 규칙을 알게 된다.
 *
 * <p><b>중도해지 이율을 늘 함께 보여 준다.</b> 예금·적금 모두 만기 이율과 중도해지 이율이 다른데,
 * 해지 버튼 옆에 그 숫자가 없으면 "얼마 손해인지 모르고 누르는" 화면이 된다.
 *
 * <p>운용수익 인식·수급 지급 두 조작은 <b>운영자 전용</b>이다(서버가 ADMIN·MANAGER 로 막는다).
 * 가입자에게 열어두면 임의 증액이 되기 때문이라, 화면도 권한이 없으면 그리지 않는다.
 */

const fmt = (v: number | null) =>
  v === null ? '-' : new Intl.NumberFormat('ko-KR', { style: 'currency', currency: 'KRW' }).format(v);

const pct = (v: number | null) => (v === null ? '-' : `${v}%`);

const inputClass = 'mt-1 w-full rounded border px-3 py-2';
const btn = 'rounded px-3 py-1.5 text-sm font-semibold disabled:opacity-50';

const Field: React.FC<{ label: string; hint?: string; children: React.ReactNode }> =
  ({ label, hint, children }) => (
    <div className="text-sm">
      <label className="block"><span className="text-gray-600">{label}</span>{children}</label>
      {hint && <span className="mt-1 block text-xs text-gray-500">{hint}</span>}
    </div>
  );

const Card: React.FC<{ children: React.ReactNode; testId?: string }> = ({ children, testId }) => (
  <div className="rounded border bg-white p-3 space-y-2" data-testid={testId}>{children}</div>
);

type Tab = 'deposit' | 'savings' | 'pension';

// ────────────────────────────────────────────────────────────────────────────
// 정기예금
// ────────────────────────────────────────────────────────────────────────────
const DepositPanel: React.FC = () => {
  const { showToast } = useToast();
  const [rows, setRows] = useState<TimeDeposit[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [form, setForm] = useState({
    productName: '', principal: '', annualRate: '', earlyTerminationRate: '',
    compounding: 'SIMPLE' as Compounding, termMonths: '12',
  });

  const load = useCallback(async () => {
    setError(null);
    try { setRows(await timeDepositApi.listMine()); }
    catch (err) { setRows(null); setError(apiErrorMessage(err, '예금 목록을 불러오지 못했습니다.')); }
  }, []);
  useEffect(() => { void load(); }, [load]);

  const run = async (label: string, action: () => Promise<void>) => {
    setBusy(true); setError(null);
    try { await action(); await load(); }
    catch (err) { setError(apiErrorMessage(err, `${label} 처리에 실패했습니다.`)); }
    finally { setBusy(false); }
  };

  const ready = form.productName.trim() !== '' && Number(form.principal) > 0
    && Number(form.annualRate) > 0 && Number(form.termMonths) > 0;

  const open = () => void run('가입', async () => {
    await timeDepositApi.open({
      productName: form.productName.trim(), principal: Number(form.principal),
      annualRate: Number(form.annualRate), earlyTerminationRate: Number(form.earlyTerminationRate || 0),
      compounding: form.compounding, termMonths: Number(form.termMonths),
    });
    setForm({ ...form, productName: '', principal: '' });
    showToast('예금에 가입했습니다.', 'success');
  });

  const closeEarly = (d: TimeDeposit) => {
    // 중도해지는 이율이 달라진다 — 얼마 손해인지 모르고 누르지 않게 숫자를 확인 문구에 넣는다.
    if (!window.confirm(
      `${d.productName} 을 중도해지합니다.\n\n`
      + `약정 이율 ${pct(d.annualRate)} 대신 중도해지 이율 ${pct(d.earlyTerminationRate)} 가 적용됩니다.\n`
      + `만기는 ${d.maturityDate} 입니다.\n\n계속하시겠습니까?`)) return;
    void run('중도해지', async () => {
      await timeDepositApi.closeEarly(d.id);
      showToast('중도해지했습니다.', 'success');
    });
  };

  return (
    <div className="space-y-4">
      <Card testId="deposit-open">
        <h3 className="text-sm font-semibold">예금 가입</h3>
        <div className="grid gap-3 sm:grid-cols-3">
          <Field label="상품명">
            <input value={form.productName} className={inputClass}
              onChange={(e) => setForm({ ...form, productName: e.target.value })} />
          </Field>
          <Field label="원금">
            <input value={form.principal} inputMode="numeric" className={inputClass}
              onChange={(e) => setForm({ ...form, principal: e.target.value })} />
          </Field>
          <Field label="약정 이율(%)">
            <input value={form.annualRate} inputMode="decimal" className={inputClass}
              onChange={(e) => setForm({ ...form, annualRate: e.target.value })} />
          </Field>
          <Field label="중도해지 이율(%)" hint="약정 이율보다 낮게 정합니다">
            <input value={form.earlyTerminationRate} inputMode="decimal" className={inputClass}
              onChange={(e) => setForm({ ...form, earlyTerminationRate: e.target.value })} />
          </Field>
          <Field label="이자 방식">
            <select value={form.compounding} className={inputClass}
              onChange={(e) => setForm({ ...form, compounding: e.target.value as Compounding })}>
              <option value="SIMPLE">단리</option>
              <option value="MONTHLY_COMPOUND">월복리</option>
            </select>
          </Field>
          <Field label="기간(개월)">
            <input value={form.termMonths} inputMode="numeric" className={inputClass}
              onChange={(e) => setForm({ ...form, termMonths: e.target.value })} />
          </Field>
        </div>
        <button type="button" onClick={open} disabled={!ready || busy}
          className={`${btn} bg-blue-600 text-white`}>가입</button>
      </Card>

      {error && <p role="alert" className="text-sm text-red-600">{error}</p>}

      {rows === null ? (!error && <p className="text-sm text-gray-500">불러오는 중…</p>)
        : rows.length === 0 ? <p className="text-sm text-gray-600" data-testid="deposit-empty">가입한 예금이 없습니다.</p>
          : rows.map((d) => (
            <Card key={d.id} testId={`deposit-${d.id}`}>
              <div className="flex flex-wrap items-baseline justify-between gap-2">
                <b>{d.productName}</b>
                <span className="text-sm text-gray-500">
                  #{d.id} · {d.status === 'ACTIVE' ? '유지 중' : '해지됨'} · 만기 {d.maturityDate}
                </span>
              </div>
              <dl className="grid gap-2 text-sm sm:grid-cols-4">
                <div><dt className="text-gray-500">원금</dt><dd>{fmt(d.principal)}</dd></div>
                <div><dt className="text-gray-500">약정 이율</dt><dd>{pct(d.annualRate)}</dd></div>
                {/* 해지 판단의 핵심 숫자라 목록에서도 감추지 않는다. */}
                <div><dt className="text-gray-500">중도해지 이율</dt>
                  <dd data-testid={`early-rate-${d.id}`}>{pct(d.earlyTerminationRate)}</dd></div>
                <div><dt className="text-gray-500">지급액</dt><dd>{fmt(d.payoutAmount)}</dd></div>
              </dl>
              {d.status === 'ACTIVE' && (
                <div className="flex gap-2">
                  <button type="button" disabled={busy} className={`${btn} bg-gray-800 text-white`}
                    onClick={() => void run('만기해지', async () => {
                      await timeDepositApi.closeOnMaturity(d.id);
                      showToast('만기 해지했습니다.', 'success');
                    })}>만기 해지</button>
                  <button type="button" disabled={busy} onClick={() => closeEarly(d)}
                    className={`${btn} border border-gray-300 bg-white text-gray-700`}>중도 해지</button>
                </div>
              )}
            </Card>
          ))}
    </div>
  );
};

// ────────────────────────────────────────────────────────────────────────────
// 적금
// ────────────────────────────────────────────────────────────────────────────
const SavingsPanel: React.FC = () => {
  const { showToast } = useToast();
  const [rows, setRows] = useState<InstallmentSavings[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [payAmount, setPayAmount] = useState<Record<number, string>>({});
  const [form, setForm] = useState({
    productName: '', savingsType: 'FIXED' as SavingsType, monthlyAmount: '',
    annualRate: '', earlyTerminationRate: '', termMonths: '12',
  });

  const load = useCallback(async () => {
    setError(null);
    try { setRows(await savingsApi.listMine()); }
    catch (err) { setRows(null); setError(apiErrorMessage(err, '적금 목록을 불러오지 못했습니다.')); }
  }, []);
  useEffect(() => { void load(); }, [load]);

  const run = async (label: string, action: () => Promise<void>) => {
    setBusy(true); setError(null);
    try { await action(); await load(); }
    catch (err) { setError(apiErrorMessage(err, `${label} 처리에 실패했습니다.`)); }
    finally { setBusy(false); }
  };

  const ready = form.productName.trim() !== '' && Number(form.monthlyAmount) > 0
    && Number(form.annualRate) > 0 && Number(form.termMonths) > 0;

  /** 다음 납입 회차 = 이미 낸 회차의 최대값 + 1. 같은 회차 재납입은 서버가 막는다. */
  const nextRound = (s: InstallmentSavings) =>
    s.installments.reduce((max, i) => Math.max(max, i.round), 0) + 1;

  return (
    <div className="space-y-4">
      <Card testId="savings-open">
        <h3 className="text-sm font-semibold">적금 가입</h3>
        <div className="grid gap-3 sm:grid-cols-3">
          <Field label="상품명">
            <input value={form.productName} className={inputClass}
              onChange={(e) => setForm({ ...form, productName: e.target.value })} />
          </Field>
          <Field label="유형" hint="정액은 매회 같은 금액, 자유는 한도 안에서 자유롭게">
            <select value={form.savingsType} className={inputClass}
              onChange={(e) => setForm({ ...form, savingsType: e.target.value as SavingsType })}>
              <option value="FIXED">정액적립</option>
              <option value="FLEXIBLE">자유적립</option>
            </select>
          </Field>
          <Field label="월 납입액">
            <input value={form.monthlyAmount} inputMode="numeric" className={inputClass}
              onChange={(e) => setForm({ ...form, monthlyAmount: e.target.value })} />
          </Field>
          <Field label="약정 이율(%)">
            <input value={form.annualRate} inputMode="decimal" className={inputClass}
              onChange={(e) => setForm({ ...form, annualRate: e.target.value })} />
          </Field>
          <Field label="중도해지 이율(%)">
            <input value={form.earlyTerminationRate} inputMode="decimal" className={inputClass}
              onChange={(e) => setForm({ ...form, earlyTerminationRate: e.target.value })} />
          </Field>
          <Field label="기간(개월)">
            <input value={form.termMonths} inputMode="numeric" className={inputClass}
              onChange={(e) => setForm({ ...form, termMonths: e.target.value })} />
          </Field>
        </div>
        <button type="button" disabled={!ready || busy} className={`${btn} bg-blue-600 text-white`}
          onClick={() => void run('가입', async () => {
            await savingsApi.open({
              productName: form.productName.trim(), savingsType: form.savingsType,
              monthlyAmount: Number(form.monthlyAmount), annualRate: Number(form.annualRate),
              earlyTerminationRate: Number(form.earlyTerminationRate || 0),
              termMonths: Number(form.termMonths),
            });
            setForm({ ...form, productName: '', monthlyAmount: '' });
            showToast('적금에 가입했습니다.', 'success');
          })}>가입</button>
      </Card>

      {error && <p role="alert" className="text-sm text-red-600">{error}</p>}

      {rows === null ? (!error && <p className="text-sm text-gray-500">불러오는 중…</p>)
        : rows.length === 0 ? <p className="text-sm text-gray-600" data-testid="savings-empty">가입한 적금이 없습니다.</p>
          : rows.map((s) => (
            <Card key={s.id} testId={`savings-${s.id}`}>
              <div className="flex flex-wrap items-baseline justify-between gap-2">
                <b>{s.productName}</b>
                <span className="text-sm text-gray-500">
                  #{s.id} · {s.savingsType === 'FIXED' ? '정액' : '자유'} ·{' '}
                  {s.status === 'ACTIVE' ? '납입 중' : '해지됨'} · 만기 {s.maturityDate}
                </span>
              </div>
              <dl className="grid gap-2 text-sm sm:grid-cols-4">
                <div><dt className="text-gray-500">월 납입</dt><dd>{fmt(s.monthlyAmount)}</dd></div>
                <div><dt className="text-gray-500">누적 납입</dt>
                  <dd data-testid={`paid-${s.id}`}>{fmt(s.totalPaidAmount)}</dd></div>
                <div><dt className="text-gray-500">중도해지 이율</dt><dd>{pct(s.earlyTerminationRate)}</dd></div>
                <div><dt className="text-gray-500">납입 회차</dt>
                  <dd data-testid={`rounds-${s.id}`}>{s.installments.length}/{s.termMonths}</dd></div>
              </dl>

              {s.status === 'ACTIVE' && (
                <div className="flex flex-wrap items-end gap-2 border-t pt-2">
                  <Field label={`${nextRound(s)}회차 납입액`}>
                    <input inputMode="numeric" className="mt-1 block w-40 rounded border px-3 py-2"
                      aria-label={`적금 ${s.id} 납입액`}
                      value={payAmount[s.id] ?? String(s.monthlyAmount)}
                      onChange={(e) => setPayAmount({ ...payAmount, [s.id]: e.target.value })} />
                  </Field>
                  <button type="button" disabled={busy} className={`${btn} bg-blue-600 text-white`}
                    onClick={() => void run('납입', async () => {
                      await savingsApi.pay(s.id, nextRound(s), Number(payAmount[s.id] ?? s.monthlyAmount));
                      setPayAmount({ ...payAmount, [s.id]: '' });
                      showToast(`${nextRound(s)}회차를 납입했습니다.`, 'success');
                    })}>납입</button>
                  <button type="button" disabled={busy} className={`${btn} bg-gray-800 text-white`}
                    onClick={() => void run('만기해지', async () => {
                      await savingsApi.closeOnMaturity(s.id);
                      showToast('만기 해지했습니다.', 'success');
                    })}>만기 해지</button>
                  <button type="button" disabled={busy}
                    className={`${btn} border border-gray-300 bg-white text-gray-700`}
                    onClick={() => {
                      if (!window.confirm(
                        `${s.productName} 을 중도해지합니다.\n\n`
                        + `약정 이율 ${pct(s.annualRate)} 대신 중도해지 이율 ${pct(s.earlyTerminationRate)} 가 적용됩니다.\n\n`
                        + '계속하시겠습니까?')) return;
                      void run('중도해지', async () => {
                        await savingsApi.closeEarly(s.id);
                        showToast('중도해지했습니다.', 'success');
                      });
                    }}>중도 해지</button>
                </div>
              )}
            </Card>
          ))}
    </div>
  );
};

// ────────────────────────────────────────────────────────────────────────────
// 퇴직연금
// ────────────────────────────────────────────────────────────────────────────
const WITHDRAWAL_REASONS: { value: MidWithdrawalReason; label: string }[] = [
  { value: 'HOMELESS_HOUSE_PURCHASE', label: '무주택자 주택 구입' },
  { value: 'LONG_TERM_CARE_6_MONTHS', label: '6개월 이상 요양' },
  { value: 'BANKRUPTCY', label: '파산 선고' },
  { value: 'PERSONAL_REHABILITATION', label: '개인회생' },
  { value: 'NATURAL_DISASTER', label: '천재지변' },
  { value: 'MINISTER_NOTICE', label: '고용노동부 장관 고시' },
];

const PensionPanel: React.FC<{ isOperator: boolean }> = ({ isOperator }) => {
  const { showToast } = useToast();
  const [rows, setRows] = useState<RetirementPension[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [amounts, setAmounts] = useState<Record<number, string>>({});
  const [sources, setSources] = useState<Record<number, ContributionSource>>({});
  const [form, setForm] = useState({
    scheme: 'DC' as PensionScheme, employerName: '', birthDate: '', annualRate: '',
  });

  const load = useCallback(async () => {
    setError(null);
    try { setRows(await pensionApi.listMine()); }
    catch (err) { setRows(null); setError(apiErrorMessage(err, '퇴직연금 목록을 불러오지 못했습니다.')); }
  }, []);
  useEffect(() => { void load(); }, [load]);

  const run = async (label: string, action: () => Promise<void>) => {
    setBusy(true); setError(null);
    try { await action(); await load(); }
    catch (err) { setError(apiErrorMessage(err, `${label} 처리에 실패했습니다.`)); }
    finally { setBusy(false); }
  };

  const rule = PENSION_RULES[form.scheme];
  const openReady = form.birthDate !== '' && Number(form.annualRate) > 0
    && (!rule.employerNameRequired || form.employerName.trim() !== '');

  return (
    <div className="space-y-4">
      <Card testId="pension-open">
        <h3 className="text-sm font-semibold">퇴직연금 가입</h3>
        <div className="grid gap-3 sm:grid-cols-4">
          <Field label="제도">
            <select value={form.scheme} className={inputClass}
              onChange={(e) => setForm({ ...form, scheme: e.target.value as PensionScheme })}>
              <option value="DB">DB (확정급여)</option>
              <option value="DC">DC (확정기여)</option>
              <option value="IRP">IRP (개인형)</option>
            </select>
          </Field>
          {/* IRP 는 사업장이 없다 — 안 쓰는 칸을 남기면 무엇이 필수인지 흐려진다. */}
          {rule.employerNameRequired && (
            <Field label="사업장명" hint="DB·DC 는 필수입니다">
              <input value={form.employerName} className={inputClass}
                onChange={(e) => setForm({ ...form, employerName: e.target.value })} />
            </Field>
          )}
          <Field label="생년월일" hint="급여 개시 연령(만 55세) 판정에 쓰입니다">
            <input type="date" value={form.birthDate} className={inputClass}
              onChange={(e) => setForm({ ...form, birthDate: e.target.value })} />
          </Field>
          <Field label="적용 이율(%)">
            <input value={form.annualRate} inputMode="decimal" className={inputClass}
              onChange={(e) => setForm({ ...form, annualRate: e.target.value })} />
          </Field>
        </div>
        <p className="text-xs text-gray-500" data-testid="scheme-note">
          {form.scheme} — 납입 주체 {rule.contributionSources.join(', ')} ·{' '}
          중도인출 {rule.midWithdrawalPermitted ? '가능' : '불가(제도상 없음)'}
        </p>
        <button type="button" disabled={!openReady || busy} className={`${btn} bg-blue-600 text-white`}
          onClick={() => void run('가입', async () => {
            await pensionApi.open({
              scheme: form.scheme, birthDate: form.birthDate, annualRate: Number(form.annualRate),
              ...(rule.employerNameRequired ? { employerName: form.employerName.trim() } : {}),
            });
            setForm({ ...form, employerName: '' });
            showToast('퇴직연금에 가입했습니다.', 'success');
          })}>가입</button>
      </Card>

      {error && <p role="alert" className="text-sm text-red-600">{error}</p>}

      {rows === null ? (!error && <p className="text-sm text-gray-500">불러오는 중…</p>)
        : rows.length === 0 ? <p className="text-sm text-gray-600" data-testid="pension-empty">가입한 퇴직연금이 없습니다.</p>
          : rows.map((p) => {
            const r = PENSION_RULES[p.scheme];
            const amount = amounts[p.id] ?? '';
            const source = sources[p.id] ?? r.contributionSources[0];
            return (
              <Card key={p.id} testId={`pension-${p.id}`}>
                <div className="flex flex-wrap items-baseline justify-between gap-2">
                  <b>{p.scheme}{p.employerName ? ` · ${p.employerName}` : ''}</b>
                  <span className="text-sm text-gray-500">#{p.id} · {p.status}</span>
                </div>
                <dl className="grid gap-2 text-sm sm:grid-cols-3">
                  <div><dt className="text-gray-500">적립금</dt>
                    <dd className="font-bold" data-testid={`accumulated-${p.id}`}>{fmt(p.accumulatedAmount)}</dd></div>
                  <div><dt className="text-gray-500">최근 운용수익 인식</dt>
                    <dd>{p.lastInterestSettledOn ?? '-'}</dd></div>
                  <div><dt className="text-gray-500">수급</dt>
                    <dd>{p.benefitStartedOn ? `${p.benefitType} · ${p.benefitStartedOn}` : '미개시'}</dd></div>
                </dl>

                {p.status === 'ACCUMULATING' && (
                  <div className="flex flex-wrap items-end gap-2 border-t pt-2">
                    <Field label="금액">
                      <input inputMode="numeric" className="mt-1 block w-36 rounded border px-3 py-2"
                        aria-label={`연금 ${p.id} 금액`} value={amount}
                        onChange={(e) => setAmounts({ ...amounts, [p.id]: e.target.value })} />
                    </Field>
                    <Field label="납입 주체">
                      <select className="mt-1 block rounded border px-3 py-2" value={source}
                        aria-label={`연금 ${p.id} 납입 주체`}
                        onChange={(e) => setSources({ ...sources, [p.id]: e.target.value as ContributionSource })}>
                        {/* 제도가 허용하는 주체만 — DB 형에 가입자 납입을 열면 400 왕복이 된다. */}
                        {r.contributionSources.map((s) => (
                          <option key={s} value={s}>{s === 'EMPLOYER' ? '회사' : '가입자'}</option>
                        ))}
                      </select>
                    </Field>
                    <button type="button" disabled={busy || Number(amount) <= 0}
                      className={`${btn} bg-blue-600 text-white`}
                      onClick={() => void run('납입', async () => {
                        await pensionApi.contribute(p.id, Number(amount), source);
                        setAmounts({ ...amounts, [p.id]: '' });
                        showToast('납입했습니다.', 'success');
                      })}>납입</button>

                    {/* DB 형은 중도인출이 제도적으로 없다 — 버튼 자체를 그리지 않는다. */}
                    {r.midWithdrawalPermitted && (
                      <button type="button" disabled={busy || Number(amount) <= 0}
                        data-testid={`withdraw-${p.id}`}
                        className={`${btn} border border-gray-300 bg-white text-gray-700`}
                        onClick={() => {
                          const reason = window.prompt(
                            '중도인출 사유 코드를 입력하세요:\n'
                            + WITHDRAWAL_REASONS.map((w) => `${w.value} (${w.label})`).join('\n'));
                          if (!reason) return;
                          if (!WITHDRAWAL_REASONS.some((w) => w.value === reason)) {
                            setError('허용되지 않는 중도인출 사유입니다.');
                            return;
                          }
                          void run('중도인출', async () => {
                            await pensionApi.withdrawMidway(p.id, Number(amount), reason as MidWithdrawalReason);
                            setAmounts({ ...amounts, [p.id]: '' });
                            showToast('중도인출했습니다.', 'success');
                          });
                        }}>중도인출</button>
                    )}

                    {/* 급여 개시 — ANNUITY 는 가입 10년을 요구한다(서버가 최종 판정). */}
                    <button type="button" disabled={busy} className={`${btn} bg-gray-800 text-white`}
                      onClick={() => {
                        const t: BenefitType = window.confirm(
                          '연금(ANNUITY)으로 개시하려면 확인, 일시금(LUMP_SUM)이면 취소를 누르세요.\n\n'
                          + `연금: 만 ${BENEFIT_RULES.ANNUITY.minimumAge}세 + 가입 ${BENEFIT_RULES.ANNUITY.minimumSubscribedYears}년\n`
                          + `일시금: 만 ${BENEFIT_RULES.LUMP_SUM.minimumAge}세`) ? 'ANNUITY' : 'LUMP_SUM';
                        void run('급여개시', async () => {
                          await pensionApi.startBenefit(p.id, t);
                          showToast(`급여를 개시했습니다 (${t}).`, 'success');
                        });
                      }}>급여 개시</button>
                  </div>
                )}

                {/* 운영자 전용 두 조작 — 서버가 ADMIN·MANAGER 로 막는다. 권한이 없으면 그리지 않는다. */}
                {isOperator && (
                  <div className="flex flex-wrap items-end gap-2 border-t pt-2" data-testid={`ops-${p.id}`}>
                    <span className="text-xs text-gray-500">운영자 전용</span>
                    <button type="button" disabled={busy} className={`${btn} bg-amber-600 text-white`}
                      onClick={() => void run('운용수익 인식', async () => {
                        await pensionApi.settleInterest(p.id);
                        showToast('운용수익을 인식했습니다.', 'success');
                      })}>운용수익 인식</button>
                    {p.status === 'RECEIVING' && (
                      <button type="button" disabled={busy || Number(amount) <= 0}
                        className={`${btn} bg-amber-600 text-white`}
                        onClick={() => void run('수급 지급', async () => {
                          await pensionApi.payBenefit(p.id, Number(amount));
                          setAmounts({ ...amounts, [p.id]: '' });
                          showToast('수급을 지급했습니다.', 'success');
                        })}>수급 지급</button>
                    )}
                  </div>
                )}
              </Card>
            );
          })}
    </div>
  );
};

// ────────────────────────────────────────────────────────────────────────────
const TABS: { value: Tab; label: string }[] = [
  { value: 'deposit', label: '정기예금' },
  { value: 'savings', label: '적금' },
  { value: 'pension', label: '퇴직연금' },
];

const BankingConsolePage: React.FC = () => {
  const { user } = useAuth();
  const [tab, setTab] = useState<Tab>('deposit');
  const isOperator = user?.role === 'ADMIN' || user?.role === 'MANAGER';

  return (
    <div className="min-h-screen bg-gray-50 py-8 px-4 sm:px-6 lg:px-8">
      <div className="max-w-4xl mx-auto space-y-4">
        <div>
          <h1 className="text-2xl font-bold text-gray-900">수신 상품</h1>
          <p className="text-sm text-gray-500 mt-1">
            정기예금 · 적금 · 퇴직연금. 계약 주체가 본인이라 <b>내 계약만</b> 보입니다.
          </p>
        </div>

        <div className="flex gap-2" role="tablist">
          {TABS.map((t) => (
            <button key={t.value} type="button" role="tab" aria-selected={tab === t.value}
              onClick={() => setTab(t.value)}
              className={`rounded px-3 py-1.5 text-sm font-semibold ${
                tab === t.value ? 'bg-gray-900 text-white' : 'border border-gray-300 bg-white text-gray-700'}`}>
              {t.label}
            </button>
          ))}
        </div>

        {tab === 'deposit' && <DepositPanel />}
        {tab === 'savings' && <SavingsPanel />}
        {tab === 'pension' && <PensionPanel isOperator={isOperator} />}
      </div>
    </div>
  );
};

export default BankingConsolePage;
