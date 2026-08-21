import React, { useState } from 'react';
import {
  proposalApi, applicationApi, policyApi, UnderwritingGateError,
  type ProposalSummary, type IssuedPolicy, type PolicyTermination,
  type Gender, type SalesChannel,
} from '@/api/insuranceSales';
import { saveBlob } from '@/api/auditLog';
import { apiErrorMessage } from '@/lib/apiError';
import { useToast } from '@/contexts/useToast';

/**
 * 보험 영업 체인 — 가입설계 → 청약 → 승인 → 계약.
 *
 * <p><b>이 화면의 값은 식별자를 이어 주는 것이다.</b> 각 단계의 응답이 다음 단계의 열쇠를 준다
 * (설계 전환 → applicationId, 승인 → policyNumber). 서버에 목록 조회가 없어서, 화면이 그 값을
 * 물려주지 않으면 사람이 화면 밖에 적어 두고 다시 입력해야 한다.
 *
 * <p><b>승인 게이트 둘을 갈라서 보여 준다.</b> 완전판매 게이트(교부 증빙 없음)와 서류 대사
 * 게이트(첨부 서류가 MATCHED 아님)는 상태코드가 같은 409 지만 <b>조치가 다르다</b> — 하나는
 * 상품설명서 교부 화면으로, 하나는 증빙 리뷰 큐로 가야 한다. 뭉개면 운영자가 어디로 갈지 모른다.
 *
 * <p><b>해지와 철회를 나란히 두지 않는다.</b> 해지는 해지환급금, 철회는 납입 보험료 반환으로
 * 돈의 성격이 다른데 버튼이 붙어 있으면 잘못 누르기 쉽다. 각각 확인 문구로 그 차이를 말한다.
 */

const fmt = (v: number | null | undefined) =>
  v === null || v === undefined ? '-'
    : new Intl.NumberFormat('ko-KR', { style: 'currency', currency: 'KRW' }).format(v);

const PRODUCTS = ['LIFE-TERM-20', 'LIFE-WHOLE-01', 'HEALTH-CI-01', 'AUTO-STD-01', 'FIRE-HOME-01'];
const BANKS = ['BANK-001', 'BANK-002', 'BANK-003'];

const inputClass = 'mt-1 w-full rounded border px-3 py-2';
const btn = 'rounded px-3 py-2 text-sm font-semibold disabled:opacity-50';

const Field: React.FC<{ label: string; hint?: string; children: React.ReactNode }> =
  ({ label, hint, children }) => (
    <div className="text-sm">
      <label className="block"><span className="text-gray-600">{label}</span>{children}</label>
      {hint && <span className="mt-1 block text-xs text-gray-500">{hint}</span>}
    </div>
  );

const Section: React.FC<{ title: string; note: string; children: React.ReactNode; testId: string }> =
  ({ title, note, children, testId }) => (
    <section className="rounded-xl border border-gray-200 bg-white p-4 space-y-3" data-testid={testId}>
      <div>
        <h2 className="font-semibold text-gray-900">{title}</h2>
        <p className="mt-1 text-sm text-gray-500">{note}</p>
      </div>
      {children}
    </section>
  );

