import api from './axios';

/**
 * 일일 대사 API — `GET /admin/reconciliation?date=` (ADMIN·MANAGER).
 *
 * <p>이중장부 대사다: order 쪽 집계(캡처 gross·환불액·건수)와 settlement 쪽 집계
 * (정산 payment_amount·refunded_amount·건수)를 같은 날짜 축에서 맞춰 본다. 세 축 중 하나라도
 * 어긋나면 `matched=false` 이고, 그건 돈이 한쪽 장부에만 있다는 뜻이라 즉시 조사 대상이다.
 */
export interface ReconciliationReport {
  targetDate: string;
  /** order: 그날 캡처 gross (CAPTURED + REFUNDED) */
  capturedPayments: string;
  /** settlement: 그날 생성 정산 payment_amount 합 */
  settlementGross: string;
  /** order: 그날 캡처분에 반영된 환불액 */
  refundedAgainstCaptures: string;
  /** settlement: 그날 생성 정산 refunded_amount 합 */
  settlementRefunded: string;
  /** capturedPayments - settlementGross (0 이어야 함) */
  captureDiscrepancy: string;
  /** refundedAgainstCaptures - settlementRefunded (0 이어야 함) */
  refundDiscrepancy: string;
  /** |캡처차| + |환불차| — 경보 총량 */
  discrepancy: string;
  capturedCount: number;
  settlementCount: number;
  /** capturedCount - settlementCount (0 이어야 함) */
  countDiscrepancy: number;
  matched: boolean;
}

export const reconciliationApi = {
  /** 하루치 대사 실행 */
  run: async (date: string): Promise<ReconciliationReport> =>
    (await api.get<ReconciliationReport>('/admin/reconciliation', { params: { date } })).data,
};

/** 기간 스캔 결과 한 칸 — 실패한 날짜도 자리를 남겨 "안 본 날"과 구분한다. */
export interface ReconciliationScanCell {
  date: string;
  report: ReconciliationReport | null;
  error: string | null;
}

/**
 * 기간 스캔 — 서버가 하루 단위 API 만 제공하므로 클라이언트가 날짜를 돌린다.
 *
 * <p>대사 쿼리는 양쪽 장부를 훑는 무거운 조회라 한꺼번에 다 던지지 않고 묶음으로 나눠 보낸다.
 * 한 날짜가 실패해도 나머지 날짜 판정은 남겨야 하므로 개별 실패를 셀에 담는다.
 */
export const scanReconciliation = async (
  dates: string[],
  batchSize = 5,
): Promise<ReconciliationScanCell[]> => {
  const cells: ReconciliationScanCell[] = [];
  for (let i = 0; i < dates.length; i += batchSize) {
    const batch = dates.slice(i, i + batchSize);
    const settled = await Promise.all(batch.map(async (date): Promise<ReconciliationScanCell> => {
      try {
        return { date, report: await reconciliationApi.run(date), error: null };
      } catch (err) {
        return { date, report: null, error: err instanceof Error ? err.message : '조회 실패' };
      }
    }));
    cells.push(...settled);
  }
  return cells;
};
