import React, { useCallback, useEffect, useState } from 'react';
import {
  taxApi,
  type TaxInvoiceScan,
  type TaxScanStatus,
  type TaxType,
  type SellerTaxProfile,
  type TaxReconciliation,
  type TaxInvoice,
} from '@/api/tax';
import { formatDecimal } from '@/lib/decimal';
import { apiErrorMessage, apiErrorStatus } from '@/lib/apiError';
import { useToast } from '@/contexts/useToast';
import Spinner from '@/components/Spinner';

/**
 * 세무 콘솔 (ADMIN/MANAGER) — 세금계산서 스캔 리뷰 · 정산별 세무 산출물 · 셀러 세무 프로필.
 *
 * <p>여태 이 세 표면은 화면이 없어 curl 로만 굴릴 수 있었다. 그런데 여기 있는 실행 둘은
 * <b>되돌리기 어렵다</b> — 전표 전기는 원장을 움직이고, 세금계산서 발행은 대외 산출물을 만든다.
 * 그래서 둘 다 확인창을 거치게 하고, 누르기 전에 <b>3자 대사 결과를 먼저 보여 준다</b>
 * (계산·세금계산서·원장이 서로 맞는지 모른 채 전기하면 틀린 장부를 만든다).
 *
 * <p>sellerId 를 운영자가 입력하는 화면이라 서버가 정산의 실제 소유 셀러와 대조해 403 을 준다.
 * 403 을 장애 문구로 뭉뚱그리면 운영자가 "시스템이 고장났다"로 읽으므로 셀러 지정 오류로 구분한다.
 *
 * <p>OCR 은 사람의 판단을 대체하지 않는다. 신뢰도·산술 정합(공급가+세액=합계, 세액=공급가10%)을
 * 행마다 드러내서, "AI 가 읽었으니 맞겠지"가 아니라 사람이 근거를 보고 종결하게 만든다.
 */

type Tab = 'scans' | 'settlement' | 'profile';

/** 사람 손이 필요한 상태 — 저신뢰 보류·금액 불일치·미매칭. 서버 기본 큐와 같은 집합이다. */
const REVIEW_QUEUE: TaxScanStatus[] = ['EXTRACTED', 'MISMATCHED', 'UNMATCHED'];

/** 큐 필터 — 기본은 '리뷰 필요' 묶음이다. 상태별로 쪼개 보면 다른 곳에 쌓인 건을 놓친다. */
type ScanFilter = 'REVIEW' | TaxScanStatus;

const SCAN_FILTERS: ScanFilter[] = ['REVIEW', 'EXTRACTED', 'MISMATCHED', 'UNMATCHED', 'MATCHED', 'REJECTED'];

const FILTER_LABEL: Record<ScanFilter, string> = {
  REVIEW: '리뷰 필요(보류·불일치·미매칭)',
  EXTRACTED: 'EXTRACTED (저신뢰 보류)',
  MATCHED: 'MATCHED',
  MISMATCHED: 'MISMATCHED',
  UNMATCHED: 'UNMATCHED',
  REJECTED: 'REJECTED',
};

const STATUS_STYLE: Record<TaxScanStatus, string> = {
  EXTRACTED: 'bg-gray-100 text-gray-700',
  MATCHED: 'bg-green-100 text-green-800',
  MISMATCHED: 'bg-amber-100 text-amber-800',
  UNMATCHED: 'bg-red-100 text-red-700',
  REJECTED: 'bg-gray-200 text-gray-500',
};

const money = (v: string | null | undefined) => {
  const formatted = formatDecimal(v);
  return formatted === null ? '-' : `${formatted}원`;
};

