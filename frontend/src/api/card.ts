import api from './axios';

/**
 * 법인카드 API — `/api/cards/**` (card-service, gateway 라우팅).
 *
 * <p>인증만 요구하는 표면이다. 조직 역할(OWNER/MANAGER/STAFF) 판정은 서버의
 * `CardOrgAuthorizer` 가 멤버십 프로젝션으로 수행한다 — 요청자(userId)는 <b>JWT 주체에서만</b>
 * 파생되므로 이 모듈은 어떤 요청에도 사용자 식별자를 싣지 않는다(IDOR 방어).
 *
 * <p>금액(masterLimit·subLimit 등)은 서버 BigDecimal 이 JSON number 로 온다. 원화 정수 한도라
 * 안전 정수 범위 안이지만, 표시할 때는 `formatDecimal` 을 거쳐 로케일 포맷으로 그린다.
 */

/** 계정 상태 — SCREENING→ACTIVE/REJECTED, ACTIVE→SUSPENDED/DELINQUENT/CLOSED (전이 정본은 도메인) */
export type CardAccountStatus =
  | 'SCREENING' | 'ACTIVE' | 'SUSPENDED' | 'DELINQUENT' | 'CLOSED' | 'REJECTED';

/** 카드 상태 — ISSUED↔SUSPENDED, 둘 다 →CANCELED(종결). CANCELED 만 한도를 반환한다 */
export type CardStatus = 'ISSUED' | 'SUSPENDED' | 'CANCELED';

/**
 * 카드계정. 한도와 함께 산정 근거(재원·인정비율·평판등급)가 온다 —
 * "왜 이 한도인가"에 답하는 것이 여신 화면의 최소 책임이다.
 */
export interface CardAccount {
  id: number;
  organizationId: number;
  sellerId: string;
  status: CardAccountStatus;
  masterLimit: number;
  reputationGrade: string | null;
  sellerPayable: number | null;
  holdbackPayable: number | null;
  appliedRatio: number | null;
  rejectReason: string | null;
}

/** 임직원 카드. 번호는 서버가 이미 마스킹한 값만 보유한다 */
export interface CorporateCard {
  id: number;
  cardAccountId: number;
  holderUserId: number;
  maskedCardNo: string;
  subLimit: number;
  status: CardStatus;
}

export interface IssueCardRequest {
  holderUserId: number;
  subLimit: number;
}

export interface ChangeCardStatusRequest {
  status: CardStatus;
  /** 감사용 필수 사유 — 없으면 서버가 400 으로 끊는다 (최대 200자) */
  reason: string;
}

export const cardApi = {
  /** 카드계정 개설 — 조직만 지정한다. 개설 주체는 JWT 에서 파생(조직 OWNER 검증은 서버 몫) */
  openAccount: async (organizationId: number): Promise<CardAccount> =>
    (await api.post<CardAccount>('/api/cards/accounts', { organizationId })).data,

  getAccount: async (cardAccountId: number): Promise<CardAccount> =>
    (await api.get<CardAccount>(`/api/cards/accounts/${cardAccountId}`)).data,

  listCards: async (cardAccountId: number): Promise<CorporateCard[]> =>
    (await api.get<CorporateCard[]>(`/api/cards/accounts/${cardAccountId}/cards`)).data,

  /** 임직원 카드 발급 — 대상(holderUserId)은 본문, 요청자는 JWT. 출처가 다른 것이 곧 권한 모델 */
  issueCard: async (cardAccountId: number, request: IssueCardRequest): Promise<CorporateCard> =>
    (await api.post<CorporateCard>(`/api/cards/accounts/${cardAccountId}/cards`, request)).data,

  /** 서브한도 변경 — 바꿀 값 하나만 보낸다 (계정은 카드로 이미 결정된다) */
  changeSubLimit: async (cardId: number, subLimit: number): Promise<CorporateCard> =>
    (await api.patch<CorporateCard>(`/api/cards/cards/${cardId}/limit`, { subLimit })).data,

  /** 상태 변경(정지·재개·해지) — 목표 상태를 값으로. 허용 전이의 정본은 도메인 상태머신 */
  changeStatus: async (cardId: number, request: ChangeCardStatusRequest): Promise<CorporateCard> =>
    (await api.patch<CorporateCard>(`/api/cards/cards/${cardId}/status`, request)).data,

  /** 내 카드 — 경로에 사용자 식별자가 없다는 것이 이 엔드포인트의 전부다 */
  myCards: async (): Promise<CorporateCard[]> =>
    (await api.get<CorporateCard[]>('/api/cards/cards/me')).data,
};
