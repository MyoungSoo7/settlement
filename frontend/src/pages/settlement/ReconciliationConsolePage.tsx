import React, { useCallback, useEffect, useMemo, useState } from 'react';
import {
  reconciliationApi,
  scanReconciliation,
  type ReconciliationReport,
  type ReconciliationScanCell,
} from '@/api/reconciliation';
import { formatDecimal } from '@/lib/decimal';
import { apiErrorMessage } from '@/lib/apiError';
import { useToast } from '@/contexts/useToast';
import Spinner from '@/components/Spinner';

/**
 * 일일 대사 콘솔 — order 장부와 settlement 장부를 같은 날짜 축에서 맞춰 본다.
 *
 * <p>서버 API 는 하루 단위라, 온콜이 "최근 어느 날부터 깨졌나"를 알려면 날짜를 바꿔 가며
 * 여러 번 호출해야 했다. 그 순회를 화면이 대신한다(기간 스캔). 판정(`matched`)은 서버 것을
 * 그대로 쓰고, 화면은 <b>어긋난 축이 어디인지</b>(캡처 금액·환불 금액·건수)를 짚어 주는 데 집중한다.
 */

const money = (v: string | null | undefined) => {
  const formatted = formatDecimal(v);
  return formatted === null ? '-' : `${formatted}원`;
};

const isZero = (v: string | null | undefined) => Number(v ?? 0) === 0;

const daysAgo = (n: number) => new Date(Date.now() - n * 86_400_000).toISOString().slice(0, 10);

/** 스캔 상한 — 대사 쿼리가 무거워 무한정 늘리면 운영 DB 를 때린다. */
const MAX_SCAN_DAYS = 31;

const datesBetween = (from: string, to: string): string[] => {
  const out: string[] = [];
  const start = new Date(`${from}T00:00:00Z`);
  const end = new Date(`${to}T00:00:00Z`);
  for (let d = start; d <= end; d = new Date(d.getTime() + 86_400_000)) {
    out.push(d.toISOString().slice(0, 10));
    if (out.length >= MAX_SCAN_DAYS) break;
  }
  return out;
};

/** 축 하나의 대사 — 양쪽 값과 차이를 나란히 보여 준다. */
const Axis: React.FC<{
  title: string;
  orderLabel: string;
  orderValue: string;
  settlementLabel: string;
  settlementValue: string;
  diff: string;
  broken: boolean;
}> = ({ title, orderLabel, orderValue, settlementLabel, settlementValue, diff, broken }) => (
  <div className={`rounded-lg border p-3 ${broken ? 'border-red-200 bg-red-50' : 'border-gray-200 bg-white'}`}>
    <p className="text-xs font-semibold text-gray-700 mb-2">{title}</p>
    <dl className="space-y-1">
      <div className="flex justify-between text-xs">
        <dt className="text-gray-500">{orderLabel}</dt>
        <dd className="font-mono">{orderValue}</dd>
      </div>
      <div className="flex justify-between text-xs">
        <dt className="text-gray-500">{settlementLabel}</dt>
        <dd className="font-mono">{settlementValue}</dd>
      </div>
      <div className="flex justify-between text-sm pt-1 border-t border-gray-100">
        <dt className="text-gray-500">차이</dt>
        <dd className={`font-mono font-bold ${broken ? 'text-red-700' : 'text-green-700'}`}>{diff}</dd>
      </div>
    </dl>
  </div>
);

