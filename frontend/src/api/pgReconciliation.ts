import api from './axios';

/**
 * PG 정산파일 대사 API — `/admin/pg-reconciliation/**` (ADMIN·MANAGER).
 *
 * <p>흐름: PG 사 CSV 업로드 → 즉시 대사(ROUNDING_DIFF 는 자동 보정, 나머지는 PENDING 큐)
 * → 운영자가 차이별 승인/거절 → 미결 0 건이 되면 마감.
 *
 * <p><b>응답 봉투 주의</b>: 서버가 `{run: {...}}`, `{discrepancy: {...}}` 처럼 한 겹 감싸서
 * 내려준다. 화면이 매번 벗기면 실수가 나므로 이 모듈에서 풀어 평평한 객체로 넘긴다.
 */

export type DiscrepancyType =
  | 'AMOUNT_MISMATCH' | 'MISSING_INTERNAL' | 'MISSING_PG'
  | 'DUPLICATE' | 'ROUNDING_DIFF' | 'FEE_MISMATCH';

export type DiscrepancyStatus = 'PENDING' | 'APPROVED' | 'REJECTED' | 'AUTO_CORRECTED';

export type RunStatus = 'RUNNING' | 'COMPLETED' | 'FAILED' | 'CLOSED';

export const DISCREPANCY_TYPE_LABEL: Record<DiscrepancyType, string> = {
  AMOUNT_MISMATCH: '금액 불일치',
  MISSING_INTERNAL: '내부 누락',
  MISSING_PG: 'PG 누락',
  DUPLICATE: '중복',
  ROUNDING_DIFF: '반올림 차이',
  FEE_MISMATCH: '수수료 불일치',
};

export interface ReconciliationRun {
  id: number;
  pgProvider: string;
  targetDate: string;
  fileName: string | null;
  status: RunStatus;
  startedAt: string | null;
  finishedAt: string | null;
  totalPgRows: number;
  totalInternalRows: number;
  matchedCount: number;
  discrepancyCount: number;
  autoCorrectedCount: number;
  operatorId: string | null;
  closed: boolean;
  closedBy: string | null;
  closedAt: string | null;
}

export interface Discrepancy {
  id: number;
  runId: number;
  type: DiscrepancyType;
  paymentId: number | null;
  pgTransactionId: string | null;
  internalAmount: string | null;
  pgAmount: string | null;
  difference: string | null;
  status: DiscrepancyStatus;
  resolvedAt: string | null;
  resolvedBy: string | null;
  note: string | null;
}

export interface RunDetail {
  run: ReconciliationRun;
  discrepancies: Discrepancy[];
}

/** 승인 시 셀러에게서 회수될 금액 미리보기 — 상태를 바꾸지 않는 조회다. */
export interface ClawbackImpact {
  runId: number;
  clawbackCount: number;
  totalClawbackAmount: string;
  /** 승인해도 돈이 움직이지 않는 차이 건수 — "안 움직인다"는 사실 자체가 판단 재료다. */
  noImpactCount: number;
  lines: { discrepancyId: number; paymentId: number | null; type: string; clawbackAmount: string }[];
}

interface RunEnvelope { run: ReconciliationRun }
interface DiscrepancyEnvelope { discrepancy: Discrepancy }
interface RunDetailEnvelope { run: ReconciliationRun; discrepancies: DiscrepancyEnvelope[] }

export const pgReconciliationApi = {
  /** 최근 대사 실행 목록 */
  runs: async (limit = 20): Promise<ReconciliationRun[]> =>
    (await api.get<RunEnvelope[]>('/admin/pg-reconciliation/runs', { params: { limit } }))
      .data.map((e) => e.run),

  /** 실행 상세 + 차이 목록 */
  runDetail: async (runId: number): Promise<RunDetail> => {
    const { data } = await api.get<RunDetailEnvelope>(`/admin/pg-reconciliation/runs/${runId}`);
    return { run: data.run, discrepancies: data.discrepancies.map((e) => e.discrepancy) };
  },

  /** 회수 영향 미리보기 (읽기 전용) */
  clawbackPreview: async (runId: number): Promise<ClawbackImpact> =>
    (await api.get<ClawbackImpact>(`/admin/pg-reconciliation/runs/${runId}/clawback-preview`)).data,

  /** CSV 업로드 + 즉시 대사 */
  upload: async (provider: string, targetDate: string, file: File): Promise<ReconciliationRun> => {
    const form = new FormData();
    form.append('provider', provider);
    form.append('targetDate', targetDate);
    form.append('file', file);
    const { data } = await api.post<RunEnvelope>('/admin/pg-reconciliation/files', form, {
      headers: { 'Content-Type': 'multipart/form-data' },
    });
    return data.run;
  },

  /** 차이 승인 — 역정산(SettlementAdjustment)이 뒤따른다 */
  approve: async (discrepancyId: number, note: string): Promise<Discrepancy> =>
    (await api.post<DiscrepancyEnvelope>(
      `/admin/pg-reconciliation/discrepancies/${discrepancyId}/approve`, { note })).data.discrepancy,

  /** 차이 거절 — 사유 필수 */
  reject: async (discrepancyId: number, note: string): Promise<Discrepancy> =>
    (await api.post<DiscrepancyEnvelope>(
      `/admin/pg-reconciliation/discrepancies/${discrepancyId}/reject`, { note })).data.discrepancy,

  /** 대사 마감 — 미결 0 건일 때만. CLOSED 는 종착 상태로 재개방 경로가 없다. */
  close: async (runId: number, note?: string): Promise<ReconciliationRun> =>
    (await api.post<RunEnvelope>(`/admin/pg-reconciliation/runs/${runId}/close`, { note })).data.run,
};
