import React, { useCallback, useEffect, useState } from 'react';
import {
  reviewQueueApi,
  type CardReceiptItem,
  type DepositProofItem,
  type InsuranceDocumentItem,
  type LoanCollateralDocumentItem,
  type ReviewPayload,
} from '@/api/reviewQueue';
import { apiErrorMessage } from '@/lib/apiError';
import { useToast } from '@/contexts/useToast';
import Spinner from '@/components/Spinner';

/**
 * 증빙 OCR 리뷰 큐 (ADR 0036) — 4개 서비스의 NEEDS_REVIEW 증빙을 한 화면에서 육안 종결한다.
 *
 * 리뷰 확정(MATCHED)은 각 서비스의 승인/기표 게이트를 여는 운영 판단이므로, 판정 근거(note)를
 * 반드시 입력해야 버튼이 활성화된다(deposit 은 서버도 @NotBlank 로 강제한다).
 */

type TabKey = 'card' | 'insurance' | 'loan' | 'deposit';

interface QueueRow {
  id: number;
  anchor: string;        // 참조 식별자 (보고서/청약/대출/기표 참조)
  subject: string;       // 참고 명칭 (상호·계약자·소유자·입금자)
  amount: string;        // 핵심 금액 (십진 문자열)
  docDate: string;       // 서류 일자
  confidence: string;
  matchNote: string;
  fileName: string;
  createdAt: string;
}

const TABS: Array<{ key: TabKey; label: string }> = [
  { key: 'card', label: '카드 영수증' },
  { key: 'insurance', label: '보험 청약서류' },
  { key: 'loan', label: '대출 담보서류' },
  { key: 'deposit', label: '예치금 증빙' },
];

const dash = (v: string | null | undefined): string => (v == null || v === '' ? '—' : v);

const toRows = {
  card: (items: CardReceiptItem[]): QueueRow[] =>
    items.map((r) => ({
      id: r.id, anchor: r.reportId, subject: dash(r.merchantName), amount: r.totalAmount,
      docDate: dash(r.transactionDate), confidence: r.confidence, matchNote: dash(r.matchNote),
      fileName: r.fileName, createdAt: r.createdAt,
    })),
  insurance: (items: InsuranceDocumentItem[]): QueueRow[] =>
    items.map((d) => ({
      id: d.id, anchor: d.applicationId, subject: dash(d.contractorName), amount: d.annualPremium,
      docDate: dash(d.applicationDate), confidence: d.confidence, matchNote: dash(d.matchNote),
      fileName: d.fileName, createdAt: d.createdAt,
    })),
  loan: (items: LoanCollateralDocumentItem[]): QueueRow[] =>
    items.map((d) => ({
      id: d.id, anchor: `loan#${d.securedLoanId}`, subject: dash(d.ownerName), amount: d.appraisedValue,
      docDate: dash(d.appraisalDate), confidence: d.confidence, matchNote: dash(d.matchNote),
      fileName: d.fileName, createdAt: d.createdAt,
    })),
  deposit: (items: DepositProofItem[]): QueueRow[] =>
    items.map((p) => ({
      id: p.id, anchor: `${p.referenceType}/${p.referenceId}`, subject: dash(p.senderName),
      amount: p.transferAmount, docDate: dash(p.transferDate), confidence: p.confidence,
      matchNote: dash(p.matchNote), fileName: p.fileName, createdAt: p.createdAt,
    })),
};

const listOf = (tab: TabKey): Promise<QueueRow[]> => {
  switch (tab) {
    case 'card': return reviewQueueApi.listCardReceipts().then(toRows.card);
    case 'insurance': return reviewQueueApi.listInsuranceDocuments().then(toRows.insurance);
    case 'loan': return reviewQueueApi.listLoanCollateralDocuments().then(toRows.loan);
    case 'deposit': return reviewQueueApi.listDepositProofs().then(toRows.deposit);
  }
};

const reviewOf = (tab: TabKey, id: number, payload: ReviewPayload): Promise<unknown> => {
  switch (tab) {
    case 'card': return reviewQueueApi.reviewCardReceipt(id, payload);
    case 'insurance': return reviewQueueApi.reviewInsuranceDocument(id, payload);
    case 'loan': return reviewQueueApi.reviewLoanCollateralDocument(id, payload);
    case 'deposit': return reviewQueueApi.reviewDepositProof(id, payload);
  }
};