const TaxConsolePage: React.FC = () => {
  const { showToast } = useToast();
  const [tab, setTab] = useState<Tab>('scans');

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-bold text-gray-900">세무</h1>
        <p className="text-sm text-gray-500 mt-1">
          세금계산서 스캔 리뷰 · 정산별 세무 전표와 발행 · 셀러 세무유형 등록. 전표 전기와 발행은
          되돌리기 어려우니 대사 결과를 먼저 확인하세요.
        </p>
      </div>

      <div className="flex gap-2 border-b border-gray-200">
        {([['scans', '스캔 리뷰'], ['settlement', '정산별 세무'], ['profile', '세무 프로필']] as const)
          .map(([key, label]) => (
            <button
              key={key}
              onClick={() => setTab(key)}
              className={`px-4 py-2 text-sm font-semibold border-b-2 -mb-px ${
                tab === key ? 'border-blue-600 text-blue-700' : 'border-transparent text-gray-500 hover:text-gray-700'
              }`}
            >
              {label}
            </button>
          ))}
      </div>

      {tab === 'scans' && <ScanQueue showToast={showToast} />}
      {tab === 'settlement' && <SettlementTax showToast={showToast} />}
      {tab === 'profile' && <ProfileEditor showToast={showToast} />}
    </div>
  );
};

type Toast = (message: string, type: 'success' | 'error' | 'warning' | 'info') => void;

/**
 * 스캔 리뷰 큐 — 기본은 '리뷰 필요' 묶음(보류·불일치·미매칭)을 한 화면에 연다.
 *
 * 종전에는 MISMATCHED 하나만 기본이었다. 저신뢰 판독이 자동 대사를 건너뛰고 EXTRACTED 에
 * 남게 되면서, 그 건들이 기본 화면에 보이지 않는 사각지대가 생겼다.
 */
