import { isAxiosError } from 'axios';
import api from './axios';

/**
 * 셀러 지급 계좌 — settlement-service {@code SellerBankAccount{Self,Admin}Controller}.
 *
 * <p>이 계좌가 없으면 <b>정산금이 조용히 안 나간다</b>. payout 생성이 실패하는 게 아니라
 * {@code PayoutService} 가 "지급수단 미해석" WARN 만 남기고 건너뛰기 때문에, 지급 콘솔의 실패
 * 목록에도 대기 목록에도 뜨지 않는다. 화면이 없던 동안 이걸 고치는 방법은 DB 직접 수정뿐이었다.
 *
 * <p><b>계좌번호는 되읽을 수 없다.</b> 서버는 저장 시 암호화하고 조회 때는 마스킹({@code ****1234})만
 * 준다. 그래서 이 모듈의 조회 타입({@link SellerBankAccountView})과 저장 타입
 * ({@link SellerBankAccountInput})은 <b>일부러 다른 타입</b>이다 — 같은 타입이면 조회 결과를 그대로
 * 저장에 넘기는 코드가 자연스러워 보이는데, 그러면 계좌번호가 문자 그대로 `****1234` 로 덮인다.
 * 도메인 검증은 공백만 막으므로 서버도 그 값을 받아 준다.
 *
 * <p>본인 조회에 셀러 식별자를 보내지 않는다 — 서버가 JWT 주체에서만 파생한다(IDOR 차단).
 */

/** 조회 결과. `account` 는 <b>마스킹된 값</b>이며 저장에 되돌려 쓸 수 없다. */
export interface SellerBankAccountView {
  sellerId: number;
  bank: string;
  /** `****1234` — 뒤 4자리만. 원문은 서버가 어떤 경로로도 돌려주지 않는다. */
  account: string;
  holder: string;
  updatedAt: string;
}

/** 저장 입력. 계좌번호는 매번 새로 입력받는다(되읽을 수 없으므로 되채울 것이 없다). */
export interface SellerBankAccountInput {
  bankCode: string;
  accountNumber: string;
  accountHolder: string;
}

/** 404 = "등록된 적 없음". 0원 계좌처럼 지어내지 않고 호출부가 구분할 수 있게 null 로 접는다. */
const orNullOn404 = async (
  request: () => Promise<{ data: SellerBankAccountView }>,
): Promise<SellerBankAccountView | null> => {
  try {
    return (await request()).data;
  } catch (err) {
    if (isAxiosError(err) && err.response?.status === 404) return null;
    throw err;
  }
};

export const sellerBankAccountApi = {
  /** 내 지급 계좌. 등록 전이면 `null`. */
  mine: (): Promise<SellerBankAccountView | null> =>
    orNullOn404(() => api.get<SellerBankAccountView>('/api/seller/bank-account')),

  /** 내 지급 계좌 등록·정정(upsert). 셀러 식별자를 실을 자리가 없다 — 서버가 토큰에서 파생한다. */
  saveMine: async (input: SellerBankAccountInput): Promise<SellerBankAccountView> =>
    (await api.put<SellerBankAccountView>('/api/seller/bank-account', input)).data,

  /** 특정 셀러의 지급 계좌 (ADMIN·MANAGER — 서버가 게이트). 없으면 `null`. */
  of: (sellerId: number): Promise<SellerBankAccountView | null> =>
    orNullOn404(() => api.get<SellerBankAccountView>(`/admin/seller-bank-accounts/${sellerId}`)),

  /**
   * 운영자 대행 등록·정정.
   *
   * <p>서버에는 {@code POST /admin/seller-bank-accounts}(본문에 sellerId)도 있지만 쓰지 않는다.
   * 둘 다 같은 upsert 인데, 대상 셀러가 URL 에 드러나는 쪽이 감사 로그·프록시 로그에서 읽기 쉽고
   * 재시도해도 같은 결과다.
   */
  save: async (sellerId: number, input: SellerBankAccountInput): Promise<SellerBankAccountView> =>
    (await api.put<SellerBankAccountView>(`/admin/seller-bank-accounts/${sellerId}`, input)).data,
};