const ProofReviewQueuePage: React.FC = () => {
  const [tab, setTab] = useState<TabKey>('card');
  const [rows, setRows] = useState<QueueRow[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [notes, setNotes] = useState<Record<number, string>>({});
  const { showToast } = useToast();

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setRows(await listOf(tab));
      setNotes({});
    } catch (err) {
      setError(apiErrorMessage(err, '리뷰 큐를 불러오지 못했습니다.'));
    } finally {
      setLoading(false);
    }
  }, [tab]);

  useEffect(() => { void load(); }, [load]);

  const review = async (id: number, matched: boolean) => {
    const note = (notes[id] ?? '').trim();
    try {
      await reviewOf(tab, id, { matched, note });
      showToast(matched ? '대사 확정(MATCHED) 처리했습니다.' : '반려(MISMATCHED) 처리했습니다.', 'success');
      await load();
    } catch (err) {
      showToast(apiErrorMessage(err, '리뷰 처리에 실패했습니다.'), 'error');
    }
  };

  return (
    <div className="max-w-6xl mx-auto px-4 py-8">
      <h1 className="text-xl font-bold mb-1">증빙 리뷰 큐</h1>
      <p className="text-sm text-gray-500 mb-6">
        OCR 이 확신하지 못한 증빙(NEEDS_REVIEW)을 육안 대조로 종결합니다. 확정은 각 서비스의
        승인·기표 게이트를 여는 판단이므로 근거를 남겨야 합니다.
      </p>

      <div className="flex gap-2 mb-6" role="tablist">
        {TABS.map((t) => (
          <button
            key={t.key}
            role="tab"
            aria-selected={tab === t.key}
            onClick={() => setTab(t.key)}
            className={
              tab === t.key
                ? 'px-4 py-2 rounded-lg bg-gray-900 text-white text-sm font-semibold'
                : 'px-4 py-2 rounded-lg border border-gray-200 text-sm font-semibold text-gray-700 hover:bg-gray-50'
            }
          >
            {t.label}
          </button>
        ))}
      </div>

      {error && (
        <p className="text-sm text-orange-700 bg-orange-50 border border-orange-100 rounded px-3 py-2 mb-6">
          {error}
        </p>
      )}
      {loading && (
        <div className="flex justify-center py-6"><Spinner /></div>
      )}

      {!loading && (
        <div className="bg-white rounded-xl border border-gray-200 overflow-x-auto">
          <table className="w-full text-sm">
            <thead className="bg-gray-50 border-b">
              <tr>
                <th className="px-3 py-2 text-left font-semibold text-gray-600">참조</th>
                <th className="px-3 py-2 text-left font-semibold text-gray-600">이름</th>
                <th className="px-3 py-2 text-right font-semibold text-gray-600">금액</th>
                <th className="px-3 py-2 text-left font-semibold text-gray-600">서류일자</th>
                <th className="px-3 py-2 text-right font-semibold text-gray-600">신뢰도</th>
                <th className="px-3 py-2 text-left font-semibold text-gray-600">판정 근거</th>
                <th className="px-3 py-2 text-left font-semibold text-gray-600">리뷰</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-100">
              {rows.length === 0 && (
                <tr>
                  <td colSpan={7} className="px-3 py-8 text-center text-gray-400">
                    리뷰 대기 증빙이 없습니다.
                  </td>
                </tr>
              )}
              {rows.map((row) => (
                <tr key={row.id} data-testid="proof-review-row">
                  <td className="px-3 py-2 font-mono text-xs">{row.anchor}</td>
                  <td className="px-3 py-2">{row.subject}</td>
                  <td className="px-3 py-2 text-right font-mono">{row.amount}</td>
                  <td className="px-3 py-2">{row.docDate}</td>
                  <td className="px-3 py-2 text-right font-mono">{row.confidence}</td>
                  <td className="px-3 py-2 text-xs text-gray-500">{row.matchNote}</td>
                  <td className="px-3 py-2">
                    <div className="flex items-center gap-2">
                      <input
                        className="input w-44 text-xs"
                        placeholder="육안 대조 근거 (필수)"
                        aria-label={`리뷰 근거 ${row.id}`}
                        value={notes[row.id] ?? ''}
                        onChange={(e) => setNotes((prev) => ({ ...prev, [row.id]: e.target.value }))}
                      />
                      <button
                        disabled={!(notes[row.id] ?? '').trim()}
                        onClick={() => void review(row.id, true)}
                        className="px-2 py-1 rounded border border-gray-200 text-xs font-semibold text-gray-700 hover:bg-gray-50 disabled:opacity-40"
                      >
                        확정
                      </button>
                      <button
                        disabled={!(notes[row.id] ?? '').trim()}
                        onClick={() => void review(row.id, false)}
                        className="px-2 py-1 rounded border border-red-200 text-xs font-semibold text-red-700 hover:bg-red-50 disabled:opacity-40"
                      >
                        반려
                      </button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
};

export default ProofReviewQueuePage;
