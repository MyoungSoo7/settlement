import { isAxiosError } from 'axios';
import api from './axios';

/**
 * 셀러 예치금 조회 — deposit-service {@code DepositController} (`/api/deposits`, 게이트웨이 8112).
 *
 * <p>본인 조회에 셀러 식별자를 <b>보내지 않는다</b>. 서버가 JWT 주체에서만 sellerId 를 파생하므로
 * (`/accounts/me`), 화면이 식별자를 실어 보낼 자리가 애초에 없다 — 그게 IDOR 방어의 형태다.
 *
 * <p>잔고를 {@code available}/{@code locked} 로 나눠 받는다. 합계 하나만 보면 "잔고는 있는데 왜
 * 못 쓰지"에 답할 수 없다 — locked 는 카드 승인 등으로 이미 선점된 금액이다.
 * 도메인 불변식은 {@code total = available + locked}.
 */

export interface DepositAccount {
  id: number;
  sellerId: number;
  /** 즉시 사용 가능. */
  available: number;
  /** 승인 홀드 등으로 선점됨 — 잔고에는 있으나 쓸 수 없다. */
  locked: number;
  total: number;
  createdAt: string;
  updatedAt: string;
}

export const depositApi = {
  /**
   * 내 예치 계좌. **계좌가 없으면 `null`** 이다.
   *
   * <p>서버는 이 경우 404 를 준다 — 0원 계좌를 지어내 200 으로 돌려주지 않는 것이 서버의 선택이고,
   * 화면도 그 구분을 지운다면 서버가 애써 나눠 준 "열린 적 없다"와 "잔고가 0이다"가 다시 뭉개진다.
   * 그래서 404 만 null 로 접고, 그 외 오류(401·5xx)는 그대로 던져 호출부가 오류로 처리하게 둔다.
   */
  myAccount: async (): Promise<DepositAccount | null> => {
    try {
      return (await api.get<DepositAccount>('/api/deposits/accounts/me')).data;
    } catch (err) {
      if (isAxiosError(err) && err.response?.status === 404) return null;
      throw err;
    }
  },

  /** 특정 셀러의 예치 계좌 (ADMIN·MANAGER 전용 — 서버가 게이트한다). 없으면 `null`. */
  accountOf: async (sellerId: number): Promise<DepositAccount | null> => {
    try {
      return (await api.get<DepositAccount>(`/api/deposits/accounts/${sellerId}`)).data;
    } catch (err) {
      if (isAxiosError(err) && err.response?.status === 404) return null;
      throw err;
    }
  },
};

/**
 * 예치금 운영 콘솔 API — `/admin/deposits/**` (**ADMIN 전용**, 서버가 hasRole 로 잠근다).
 *
 * <p>이 콘솔이 있는 이유는 자동 경로가 반쪽이기 때문이다. 입금·출금은 settlement.confirmed /
 * payout.completed 컨슈머가 자동 처리하지만, hold·offset 의 자동 트리거인 card 이벤트에는
 * {@code sellerId} 가 없어 대상 계좌를 특정할 수 없다 — 계약을 고치기 전까지 <b>수기 콘솔이
 * 유일한 입력 경로</b>다.
 *
 * <p>부족분(shortfall)은 더 심하다: 도메인 주석이 "해소 주체가 아직 없다"고 적고 있다.
 * {@code resolve}/{@code writeOff} 는 프로덕션 호출자가 0건이고 OPEN 건을 도는 스케줄러도 없다.
 * 즉 이 API 를 부르는 화면이 없으면 부족분은 <b>영원히 쌓이기만 한다</b>.
 */

export type DepositHolderType = 'CARD_AUTHORIZATION' | 'LOAN_DISBURSEMENT' | 'INVESTMENT_EXECUTION';
export type DepositShortfallStatus = 'OPEN' | 'RESOLVED' | 'WRITTEN_OFF';

/**
 * 수기 입금·출금 입력.
 *
 * <p>{@code referenceId}·{@code referenceType} 은 편의 필드가 아니라 <b>원장 L3 멱등 키</b>다
 * (UNIQUE(account_id, entry_type, reference_type, reference_id, offset_sequence)).
 * 값을 매번 새로 지어내면 중복 방어가 통째로 사라진다 — 같은 조작에는 같은 값을 쓴다.
 */