const ScanQueue: React.FC<{ showToast: Toast }> = ({ showToast }) => {
  const [status, setStatus] = useState<ScanFilter>('REVIEW');
  const [scans, setScans] = useState<TaxInvoiceScan[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [notes, setNotes] = useState<Record<number, string>>({});
  const [busy, setBusy] = useState(false);

  const load = useCallback(async (target: ScanFilter) => {
    setLoading(true);
    setError(null);
    try {
      setScans(await taxApi.scans(target === 'REVIEW' ? REVIEW_QUEUE : target, 50));
    } catch (err) {
      setScans([]);
      setError(apiErrorMessage(err, '스캔 큐를 불러오지 못했습니다.'));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { void load(status); }, [status, load]);

  const handleReject = async (scan: TaxInvoiceScan) => {
    const note = (notes[scan.id] ?? '').trim();
    // 사유 없는 반려는 감사 근거를 남기지 않는다 — 서버를 부르지 않고 여기서 막는다.
    if (!note) {
      showToast('반려 사유를 입력하세요.', 'warning');
      return;
    }
    setBusy(true);
    try {
      await taxApi.rejectScan(scan.id, note);
      showToast(`스캔 #${scan.id} 반려`, 'success');
      await load(status);
    } catch (err) {
      showToast(apiErrorMessage(err, '반려 실패'), 'error');
    } finally {
      setBusy(false);
    }
  };

  const handleRematch = async (scan: TaxInvoiceScan) => {
    setBusy(true);
    try {
      const updated = await taxApi.rematchScan(scan.id);
      showToast(`재대사 결과: ${updated.status}`, updated.status === 'MATCHED' ? 'success' : 'info');
      await load(status);
    } catch (err) {
      showToast(apiErrorMessage(err, '재대사 실패'), 'error');
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="bg-white rounded-xl border border-gray-200 overflow-hidden">
      <div className="px-4 py-3 border-b border-gray-100 flex items-center gap-3">
        <label htmlFor="scan-status" className="text-sm font-medium text-gray-600">상태</label>
        <select
          id="scan-status" value={status} className="input w-64"
          onChange={(e) => setStatus(e.target.value as ScanFilter)}
        >
          {SCAN_FILTERS.map((s) => <option key={s} value={s}>{FILTER_LABEL[s]}</option>)}
        </select>
        <span className="text-xs text-gray-400">최대 50건</span>
      </div>

      {loading && <div className="py-16 flex justify-center"><Spinner size="lg" message="스캔 로드 중..." /></div>}
      {error && <p className="px-4 py-8 text-center text-red-600">{error}</p>}

      {!loading && !error && scans.length === 0 && (
        <p className="px-4 py-10 text-center text-gray-400">리뷰할 스캔이 없습니다.</p>
      )}

      {!loading && !error && scans.map((scan) => (
        <div key={scan.id} className="px-4 py-4 border-b border-gray-100 last:border-0">
          <div className="flex flex-wrap items-center gap-2">
            <span className={`text-xs font-semibold px-2 py-0.5 rounded-full ${STATUS_STYLE[scan.status]}`}>
              {scan.status}
            </span>
            <span className="font-medium text-gray-800">{scan.fileName ?? `스캔 #${scan.id}`}</span>
            {scan.needsReview && (
              <span className="text-xs font-semibold px-2 py-0.5 rounded-full bg-amber-100 text-amber-800">
                확인 필요 (신뢰도 {scan.confidence ?? '?'})
              </span>
            )}
            {!scan.vatConsistent && (
              <span className="text-xs font-semibold px-2 py-0.5 rounded-full bg-red-100 text-red-700">
                세액 불일치
              </span>
            )}
            {!scan.totalConsistent && (
              <span className="text-xs font-semibold px-2 py-0.5 rounded-full bg-red-100 text-red-700">
                합계 불일치
              </span>
            )}
          </div>

          <div className="mt-2 grid grid-cols-2 md:grid-cols-4 gap-x-6 gap-y-1 text-sm text-gray-600">
            <span>공급자 {scan.supplierBusinessNo ?? '-'}</span>
            <span>매입자 {scan.buyerBusinessNo ?? '-'}</span>
            <span>작성일 {scan.writtenDate ?? '-'}</span>
            <span>승인번호 {scan.approvalNumber ?? '-'}</span>
            <span>공급가 {money(scan.supplyAmount)}</span>
            <span>세액 {money(scan.taxAmount)}</span>
            <span>합계 {money(scan.totalAmount)}</span>
            <span>연결 발행분 {scan.linkedTaxInvoiceId ?? '없음'}</span>
          </div>

          <div className="mt-3 flex flex-wrap items-center gap-2">
            <input
              placeholder="반려 사유"
              value={notes[scan.id] ?? ''}
              onChange={(e) => setNotes((prev) => ({ ...prev, [scan.id]: e.target.value }))}
              className="input flex-1 min-w-[12rem]"
            />
            <button
              onClick={() => handleReject(scan)} disabled={busy}
              className="px-3 py-2 text-sm font-semibold rounded-lg bg-red-50 text-red-700 hover:bg-red-100 disabled:opacity-50"
            >
              반려
            </button>
            <button
              onClick={() => handleRematch(scan)} disabled={busy}
              className="px-3 py-2 text-sm font-semibold rounded-lg bg-gray-100 text-gray-700 hover:bg-gray-200 disabled:opacity-50"
            >
              재대사
            </button>
          </div>
        </div>
      ))}
    </div>
  );
};

/** 정산별 세무 — 대사를 먼저 보여 주고, 그 위에서 전기·발행을 집행한다. */
const SettlementTax: React.FC<{ showToast: Toast }> = ({ showToast }) => {
  const [settlementId, setSettlementId] = useState('');
  const [sellerId, setSellerId] = useState('');
  const [recon, setRecon] = useState<TaxReconciliation | null>(null);
  const [invoice, setInvoice] = useState<TaxInvoice | null>(null);
  const [invoiceNotice, setInvoiceNotice] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [busy, setBusy] = useState(false);

  const ids = () => ({ s: Number(settlementId), seller: Number(sellerId) });
  const ready = settlementId.trim() !== '' && sellerId.trim() !== '';

  const loadRecon = useCallback(async (s: number, seller: number) => {
    setLoading(true);
    setError(null);
    try {
      setRecon(await taxApi.reconcile(s, seller));
    } catch (err) {
      setRecon(null);
      // 403 은 서버가 소유권을 대조해 막은 것이다 — 장애가 아니라 입력 오류로 읽혀야 한다.
      setError(apiErrorStatus(err) === 403
        ? '지정한 셀러가 이 정산의 소유자가 아닙니다. 셀러 ID 를 확인하세요.'
        : apiErrorMessage(err, '대사를 불러오지 못했습니다.'));
    } finally {
      setLoading(false);
    }
  }, []);

  const loadInvoice = useCallback(async (s: number) => {
    setInvoiceNotice(null);
    try {
      setInvoice(await taxApi.invoice(s));
    } catch (err) {
      setInvoice(null);
      setInvoiceNotice(apiErrorStatus(err) === 404
        ? '세금계산서가 아직 발행되지 않았습니다.'
        : apiErrorMessage(err, '세금계산서를 불러오지 못했습니다.'));
    }
  }, []);

  const handleLookup = async () => {
    // 셀러 지정이 필수인 API 다 — 빈 조회로 전 셀러를 훑는 사고를 막는다.
    if (!ready) {
      showToast('정산 ID 와 셀러 ID 를 모두 입력하세요.', 'warning');
      return;
    }
    const { s, seller } = ids();
    await Promise.all([loadRecon(s, seller), loadInvoice(s)]);
  };

  const handlePost = async () => {
    const { s, seller } = ids();
    if (!window.confirm(
      `정산 #${s} 의 세무 전표를 전기합니다.\n`
      + '부가세 예수·원천징수 분개가 원장에 기록됩니다. 진행할까요?')) return;
    setBusy(true);
    try {
      const result = await taxApi.post(s, seller);
      showToast(`전기 ${result.outcome} — 전표 ${result.entriesPosted}건`, 'success');
      await loadRecon(s, seller);
    } catch (err) {
      showToast(apiErrorStatus(err) === 403
        ? '셀러가 이 정산의 소유자가 아닙니다.'
        : apiErrorMessage(err, '전표 전기 실패'), 'error');
    } finally {
      setBusy(false);
    }
  };

  const handleIssue = async () => {
    const { s, seller } = ids();
    if (!window.confirm(
      `정산 #${s} 의 세금계산서를 발행합니다.\n대외 산출물이 만들어집니다. 진행할까요?`)) return;
    setBusy(true);
    try {
      const issued = await taxApi.issue(s, seller);
      setInvoice(issued);
      setInvoiceNotice(null);
      showToast(`세금계산서 발행 — ${issued.issueNumber}`, 'success');
    } catch (err) {
      // 409 = 이미 발행됨. 중복 발행을 막은 것이라 실패로 물들이지 않는다.
      if (apiErrorStatus(err) === 409) {
        // 재조회가 안내 문구를 지우므로 순서가 중요하다 — 먼저 읽고, 그 위에 사유를 남긴다.
        await loadInvoice(s);
        setInvoiceNotice('이미 발행된 세금계산서가 있습니다. 아래 내용을 확인하세요.');
      } else {
        showToast(apiErrorStatus(err) === 403
          ? '셀러가 이 정산의 소유자가 아닙니다.'
          : apiErrorMessage(err, '발행 실패'), 'error');
      }
    } finally {
      setBusy(false);
    }
  };

  const failedChecks = recon?.checks.filter((c) => !c.passed) ?? [];

  return (
    <div className="space-y-4">
      <div className="bg-white rounded-xl border border-gray-200 p-4 flex flex-wrap items-end gap-3">
        <input placeholder="정산 ID" value={settlementId} inputMode="numeric"
          onChange={(e) => setSettlementId(e.target.value)} className="input w-40" />
        <input placeholder="셀러 ID" value={sellerId} inputMode="numeric"
          onChange={(e) => setSellerId(e.target.value)} className="input w-40" />
        <button onClick={handleLookup}
          className="px-4 py-2 bg-blue-600 text-white text-sm font-semibold rounded-lg hover:bg-blue-700">
          대사 조회
        </button>
      </div>

      {loading && <div className="py-12 flex justify-center"><Spinner size="lg" message="대사 조회 중..." /></div>}
      {error && <p className="py-8 text-center text-red-600">{error}</p>}

      {recon && !loading && (
        <div className="bg-white rounded-xl border border-gray-200 overflow-hidden">
          <div className="px-4 py-3 border-b border-gray-100 flex flex-wrap items-center gap-2">
            <h3 className="font-bold text-gray-900">세무 3자 대사</h3>
            <span className={`text-xs font-semibold px-2 py-0.5 rounded-full ${
              recon.matched ? 'bg-green-100 text-green-800' : 'bg-red-100 text-red-700'}`}>
              {recon.matched ? '일치' : '불일치'}
            </span>
            <span className={`text-xs font-semibold px-2 py-0.5 rounded-full ${
              recon.ledgerBalanced ? 'bg-green-100 text-green-800' : 'bg-red-100 text-red-700'}`}>
              원장 {recon.ledgerBalanced ? '균형' : '불균형'}
            </span>
            {failedChecks.length > 0 && (
              <span className="text-xs font-semibold px-2 py-0.5 rounded-full bg-red-100 text-red-700">
                불일치 {failedChecks.length}건
              </span>
            )}
          </div>

          <div className="px-4 py-2 text-sm text-gray-600 flex flex-wrap gap-x-6">
            <span>원장 부가세 예수 {money(recon.ledgerVatAccrued)}</span>
            <span>실제 원천징수 {money(recon.actualWithholdingDeducted)}</span>
          </div>

          <table className="w-full text-sm">
            <thead className="bg-gray-50 border-y border-gray-200">
              <tr>
                {['검증 항목', '기대', '실제', '결과'].map((h) => (
                  <th key={h} className="px-4 py-2 text-left text-xs font-semibold text-gray-500 uppercase">{h}</th>
                ))}
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-100">
              {recon.checks.map((check) => (
                <tr key={check.name} className={check.passed ? '' : 'bg-red-50'}>
                  <td className="px-4 py-2 font-mono text-xs text-gray-700">{check.name}</td>
                  <td className="px-4 py-2">{money(check.expected)}</td>
                  <td className="px-4 py-2">{money(check.actual)}</td>
                  <td className="px-4 py-2">
                    <span className={check.passed ? 'text-green-700' : 'text-red-700 font-semibold'}>
                      {check.passed ? '통과' : '불일치'}
                    </span>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>

          <div className="px-4 py-3 border-t border-gray-100 flex flex-wrap gap-2">
            <button onClick={handlePost} disabled={busy}
              className="px-4 py-2 bg-gray-800 text-white text-sm font-semibold rounded-lg hover:bg-gray-900 disabled:opacity-50">
              전표 전기
            </button>
            <button onClick={handleIssue} disabled={busy}
              className="px-4 py-2 bg-blue-600 text-white text-sm font-semibold rounded-lg hover:bg-blue-700 disabled:opacity-50">
              세금계산서 발행
            </button>
          </div>
        </div>
      )}

      {recon && !loading && (
        <div className="bg-white rounded-xl border border-gray-200 p-4">
          <h3 className="font-bold text-gray-900 mb-2">세금계산서</h3>
          {invoiceNotice && <p className="text-sm text-amber-700">{invoiceNotice}</p>}
          {invoice && (
            <div className="grid grid-cols-2 md:grid-cols-3 gap-x-6 gap-y-1 text-sm text-gray-700">
              <span>발행번호 <b>{invoice.issueNumber}</b></span>
              <span>발행일 {invoice.issueDate}</span>
              <span>셀러 {invoice.sellerId}</span>
              <span>공급가 {money(invoice.supplyAmount)}</span>
              <span>세액 {money(invoice.taxAmount)}</span>
              <span>합계 {money(invoice.totalAmount)}</span>
              <a href={taxApi.invoicePdfUrl(invoice.settlementId)} target="_blank" rel="noreferrer"
                className="text-blue-600 hover:text-blue-800 font-semibold">
                PDF 다운로드
              </a>
            </div>
          )}
        </div>
      )}
    </div>
  );
};

/** 셀러 세무 프로필 — 세무유형이 원천징수 대상 여부를 가른다(개인만 대상). */
const ProfileEditor: React.FC<{ showToast: Toast }> = ({ showToast }) => {
  const [sellerId, setSellerId] = useState('');
  const [profile, setProfile] = useState<SellerTaxProfile | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [taxType, setTaxType] = useState<TaxType>('INDIVIDUAL');
  const [businessRegNo, setBusinessRegNo] = useState('');
  const [busy, setBusy] = useState(false);

  const handleLookup = async () => {
    if (!sellerId.trim()) {
      showToast('셀러 ID 를 입력하세요.', 'warning');
      return;
    }
    setNotice(null);
    try {
      const found = await taxApi.profile(Number(sellerId));
      setProfile(found);
      setTaxType(found.taxType);
    } catch (err) {
      setProfile(null);
      // 404 는 "아직 등록 안 함" 이다 — 신규 등록 흐름으로 이어 준다.
      setNotice(apiErrorStatus(err) === 404
        ? '등록된 세무 프로필이 없습니다. 아래에서 신규 등록할 수 있습니다.'
        : apiErrorMessage(err, '프로필을 불러오지 못했습니다.'));
    }
  };

  const handleSave = async () => {
    if (!sellerId.trim()) {
      showToast('셀러 ID 를 입력하세요.', 'warning');
      return;
    }
    const changingType = profile != null && profile.taxType !== taxType;
    const withholdingLine = taxType === 'INDIVIDUAL'
      ? '개인은 사업소득 원천징수 대상이 됩니다.'
      : '사업자는 원천징수 대상에서 빠집니다.';
    if (!window.confirm(
      `셀러 ${sellerId} 의 세무유형을 ${taxType} 로 저장합니다.\n`
      + `${withholdingLine}\n`
      + `${changingType ? '이후 정산의 원천징수 계산이 달라집니다. ' : ''}진행할까요?`)) return;

    setBusy(true);
    try {
      const saved = await taxApi.upsertProfile(Number(sellerId), taxType, businessRegNo.trim());
      setProfile(saved);
      setNotice(null);
      showToast('세무 프로필 저장 완료', 'success');
    } catch (err) {
      showToast(apiErrorMessage(err, '저장 실패'), 'error');
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="space-y-4">
      <div className="bg-white rounded-xl border border-gray-200 p-4 flex flex-wrap items-end gap-3">
        <input placeholder="셀러 ID" value={sellerId} inputMode="numeric"
          onChange={(e) => setSellerId(e.target.value)} className="input w-40" />
        <button onClick={handleLookup}
          className="px-4 py-2 bg-blue-600 text-white text-sm font-semibold rounded-lg hover:bg-blue-700">
          프로필 조회
        </button>
      </div>

      {notice && <p className="text-sm text-amber-700 bg-amber-50 border border-amber-200 rounded-lg px-3 py-2">{notice}</p>}

      {profile && (
        <div data-testid="tax-profile" className="bg-white rounded-xl border border-gray-200 p-4 text-sm text-gray-700
          grid grid-cols-2 md:grid-cols-4 gap-x-6 gap-y-1">
          <span>셀러 {profile.sellerId}</span>
          <span>세무유형 <b>{profile.taxType}</b></span>
          <span>사업자등록번호 <b>{profile.businessRegNo ?? '-'}</b></span>
          <span>수정 {profile.updatedAt ?? '-'}</span>
        </div>
      )}

      <div className="bg-white rounded-xl border border-gray-200 p-4 space-y-3 max-w-xl">
        <h3 className="font-bold text-gray-900">등록 · 정정</h3>
        <div>
          <label htmlFor="tax-type" className="block text-xs font-medium text-gray-600 mb-1">세무유형</label>
          <select id="tax-type" value={taxType} className="input"
            onChange={(e) => setTaxType(e.target.value as TaxType)}>
            <option value="INDIVIDUAL">INDIVIDUAL</option>
            <option value="BUSINESS">BUSINESS</option>
          </select>
        </div>
        <div>
          <label htmlFor="biz-no" className="block text-xs font-medium text-gray-600 mb-1">
            사업자등록번호 (저장 후에는 마스킹된 값만 조회됩니다)
          </label>
          <input id="biz-no" placeholder="사업자등록번호" value={businessRegNo}
            onChange={(e) => setBusinessRegNo(e.target.value)} className="input font-mono" />
        </div>
        <button onClick={handleSave} disabled={busy}
          className="px-4 py-2 bg-blue-600 text-white text-sm font-semibold rounded-lg hover:bg-blue-700 disabled:opacity-50">
          저장
        </button>
      </div>
    </div>
  );
};

export default TaxConsolePage;