const InsuranceSalesPage: React.FC = () => {
  const { showToast } = useToast();
  const [busy, setBusy] = useState(false);

  // ── 설계
  const [design, setDesign] = useState({
    productCode: '', insuredName: '', insuredBirthDate: '', insuredGender: 'M' as Gender,
    coverageAmount: '', paymentTermYears: '20', salesChannel: 'FC' as SalesChannel,
    partnerBankCode: '', consultationId: '',
  });
  const [proposal, setProposal] = useState<ProposalSummary | null>(null);
  const [proposalError, setProposalError] = useState<string | null>(null);
  const [lookupId, setLookupId] = useState('');

  // ── 청약
  const [contractorName, setContractorName] = useState('');
  const [applicationId, setApplicationId] = useState('');
  const [appError, setAppError] = useState<string | null>(null);
  const [gate, setGate] = useState<UnderwritingGateError | null>(null);
  const [policy, setPolicy] = useState<IssuedPolicy | null>(null);

  // ── 계약
  const [policyNumber, setPolicyNumber] = useState('');
  const [termination, setTermination] = useState<PolicyTermination | null>(null);
  const [policyError, setPolicyError] = useState<string | null>(null);

  const bankRequired = design.salesChannel === 'BANCA';
  const designReady = design.productCode.trim() !== '' && design.insuredName.trim() !== ''
    && design.insuredBirthDate !== '' && Number(design.coverageAmount) > 0
    && Number(design.paymentTermYears) > 0 && (!bankRequired || design.partnerBankCode.trim() !== '');

  const run = async (setErr: (v: string | null) => void, fallback: string, action: () => Promise<void>) => {
    setBusy(true); setErr(null);
    try { await action(); }
    catch (err) { setErr(apiErrorMessage(err, fallback)); }
    finally { setBusy(false); }
  };

  const createProposal = () => void run(setProposalError, '설계를 산출하지 못했습니다.', async () => {
    const result = await proposalApi.create({
      productCode: design.productCode.trim(), insuredName: design.insuredName.trim(),
      insuredBirthDate: design.insuredBirthDate, insuredGender: design.insuredGender,
      coverageAmount: Number(design.coverageAmount), paymentTermYears: Number(design.paymentTermYears),
      salesChannel: design.salesChannel,
      ...(bankRequired ? { partnerBankCode: design.partnerBankCode.trim() } : {}),
      ...(design.consultationId.trim() ? { consultationId: design.consultationId.trim() } : {}),
    });
    setProposal(result);
    showToast(`설계 ${result.proposalId} — 연보험료 ${fmt(result.annualPremium)}`, 'success');
  });

  const convert = () => void run(setAppError, '청약 전환에 실패했습니다.', async () => {
    const result = await proposalApi.convert(proposal!.proposalId, contractorName.trim());
    // 체인의 핵심 — 다음 단계 열쇠를 화면이 물려준다.
    setApplicationId(result.applicationId);
    setGate(null); setPolicy(null);
    showToast(`청약 ${result.applicationId} 로 전환했습니다.`, 'success');
  });

  const approve = () => {
    setGate(null);
    void (async () => {
      setBusy(true); setAppError(null);
      try {
        const issued = await applicationApi.approve(applicationId.trim());
        setPolicy(issued);
        setPolicyNumber(issued.policyNumber);
        showToast(`계약 ${issued.policyNumber} 이 발행됐습니다.`, 'success');
      } catch (err) {
        // 게이트 위반은 실패가 아니라 "아직 요건이 안 찼다" 다 — 조치 안내가 달라진다.
        if (err instanceof UnderwritingGateError) setGate(err);
        else setAppError(apiErrorMessage(err, '승인에 실패했습니다.'));
      } finally { setBusy(false); }
    })();
  };

  const terminate = (mode: 'surrender' | 'cancel') => {
    const label = mode === 'surrender' ? '해지' : '철회';
    if (!window.confirm(
      `계약 ${policyNumber.trim()} 을 ${label}합니다.\n\n`
      + (mode === 'surrender'
        ? '해지환급금이 지급됩니다 — 납입한 보험료보다 적을 수 있습니다.'
        : '청약 철회로 납입 보험료가 반환됩니다 — 해지와 다른 경로입니다.')
      + '\n\n계속하시겠습니까?')) return;
    void run(setPolicyError, `${label}에 실패했습니다.`, async () => {
      const result = mode === 'surrender'
        ? await policyApi.surrender(policyNumber.trim())
        : await policyApi.cancel(policyNumber.trim());
      setTermination(result);
      showToast(`${label} 완료 — 상태 ${result.status}`, 'success');
    });
  };

  return (
    <div className="min-h-screen bg-gray-50 py-8 px-4 sm:px-6 lg:px-8">
      <div className="max-w-3xl mx-auto space-y-4">
        <div>
          <h1 className="text-2xl font-bold text-gray-900">보험 영업</h1>
          <p className="mt-1 text-sm text-gray-500">
            가입설계 → 청약 → 승인 → 계약. 각 단계의 결과가 <b>다음 단계 식별자</b>를 자동으로
            채웁니다. 승인은 상품설명서 교부 증빙이 있어야 통과합니다.
          </p>
        </div>

        <datalist id="ins-products">{PRODUCTS.map((p) => <option key={p} value={p} />)}</datalist>
        <datalist id="ins-banks">{BANKS.map((b) => <option key={b} value={b} />)}</datalist>

        {/* ── 1. 가입설계 ─────────────────────────────────────────── */}
        <Section testId="proposal-section" title="1. 가입설계"
          note="보험나이·요율을 서버가 산출합니다. 생년월일은 나이 산정에만 쓰고 저장하지 않습니다.">
          <div className="grid gap-3 sm:grid-cols-3">
            <Field label="상품 코드">
              <input list="ins-products" value={design.productCode} className={`${inputClass} font-mono`}
                onChange={(e) => setDesign({ ...design, productCode: e.target.value })} />
            </Field>
            <Field label="피보험자명">
              <input value={design.insuredName} className={inputClass}
                onChange={(e) => setDesign({ ...design, insuredName: e.target.value })} />
            </Field>
            <Field label="생년월일">
              <input type="date" value={design.insuredBirthDate} className={inputClass}
                onChange={(e) => setDesign({ ...design, insuredBirthDate: e.target.value })} />
            </Field>
            <Field label="성별">
              <select value={design.insuredGender} className={inputClass}
                onChange={(e) => setDesign({ ...design, insuredGender: e.target.value as Gender })}>
                <option value="M">남</option><option value="F">여</option>
              </select>
            </Field>
            <Field label="보장금액">
              <input value={design.coverageAmount} inputMode="numeric" className={inputClass}
                onChange={(e) => setDesign({ ...design, coverageAmount: e.target.value })} />
            </Field>
            <Field label="납입기간(년)">
              <input value={design.paymentTermYears} inputMode="numeric" className={inputClass}
                onChange={(e) => setDesign({ ...design, paymentTermYears: e.target.value })} />
            </Field>
            <Field label="판매 채널">
              <select value={design.salesChannel} className={inputClass}
                onChange={(e) => setDesign({ ...design, salesChannel: e.target.value as SalesChannel })}>
                <option value="FC">FC (설계사)</option><option value="BANCA">BANCA (제휴은행)</option>
              </select>
            </Field>
            {/* FC 설계엔 제휴은행이 없다 — 안 쓰는 칸을 남기면 무엇이 필수인지 흐려진다. */}
            {bankRequired && (
              <Field label="제휴은행 코드" hint="방카 설계는 필수입니다">
                <input list="ins-banks" value={design.partnerBankCode} className={`${inputClass} font-mono`}
                  onChange={(e) => setDesign({ ...design, partnerBankCode: e.target.value })} />
              </Field>
            )}
          </div>
          <div className="flex flex-wrap items-end gap-2">
            <button type="button" onClick={createProposal} disabled={!designReady || busy}
              className={`${btn} bg-blue-600 text-white`}>설계 산출</button>
            <span className="text-gray-400">또는</span>
            <Field label="기존 설계 번호">
              <input value={lookupId} className="mt-1 block w-52 rounded border px-3 py-2 font-mono"
                onChange={(e) => setLookupId(e.target.value)} />
            </Field>
            <button type="button" disabled={busy || lookupId.trim() === ''}
              className={`${btn} border border-gray-300 bg-white text-gray-700`}
              onClick={() => void run(setProposalError, '설계를 조회하지 못했습니다.', async () => {
                setProposal(await proposalApi.get(lookupId.trim()));
              })}>설계 조회</button>
          </div>
          {proposalError && <p role="alert" className="text-sm text-red-600">{proposalError}</p>}

          {proposal && (
            <div className="rounded bg-gray-50 p-3 text-sm space-y-2" data-testid="proposal-result">
              <dl className="grid gap-2 sm:grid-cols-4">
                <div><dt className="text-gray-500">설계 번호</dt>
                  <dd className="font-mono" data-testid="proposal-id">{proposal.proposalId}</dd></div>
                <div><dt className="text-gray-500">보험나이</dt><dd>{proposal.insuranceAge}세</dd></div>
                <div><dt className="text-gray-500">연보험료</dt>
                  <dd className="font-bold">{fmt(proposal.annualPremium)}</dd></div>
                {/* 만기가 지나면 전환이 409 로 막힌다 — 그 날짜가 화면에 없으면 이유를 모른다. */}
                <div><dt className="text-gray-500">설계 유효기한</dt>
                  <dd data-testid="valid-until">{proposal.validUntil}</dd></div>
              </dl>
              <button type="button" disabled={busy}
                className={`${btn} border border-gray-300 bg-white text-gray-700`}
                onClick={() => void run(setProposalError, '설계서를 내려받지 못했습니다.', async () => {
                  saveBlob(await proposalApi.sheet(proposal.proposalId), `proposal-${proposal.proposalId}.pdf`);
                })}>설계서 PDF</button>
              {proposal.convertedApplicationId && (
                <p className="text-xs text-gray-600" data-testid="already-converted">
                  이미 청약 <b className="font-mono">{proposal.convertedApplicationId}</b> 로 전환된 설계입니다.
                </p>
              )}
            </div>
          )}
        </Section>

        {/* ── 2. 청약 ─────────────────────────────────────────────── */}
        <Section testId="application-section" title="2. 청약 · 승인"
          note="전환하면 청약 번호가 아래에 자동으로 채워집니다. 승인은 완전판매 게이트를 통과해야 합니다.">
          {proposal && !proposal.convertedApplicationId && (
            <div className="flex flex-wrap items-end gap-2">
              <Field label="계약자명" hint={`설계 ${proposal.proposalId} 를 청약으로 전환합니다`}>
                <input value={contractorName} className="mt-1 block w-52 rounded border px-3 py-2"
                  onChange={(e) => setContractorName(e.target.value)} />
              </Field>
              <button type="button" onClick={convert} disabled={busy || contractorName.trim() === ''}
                className={`${btn} bg-blue-600 text-white`}>청약 전환</button>
            </div>
          )}

          <div className="flex flex-wrap items-end gap-2 border-t pt-3">
            <Field label="청약 번호">
              <input value={applicationId} className="mt-1 block w-56 rounded border px-3 py-2 font-mono"
                onChange={(e) => { setApplicationId(e.target.value); setGate(null); setPolicy(null); }} />
            </Field>
            <button type="button" disabled={busy || applicationId.trim() === ''}
              className={`${btn} border border-gray-300 bg-white text-gray-700`}
              onClick={() => void run(setAppError, '심사 착수에 실패했습니다.', async () => {
                const status = await applicationApi.startReview(applicationId.trim());
                showToast(`심사 착수 — ${status}`, 'success');
              })}>심사 착수</button>
            <button type="button" onClick={approve} disabled={busy || applicationId.trim() === ''}
              className={`${btn} bg-green-700 text-white`}>승인</button>
            <button type="button" disabled={busy || applicationId.trim() === ''}
              className={`${btn} border border-gray-300 bg-white text-gray-700`}
              onClick={() => {
                const reason = window.prompt('반려 사유를 입력하세요:');
                if (!reason?.trim()) return;
                void run(setAppError, '반려에 실패했습니다.', async () => {
                  await applicationApi.reject(applicationId.trim(), reason.trim());
                  showToast('반려했습니다.', 'success');
                });
              }}>반려</button>
          </div>

          {appError && <p role="alert" className="text-sm text-red-600">{appError}</p>}

          {/* 게이트 둘을 갈라 조치까지 안내한다 — 상태코드는 같지만 갈 곳이 다르다. */}
          {gate && (
            <div role="alert" className="rounded bg-amber-50 p-3 text-sm text-amber-900"
              data-testid="gate-alert">
              <p className="font-bold" data-testid="gate-kind">
                {gate.kind === 'DISCLOSURE' ? '완전판매 게이트 — 교부 증빙이 없습니다'
                  : gate.kind === 'DOCUMENT' ? '서류 대사 게이트 — 첨부 서류가 대사되지 않았습니다'
                    : '승인 게이트를 통과하지 못했습니다'}
              </p>
              <p className="mt-1">{gate.message}</p>
              <p className="mt-1 text-xs">
                {gate.kind === 'DISCLOSURE'
                  ? '시스템 관리 → 상품설명서 교부에서 이 청약 번호로 교부하세요.'
                  : gate.kind === 'DOCUMENT'
                    ? '시스템 관리 → 증빙 리뷰 큐에서 서류를 대사하세요.'
                    : '서버 문구를 확인하세요.'}
              </p>
            </div>
          )}

          {policy && (
            <dl className="grid gap-2 rounded bg-green-50 p-3 text-sm sm:grid-cols-3"
              data-testid="issued-policy">
              <div><dt className="text-gray-500">증권번호</dt>
                <dd className="font-mono font-bold" data-testid="policy-number">{policy.policyNumber}</dd></div>
              <div><dt className="text-gray-500">1차년도 수수료</dt>
                <dd>{fmt(policy.firstYearCommissionTotal)}</dd></div>
              <div><dt className="text-gray-500">분할 회차</dt><dd>{policy.installmentCount}회</dd></div>
            </dl>
          )}
        </Section>

        {/* ── 3. 계약 ─────────────────────────────────────────────── */}
        <Section testId="policy-section" title="3. 계약 유지·해지"
          note="해지는 해지환급금, 철회는 납입 보험료 반환입니다 — 돈의 성격이 다릅니다.">
          <div className="flex flex-wrap items-end gap-2">
            <Field label="증권번호">
              <input value={policyNumber} className="mt-1 block w-56 rounded border px-3 py-2 font-mono"
                onChange={(e) => { setPolicyNumber(e.target.value); setTermination(null); }} />
            </Field>
            <button type="button" disabled={busy || policyNumber.trim() === ''}
              className={`${btn} border border-gray-300 bg-white text-gray-700`}
              onClick={() => void run(setPolicyError, '지급 이력을 불러오지 못했습니다.', async () => {
                const rows = await policyApi.payouts(policyNumber.trim());
                showToast(`지급 이력 ${rows.length}건`, 'info');
                setTermination({ policyNumber: policyNumber.trim(), status: 'ACTIVE', payout: rows[0] ?? null });
              })}>지급 이력</button>
            <button type="button" onClick={() => terminate('surrender')}
              disabled={busy || policyNumber.trim() === ''}
              className={`${btn} bg-red-600 text-white`}>해지</button>
            <button type="button" onClick={() => terminate('cancel')}
              disabled={busy || policyNumber.trim() === ''}
              className={`${btn} border border-red-300 bg-white text-red-700`}>철회</button>
          </div>

          {policyError && <p role="alert" className="text-sm text-red-600">{policyError}</p>}

          {termination && (
            <dl className="grid gap-2 rounded bg-gray-50 p-3 text-sm sm:grid-cols-3"
              data-testid="termination-result">
              <div><dt className="text-gray-500">상태</dt>
                <dd data-testid="policy-status">{termination.status}</dd></div>
              <div><dt className="text-gray-500">지급액</dt>
                <dd className="font-bold">{fmt(termination.payout?.amount)}</dd></div>
              <div><dt className="text-gray-500">납입 누계</dt>
                <dd>{fmt(termination.payout?.paidPremiumTotal)}</dd></div>
            </dl>
          )}
        </Section>
      </div>
    </div>
  );
};

export default InsuranceSalesPage;
