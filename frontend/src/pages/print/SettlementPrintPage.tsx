import React, { useEffect, useMemo, useState } from 'react';
import { useParams, useSearchParams } from 'react-router-dom';
import { settlementApi } from '@/api/settlement';
import { authApi } from '@/api/auth';
import { SettlementDetail } from '@/types';
import PrintDocument from '@/components/print/PrintDocument';
import StatusBadge from '@/components/StatusBadge';
import Spinner from '@/components/Spinner';
import { apiErrorMessage } from '@/lib/apiError';
import { PRINT_DOC, readPrintHandoff, type SettlementPrintHandoff } from '@/lib/printHandoff';

/**
 * 정산서 인쇄 화면 (/print/settlement/:id).
 *
 * 금액·상태·일자는 **전부 상세 API 를 다시 조회해서** 그린다. 목록 화면이 들고 있던 값을
 * 그대로 인쇄하면 목록 조회 이후 상태가 바뀐 정산이 옛 숫자로 종이에 박히기 때문이다.
 * 목록에만 있고 상세 API 에 없는 주문자명·상품명만 핸드오프로 받아 참고 표시한다.
 */

const currency = (amount: number): string =>
  new Intl.NumberFormat('ko-KR', { style: 'currency', currency: 'KRW' }).format(amount);

/**
 * 서버의 무시간대 타임스탬프('yyyy-MM-ddTHH:mm:ss')를 로컬 시각으로 바꾼다.
 *
 * settlement_db 의 timestamp 컬럼은 시간대 없이 **UTC 로** 적재된다(DB `now()` 와 `created_at`
 * 이 같은 프레임임을 확인). 그대로 찍으면 종이 위에 '출력일시 02:28'(로컬) 옆에
 * '등록일시 17:23'(UTC)이 나란히 앉아 서로 모순돼 보인다. 형식이 다르면 원문을 그대로 둔다.
 */
const formatDateTime = (value?: string | null): string => {
  if (!value) return '-';
  const matched = /^(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2})(?::(\d{2}))?/.exec(value);
  if (!matched) return value;
  const [, year, month, day, hour, minute, second = '0'] = matched;
  return formatLocalDateTime(
    new Date(Date.UTC(+year, +month - 1, +day, +hour, +minute, +second))
  );
};

/** 출력 시각은 보는 사람의 로컬 시간으로 찍는다(toISOString 은 UTC 라 9시간 어긋난다). */
const formatLocalDateTime = (date: Date): string => {
  const pad = (n: number) => String(n).padStart(2, '0');
  return (
    `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ` +
    `${pad(date.getHours())}:${pad(date.getMinutes())}`
  );
};

/** 출력자 추적용 식별자 — 종이에 남는 값이라 이메일 로컬파트를 마스킹한다. */
const maskEmail = (email: string): string => {
  const at = email.indexOf('@');
  if (at <= 0) return '***';
  const local = email.slice(0, at);
  const head = local.slice(0, Math.min(3, local.length));
  return `${head}${'*'.repeat(Math.max(local.length - head.length, 1))}${email.slice(at)}`;
};

/** 수수료율(표시 전용). 결제금액이 0 이면 산출 불가라 표시하지 않는다. */
const commissionRateLabel = (paymentAmount: number, commission: number): string | null => {
  if (!Number.isFinite(paymentAmount) || paymentAmount <= 0) return null;
  return `${((commission / paymentAmount) * 100).toFixed(2)}%`;
};

/**
 * 종이에 나갈 최소 조건 검증. 프록시 설정이 틀려 SPA HTML 이 200 으로 돌아오는 등
 * 응답이 정산 상세가 아닐 때, 금액 자리에 `NaN` 이 인쇄되는 사고를 막는다.
 */
const isPrintable = (data: unknown): data is SettlementDetail => {
  const d = data as SettlementDetail | null;
  return (
    !!d &&
    typeof d.id === 'number' &&
    Number.isFinite(d.paymentAmount) &&
    Number.isFinite(d.commission) &&
    Number.isFinite(d.netAmount)
  );
};

const Field: React.FC<{ label: string; children: React.ReactNode }> = ({ label, children }) => (
  <div className="flex border-b border-gray-200 py-2 text-sm">
    <span className="w-28 shrink-0 font-medium text-gray-500">{label}</span>
    <span className="font-semibold text-gray-900">{children}</span>
  </div>
);

