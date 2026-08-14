import api from './axios';

/**
 * 수수료율 정책 API — `/admin/commission-rates/**` (**ADMIN 전용**, ADR 0032).
 *
 * <p>요율은 정산 금액을 직접 바꾸므로 조회 콘솔과 달리 MANAGER 에게 열지 않는다.
 *
 * <p>변경은 행 UPDATE 가 아니라 <b>close + 신규 등록</b>이다 — 이 테이블 자체가 이력이라
 * 과거 값을 덮으면 "그때 왜 그 요율이었나"를 설명할 수 없다.
 *
 * <p>정책은 <b>미래에만</b> 건다. 이미 정산이 생성된 구간으로 소급 등록하면 400 이며, 그 경우 정식
 * 경로는 역정산(settlement_adjustments)이다. 이미 만들어진 정산의 요율은 스냅샷으로 영구 보존되어
 * 정책을 바꿔도 재계산되지 않는다.
 */

export type RateScope = 'SELLER' | 'TIER';
export type SellerTier = 'NORMAL' | 'VIP' | 'STRATEGIC';

export interface CommissionRatePolicy {
  id: number;
  scope: RateScope;
  /** SELLER 면 셀러 ID, TIER 면 등급명 */
  scopeKey: string;
  /** 소수 표기(0.025 = 2.5%) */
  rate: string;
  effectiveFrom: string;
  effectiveTo: string | null;
  /** 왜 이 요율인가 — 감사 근거 */
  reason: string;
  createdBy: string;
  createdAt: string | null;
  closedAt: string | null;
  closed: boolean;
}

export interface RateSimulation {
  sellerId: number | null;
  tier: string;
  at: string;
  rate: string;
  /** 무엇이 이겼는지: `SELLER:77` · `TIER:VIP` · `DEFAULT_TIER` */
  source: string;
}

export interface RegisterPolicyRequest {
  scope: RateScope;
  scopeKey: string;
  rate: string;
  effectiveFrom: string;
  effectiveTo?: string | null;
  reason: string;
}

/**
 * 퍼센트 표기(`2.5`)를 서버가 받는 소수 문자열(`0.025`)로 바꾼다.
 *
 * <p><b>부동소수 나눗셈을 쓰지 않는다</b> — `3.5 / 100` 은 이진수로 정확히 떨어지지 않아
 * `0.034999...` 같은 값이 만들어지고, 그대로 보내면 요율에 오차가 실린다. 소수점 위치만
 * 문자열로 두 칸 옮겨서 입력한 숫자를 그대로 보존한다.
 *
 * @returns 변환된 소수 문자열. 숫자로 읽을 수 없으면 null.
 */
export function percentToRate(percent: string): string | null {
  const trimmed = percent.trim();
  if (!/^\d*\.?\d+$/.test(trimmed)) return null;

  const [whole, fraction = ''] = trimmed.split('.');
  const digits = whole + fraction;
  // 소수점을 왼쪽으로 두 칸 옮긴다. 자릿수는 건드리지 않는다 — 선행 0 을 지우면 소수점 위치와
  // 어긋나 0.5% 가 0.05 로 커진다(실제로 그렇게 틀렸다).
  const point = whole.length - 2;
  return point <= 0
    ? `0.${'0'.repeat(-point)}${digits}`
    : `${digits.slice(0, point)}.${digits.slice(point)}`;
}

/** 소수 문자열(`0.025`)을 퍼센트 표시용(`2.5`)으로 — 표시 전용이라 반올림해도 안전하다. */
export function rateToPercent(rate: string | null | undefined): string {
  if (rate == null || rate === '') return '-';
  const n = Number(rate);
  return Number.isNaN(n) ? '-' : `${Number((n * 100).toFixed(3))}`;
}

export const commissionRateApi = {
  /** 정책 목록 — 기본은 살아 있는 것만 */
  list: async (includeClosed = false): Promise<CommissionRatePolicy[]> =>
    (await api.get<CommissionRatePolicy[]>('/admin/commission-rates',
      { params: { includeClosed } })).data,

  /** 신규 등록 — 소급 구간이면 400 */
  register: async (body: RegisterPolicyRequest): Promise<CommissionRatePolicy> =>
    (await api.post<CommissionRatePolicy>('/admin/commission-rates', body)).data,

  /** 조기 종료 — 요율 변경은 close + 신규 등록으로 한다 */
  close: async (id: number): Promise<void> => {
    await api.post(`/admin/commission-rates/${id}/close`);
  },

  /** 해석 미리보기 — 이 셀러에게 이 날짜에 어떤 요율이 왜 적용되는가 */
  simulate: async (params: { sellerId?: number; tier?: SellerTier; at?: string }): Promise<RateSimulation> =>
    (await api.get<RateSimulation>('/admin/commission-rates/simulate', { params })).data,
};