export interface DepositEntryInput {
  amount: number;
  referenceId: string;
  referenceType: string;
}

export interface PlaceHoldInput {
  holderType: DepositHolderType;
  /** (holderType, holderReference) 가 hold 의 자연키이자 멱등 키다. */
  holderReference: string;
  amount: number;
  /** 생략하면 도메인 기본 72시간. 무기한 선점은 만들 수 없다. */
  expiresAt?: string;
}

export interface ApplyOffsetInput {
  holderType: DepositHolderType;
  holderReference: string;
  offsetAmount: number;
  /** 한 hold 에 대한 분할 상계 회차. 같은 번호로 다시 보내면 DB 가 중복으로 막는다. */
  offsetSequence: number;
  occurredAt?: string;
}

export interface DepositHold {
  id: number;
  accountId: number;
  holderType: DepositHolderType;
  holderReference: string;
  originalAmount: number;
  remainingAmount: number;
  status: string;
  expiresAt: string | null;
}

/** 요청액·적용액·부족분을 셋 다 받는다 — 부족분만으로는 어느 건이 급한지 판단할 수 없다. */
export interface DepositShortfall {
  id: number;
  sellerId: number;
  holderType: DepositHolderType;
  holderReference: string;
  requestedAmount: number;
  appliedAmount: number;
  shortfallAmount: number;
  status: DepositShortfallStatus;
  sourceHoldId: number | null;
  occurredAt: string;
}

export const depositAdminApi = {
  /** 수기 입금 — 계좌가 없으면 만들어진다. */
  credit: async (sellerId: number, input: DepositEntryInput): Promise<void> => {
    await api.post(`/admin/deposits/accounts/${sellerId}/credits`, input);
  },

  /** 수기 출금 — available 이 모자라면 서버가 거절한다(마이너스 잔고를 만들지 않는다). */
  debit: async (sellerId: number, input: DepositEntryInput): Promise<void> => {
    await api.post(`/admin/deposits/accounts/${sellerId}/debits`, input);
  },

  /** 수기 선점 — 같은 (holderType, holderReference) 면 기존 hold 가 그대로 돌아온다. */
  placeHold: async (sellerId: number, input: PlaceHoldInput): Promise<DepositHold> =>
    (await api.post<DepositHold>(`/admin/deposits/accounts/${sellerId}/holds`, input)).data,

  /**
   * 수기 상계. <b>잔고가 모자라도 실패가 아니다</b> — 부족분이 shortfall 로 기록되고 202 가 온다.
   * 그래서 호출부는 "성공했다"가 아니라 "처리됐다"로 읽고 부족분 목록을 다시 봐야 한다.
   */
  applyOffset: async (sellerId: number, input: ApplyOffsetInput): Promise<void> => {
    await api.post(`/admin/deposits/accounts/${sellerId}/offsets`, input);
  },

  /** 미해소 부족분 전체. 비어 있지 않다는 사실 자체가 후속 조치를 요구한다. */
  openShortfalls: async (): Promise<DepositShortfall[]> =>
    (await api.get<DepositShortfall[]>('/admin/deposits/shortfalls')).data,

  /**
   * 가용 잔고로 부족분을 덮는다 — <b>실제로 차감한다</b>(상태만 바꾸는 것이 아니다).
   * 가용액이 모자라면 409 가 나가고 아무것도 바뀌지 않는다. 부분 해소는 지원하지 않는다.
   */
  resolveShortfall: async (shortfallId: number): Promise<number> =>
    (await api.post<{ appliedAmount: number }>(
      `/admin/deposits/shortfalls/${shortfallId}/resolve`)).data.appliedAmount,

  /** 회수 포기·상각. 잔고는 건드리지 않는다. <b>되돌리는 경로가 없다</b>(종단 상태). */
  writeOffShortfall: async (shortfallId: number): Promise<void> => {
    await api.post(`/admin/deposits/shortfalls/${shortfallId}/write-off`);
  },
};