const SettlementPrintPage: React.FC = () => {
  const { id } = useParams<{ id: string }>();
  const [searchParams] = useSearchParams();
  const settlementId = Number(id);

  const [detail, setDetail] = useState<SettlementDetail | null>(null);
  const [error, setError] = useState<string | null>(null);

  const [handoff] = useState<SettlementPrintHandoff | null>(() =>
    Number.isInteger(settlementId)
      ? readPrintHandoff<SettlementPrintHandoff>(PRINT_DOC.settlement, settlementId)
      : null
  );

  // 출력 일시는 마운트 시점에 한 번 고정한다 — 재렌더마다 초가 바뀌면 안 된다.
  const [printedAt] = useState(() => new Date());
  const operator = useMemo(() => authApi.getCurrentUser(), []);

  useEffect(() => {
    if (!Number.isInteger(settlementId) || settlementId <= 0) {
      setError('정산 번호가 올바르지 않습니다.');
      return;
    }
    let alive = true;
    settlementApi
      .getSettlement(settlementId)
      .then((data) => {
        if (!alive) return;
        if (isPrintable(data)) {
          setDetail(data);
        } else {
          setError('정산 정보를 인쇄할 수 있는 형태로 받지 못했습니다. 잠시 후 다시 시도해 주세요.');
        }
      })
      .catch((err) => {
        if (alive) setError(apiErrorMessage(err, '정산 정보를 불러오지 못했습니다.'));
      });
    return () => {
      alive = false;
    };
  }, [settlementId]);

  const documentNo = `ST-${String(settlementId).padStart(8, '0')}`;
  const autoPrint = searchParams.get('auto') !== '0';
  const rate = detail ? commissionRateLabel(detail.paymentAmount, detail.commission) : null;

  return (
    <PrintDocument documentTitle={`정산서 ${documentNo}`} ready={detail !== null} autoPrint={autoPrint}>
      {error && (
        <div className="rounded-md border border-red-200 bg-red-50 p-4 text-sm text-red-800">{error}</div>
      )}

      {!error && !detail && <Spinner size="lg" message="정산서를 준비하는 중..." />}

      {detail && (
        <article>
          {/* 문서 머리 — 발행 주체와 문서 식별자 */}
          <header className="print-keep flex items-start justify-between border-b-2 border-gray-900 pb-4">
            <div>
              <p className="text-lg font-bold tracking-wide text-gray-900">LEMUEL</p>
              <p className="text-xs text-gray-500">정산 관리 시스템</p>
            </div>
            <div className="text-right text-xs text-gray-600">
              <p>
                문서번호 <span className="font-semibold text-gray-900">{documentNo}</span>
              </p>
              <p>출력일시 {formatLocalDateTime(printedAt)}</p>
            </div>
          </header>

          <h1 className="my-8 text-center text-3xl font-bold tracking-[0.4em] text-gray-900">정 산 서</h1>

          {/* 정산 개요 */}
          <section className="print-keep mb-6">
            <h2 className="mb-2 border-l-4 border-gray-900 pl-2 text-sm font-bold text-gray-900">정산 개요</h2>
            <div className="grid grid-cols-2 gap-x-8">
              <Field label="정산번호">#{detail.id}</Field>
              <Field label="정산상태">
                <StatusBadge status={detail.status} type="settlement" />
              </Field>
              <Field label="주문번호">#{detail.orderId}</Field>
              <Field label="결제번호">#{detail.paymentId}</Field>
              <Field label="정산일">{detail.settlementDate || '-'}</Field>
              <Field label="확정일시">{formatDateTime(detail.confirmedAt)}</Field>
              {handoff?.ordererName && <Field label="주문자">{handoff.ordererName}</Field>}
              {handoff?.productName && <Field label="상품명">{handoff.productName}</Field>}
            </div>
          </section>

          {/* 금액 내역 — 결제금액 − 수수료 = 정산금액 */}
          <section className="mb-6">
            <h2 className="mb-2 border-l-4 border-gray-900 pl-2 text-sm font-bold text-gray-900">
              정산 금액 내역
            </h2>
            <table className="w-full border-collapse text-sm">
              <thead>
                <tr className="bg-gray-100">
                  <th className="border border-gray-300 px-3 py-2 text-left font-semibold text-gray-700">항목</th>
                  <th className="border border-gray-300 px-3 py-2 text-left font-semibold text-gray-700">산출 근거</th>
                  <th className="border border-gray-300 px-3 py-2 text-right font-semibold text-gray-700">금액</th>
                </tr>
              </thead>
              <tbody>
                <tr>
                  <td className="border border-gray-300 px-3 py-2">결제금액</td>
                  <td className="border border-gray-300 px-3 py-2 text-gray-500">결제 승인 금액</td>
                  <td className="border border-gray-300 px-3 py-2 text-right font-semibold">
                    {currency(detail.paymentAmount)}
                  </td>
                </tr>
                <tr>
                  <td className="border border-gray-300 px-3 py-2">수수료</td>
                  <td className="border border-gray-300 px-3 py-2 text-gray-500">
                    {rate ? `결제금액 × ${rate} (정산 시점 요율)` : '정산 시점 요율 적용'}
                  </td>
                  <td className="border border-gray-300 px-3 py-2 text-right font-semibold text-red-600">
                    -{currency(detail.commission)}
                  </td>
                </tr>
              </tbody>
              <tfoot>
                <tr className="bg-gray-50">
                  <td className="border border-gray-300 px-3 py-3 font-bold" colSpan={2}>
                    정산금액 (지급 대상)
                  </td>
                  <td className="border border-gray-300 px-3 py-3 text-right text-lg font-bold text-gray-900">
                    {currency(detail.netAmount)}
                  </td>
                </tr>
              </tfoot>
            </table>
          </section>

          {/* 안내 — 종이만 보고도 문서의 성격을 알 수 있게 */}
          <section className="print-keep mb-8 rounded border border-gray-200 bg-gray-50 p-3 text-xs leading-relaxed text-gray-600">
            <p>· 본 정산서는 정산 시스템에서 자동 생성된 문서로, 별도의 날인 없이 유효합니다.</p>
            <p>· 수수료는 정산 확정 시점의 요율로 산정되며, 이후 요율 변경은 본 건에 소급되지 않습니다.</p>
            <p>· 환불·조정이 발생한 경우 별도의 조정 정산서가 추가 발행됩니다.</p>
          </section>

          <footer className="print-keep flex items-end justify-between border-t border-gray-300 pt-3 text-xs text-gray-500">
            <div>
              <p>출력일시 {formatLocalDateTime(printedAt)}</p>
              <p>출력자 {operator ? maskEmail(operator.email) : '-'}</p>
            </div>
            <p className="text-right">등록일시 {formatDateTime(detail.createdAt)}</p>
          </footer>
        </article>
      )}
    </PrintDocument>
  );
};

export default SettlementPrintPage;
