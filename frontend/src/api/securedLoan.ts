import { isAxiosError } from 'axios';
import api from './axios';
import { newIdempotencyKey } from './payout';

/**
 * 담보대출 조회 + 담보 재평가·실행 — loan-service {@code SecuredLoanController}·{@code CollateralController}.
 *
 * <p><b>배경</b>: 담보 재평가(마진콜 140%·청산 120%)와 실행(처분·대위변제)은 서비스·정책·단위테스트가
 * 모두 있었는데 어떤 어댑터도 호출하지 않아 <b>담보 가치가 반토막 나도 아무 일이 없었다</b>.
 * REST 어댑터가 그 구멍을 메웠지만(역산 PRD §10-C) 부르는 화면이 없어 여전히 사람이 손댈 수 없었다.
 *
 * <p><b>목록이 없다.</b> {@code GET /loans/secured} 는 <b>호출자 본인의 대출만</b> 준다 —
 * 운영자도 대상 차주를 지정할 수 없다(서버 주석이 명시). 그래서 운영 콘솔은 대출번호를 직접 받아
 * 상세({@code GET /loans/secured/{id}}, 운영자 허용)로 확인하는 수밖에 없다.
 *
 * <p><b>멱등</b>: 처분·대위변제는 전표와 상각을 남기는 1회성 조작인데, 서버는 {@code Idempotency-Key}
 * 가 <b>없으면 중복 방어를 적용하지 않는다</b>(하위호환). 즉 키를 안 보내면 더블클릭이 그대로 두 번
 * 집행된다 — 이 모듈은 항상 키를 붙인다. 같은 조작의 재시도에는 <b>같은 키</b>를 써야 하므로
 * 키 생성은 호출부(조작 1회)가 맡고 여기서는 받기만 한다.
 */

export type CollateralOutcome = 'SUFFICIENT' | 'MARGIN_CALL' | 'LIQUIDATION';

/** 담보 요약. 무담보 상품이면 대출 응답에서 {@code null} 이다. */
export interface CollateralView {
  collateralId: number;
  type: string;
  description: string;
  appraisedValue: number;
  status: string;
}

export interface SecuredLoan {
  loanId: number;
  productType: string;
  borrowerUserId: number;
  borrowerType: string;
  principal: number;
  outstanding: number;
  termMonths: number;
  annualRatePercent: number;
  repaymentMethod: string;
  creditScore: number | null;
  creditGrade: string | null;
  status: string;
  /** 무담보면 null — 재평가·처분·대위변제가 성립하지 않는다. */
  collateral: CollateralView | null;
  createdAt: string;
}

/** 재평가 판정 결과. {@code requiredAmount} 는 MARGIN_CALL 일 때의 추가담보 요구액. */
export interface RevaluationResult {
  loanId: number;
  collateralId: number;
  revaluedValue: number;
  coverageRatio: number;
  outcome: CollateralOutcome;
  requiredAmount: number | null;
}

/** 실행 결과 — 회수·잉여·상각과 종단 상태. */
export interface EnforcementResult {
  loanId: number;
  recovered: number;
  surplus: number;
  writtenOff: number;
  finalStatus: string;
}

/** 평가 출처 — 서버가 문자열로 받는다(MARKET_SERVICE / COMMON_DATA_SERVICE / MANUAL). */
export type RevaluationSource = 'MANUAL' | 'MARKET_SERVICE' | 'COMMON_DATA_SERVICE';

/** 중복 선점(409)을 오류가 아니라 <b>이미 처리됨</b>으로 구분하기 위한 표식. */
export class DuplicateEnforcementError extends Error {
  constructor() {
    super('같은 멱등 키로 이미 처리된 요청입니다. 결과를 다시 조회하세요.');
    this.name = 'DuplicateEnforcementError';
  }
}

const enforce = async <T>(request: () => Promise<{ data: T }>): Promise<T> => {
  try {
    return (await request()).data;
  } catch (err) {
    // 409 는 "실패"가 아니다 — 같은 키의 재제출을 서버가 선점으로 막은 것이고,
    // 원 요청은 성공했을 수 있다. 실패로 뭉개면 운영자가 다시 집행하려 든다.
    if (isAxiosError(err) && err.response?.status === 409) throw new DuplicateEnforcementError();
    throw err;
  }
};

const withKey = (key: string) => ({ headers: { 'Idempotency-Key': key } });

export { newIdempotencyKey };

export const securedLoanApi = {
  /** 대출 상세 (본인 또는 운영자). 없으면 `null`. */
  detail: async (loanId: number): Promise<SecuredLoan | null> => {
    try {
      return (await api.get<SecuredLoan>(`/loans/secured/${loanId}`)).data;
    } catch (err) {
      if (isAxiosError(err) && err.response?.status === 404) return null;
      throw err;
    }
  },

  /**
   * 담보 재평가 — <b>조회가 아니라 판정을 동반하는 조작</b>이다.
   * 140% 미달이면 마진콜(추가담보 요구), 120% 미달이면 청산 이관까지 서버가 진행한다.
   */
  revalue: async (loanId: number, revaluedValue: number, source: RevaluationSource):
  Promise<RevaluationResult> =>
    (await api.post<RevaluationResult>(
      `/loans/secured/${loanId}/collateral/revalue`, { revaluedValue, source })).data,

  /** 담보 처분 — 매각대금으로 회수하고 부족분은 상각한다. 되돌리는 경로가 없다. */
  dispose: (loanId: number, proceeds: number, idempotencyKey: string): Promise<EnforcementResult> =>
    enforce(() => api.post<EnforcementResult>(
      `/loans/secured/${loanId}/collateral/dispose`, { proceeds }, withKey(idempotencyKey))),

  /**
   * 보증기관 대위변제 청구 — 회수액은 보증비율(85%)만큼이고 미보증분은 상각된다.
   * <b>보증부라도 손실이 0 이 아니다.</b>
   */
  subrogate: (loanId: number, idempotencyKey: string): Promise<EnforcementResult> =>
    enforce(() => api.post<EnforcementResult>(
      `/loans/secured/${loanId}/collateral/subrogate`, null, withKey(idempotencyKey))),
};