const ReconciliationConsolePage: React.FC = () => {
  const { showToast } = useToast();

  const [date, setDate] = useState(daysAgo(1));
  const [report, setReport] = useState<ReconciliationReport | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const [from, setFrom] = useState(daysAgo(7));
  const [to, setTo] = useState(daysAgo(1));
  const [scan, setScan] = useState<ReconciliationScanCell[]>([]);
  const [scanning, setScanning] = useState(false);

  const run = useCallback(async (target: string) => {
    setLoading(true);
    setError(null);
    try {
      setReport(await reconciliationApi.run(target));
      setDate(target);
    } catch (err) {
      setReport(null);
      setError(apiErrorMessage(err, '대사를 실행하지 못했습니다.'));
    } finally {
      setLoading(false);
    }
  }, []);

  const runScan = useCallback(async () => {
    const dates = datesBetween(from, to);
    if (dates.length === 0) {
      showToast('시작일이 종료일보다 뒤입니다.', 'warning');
      return;
    }
    setScanning(true);
    try {
      const cells = await scanReconciliation(dates);
      setScan(cells);
      const broken = cells.filter((c) => c.report && !c.report.matched).length;
      showToast(
        broken > 0 ? `${dates.length}일 중 ${broken}일이 불일치입니다.` : `${dates.length}일 모두 일치합니다.`,
        broken > 0 ? 'warning' : 'success',
      );
    } finally {
      setScanning(false);
    }
  }, [from, to, showToast]);

  useEffect(() => { void run(daysAgo(1)); }, []); // eslint-disable-line react-hooks/exhaustive-deps

  const truncated = useMemo(() => datesBetween(from, to).length >= MAX_SCAN_DAYS, [from, to]);

  return (
    <div>
      <div className="mb-6">
        <h2 className="text-xl font-bold text-gray-900">일일 대사</h2>
        <p className="text-sm text-gray-500 mt-0.5">
          order 장부(캡처·환불)와 settlement 장부(정산 gross·환불)를 같은 날짜 축에서 대조합니다.
          세 축(캡처 금액·환불 금액·건수) 중 하나라도 어긋나면 불일치입니다.
        </p>
      </div>

      {/* ── 단일 날짜 대사 ─────────────────────────────────────────── */}
      <section className="bg-white rounded-xl border border-gray-200 p-4 mb-6">
        <div className="flex flex-wrap items-end justify-between gap-3 mb-4">
          <div className="flex items-end gap-3">
            <label className="block">
              <span className="text-[11px] text-gray-500">기준일</span>
              <input type="date" value={date} onChange={(e) => setDate(e.target.value)} className="input" />
            </label>
            <button
              onClick={() => void run(date)}
              disabled={loading}
              className="px-4 py-2 rounded-lg bg-gray-900 text-white text-sm font-semibold hover:bg-gray-800 disabled:opacity-40"
            >
              {loading ? '대사 중…' : '대사 실행'}
            </button>
          </div>
          {report && (
            <span className={`text-sm px-3 py-1 rounded-full font-semibold ${
              report.matched ? 'bg-green-100 text-green-800' : 'bg-red-100 text-red-800'
            }`}>
              {report.matched ? '일치' : `불일치 · 경보 총량 ${money(report.discrepancy)}`}
            </span>
          )}
        </div>

        {error && <p className="text-sm text-orange-700">{error}</p>}
        {loading && <div className="flex justify-center py-6"><Spinner /></div>}

        {report && (
          <div className="grid grid-cols-1 md:grid-cols-3 gap-3">
            <Axis
              title="캡처 금액"
              orderLabel="order 캡처 gross" orderValue={money(report.capturedPayments)}
              settlementLabel="settlement 정산 gross" settlementValue={money(report.settlementGross)}
              diff={money(report.captureDiscrepancy)} broken={!isZero(report.captureDiscrepancy)}
            />
            <Axis
              title="환불 금액"
              orderLabel="order 환불액" orderValue={money(report.refundedAgainstCaptures)}
              settlementLabel="settlement 환불액" settlementValue={money(report.settlementRefunded)}
              diff={money(report.refundDiscrepancy)} broken={!isZero(report.refundDiscrepancy)}
            />
            <Axis
              title="건수 (INV-9)"
              orderLabel="order 캡처 건수" orderValue={`${report.capturedCount}건`}
              settlementLabel="settlement 정산 건수" settlementValue={`${report.settlementCount}건`}
              diff={`${report.countDiscrepancy}건`} broken={report.countDiscrepancy !== 0}
            />
          </div>
        )}

        {report && !report.matched && (
          <p className="mt-3 text-xs text-red-700 bg-red-50 border border-red-100 rounded px-3 py-2">
            금액 축이 어긋나면 프로젝션 유실·조정 누락을, 건수만 어긋나면 이벤트 소비 지연·중복을 먼저 의심합니다.
            정합성 검증 화면의 프로젝션 diff(INV-12)·원장 완전성(INV-5)과 함께 보세요.
          </p>
        )}
      </section>

      {/* ── 기간 스캔 ──────────────────────────────────────────────── */}
      <section className="bg-white rounded-xl border border-gray-200 p-4">
        <div className="flex flex-wrap items-end gap-3 mb-4">
          <label className="block">
            <span className="text-[11px] text-gray-500">스캔 시작</span>
            <input type="date" value={from} onChange={(e) => setFrom(e.target.value)} className="input" />
          </label>
          <label className="block">
            <span className="text-[11px] text-gray-500">스캔 종료</span>
            <input type="date" value={to} onChange={(e) => setTo(e.target.value)} className="input" />
          </label>
          <button
            onClick={() => void runScan()}
            disabled={scanning}
            className="px-4 py-2 rounded-lg border border-gray-200 text-sm text-gray-700 hover:bg-gray-50 disabled:opacity-40"
          >
            {scanning ? '스캔 중…' : '기간 스캔'}
          </button>
          {truncated && (
            <span className="text-[11px] text-orange-700">
              최대 {MAX_SCAN_DAYS}일까지만 훑습니다 (대사 쿼리가 무거워 상한을 둡니다)
            </span>
          )}
        </div>

        {scan.length > 0 && (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead className="bg-gray-50 border-b border-gray-200">
                <tr>
                  {['날짜', '판정', '캡처 차이', '환불 차이', '건수 차이', ''].map((h) => (
                    <th key={h} className="px-3 py-2 text-left text-xs font-semibold text-gray-500">{h}</th>
                  ))}
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-100">
                {scan.map((cell) => (
                  <tr key={cell.date} className={cell.report && !cell.report.matched ? 'bg-red-50' : ''}>
                    <td className="px-3 py-2 font-mono text-xs">{cell.date}</td>
                    <td className="px-3 py-2">
                      {cell.error ? (
                        <span className="text-xs px-2 py-0.5 rounded-full bg-orange-100 text-orange-700">조회 실패</span>
                      ) : cell.report?.matched ? (
                        <span className="text-xs px-2 py-0.5 rounded-full bg-green-100 text-green-800">일치</span>
                      ) : (
                        <span className="text-xs px-2 py-0.5 rounded-full bg-red-100 text-red-800 font-semibold">불일치</span>
                      )}
                    </td>
                    <td className="px-3 py-2 text-right font-mono text-xs">
                      {cell.report ? money(cell.report.captureDiscrepancy) : '-'}
                    </td>
                    <td className="px-3 py-2 text-right font-mono text-xs">
                      {cell.report ? money(cell.report.refundDiscrepancy) : '-'}
                    </td>
                    <td className="px-3 py-2 text-right font-mono text-xs">
                      {cell.report ? `${cell.report.countDiscrepancy}건` : '-'}
                    </td>
                    <td className="px-3 py-2">
                      <button
                        onClick={() => void run(cell.date)}
                        className="text-xs px-2 py-1 rounded border border-gray-200 text-gray-600 hover:bg-gray-50"
                      >
                        상세
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}

        {scan.length === 0 && !scanning && (
          <p className="text-sm text-gray-400 py-6 text-center">
            기간을 지정해 스캔하면 어느 날부터 어긋났는지 한눈에 볼 수 있습니다.
          </p>
        )}
      </section>
    </div>
  );
};

export default ReconciliationConsolePage;
