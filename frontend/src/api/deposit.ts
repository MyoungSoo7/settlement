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
