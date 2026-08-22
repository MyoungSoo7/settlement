import api from './axios';

/**
 * 보험 영업 체인 — insurance-service 가입설계 · 청약 · 계약.
 *
 * <p><b>체인이 하나다.</b> 설계(Proposal) → 전환 → 청약(Application) → 승인 → 계약(Policy).
 * 각 단계의 응답이 다음 단계의 식별자를 준다 — 설계는 {@code applicationId} 를, 승인은
 * {@code policyNumber} 를 돌려준다. 화면이 그 값을 이어 주지 않으면 사람이 옮겨 적어야 한다.
 *
 * <p><b>승인에는 완전판매 게이트가 걸린다.</b> 상품설명서 교부 증빙이 없으면 409
 * ({@code DisclosureNotDeliveredException}), 첨부 서류가 MATCHED 가 아니면 역시 409
 * ({@code ApplicationDocumentNotMatchedException}). 둘은 원인도 조치도 다르므로 화면이 갈라야 한다.
 *
 * <p><b>목록 조회가 없다.</b> 세 컨트롤러 모두 단건 조회뿐이라 식별자를 직접 받는다.
 * 다만 체인을 이어 주면 대부분의 경우 손으로 넣을 일이 없다.
 *
 * <p>요청자(fcId)는 어느 요청에도 싣지 않는다 — 서버가 JWT 주체에서만 파생한다(IDOR 차단).
 */

export type Gender = 'M' | 'F';
export type SalesChannel = 'FC' | 'BANCA';
export type ApplicationStatus = 'SUBMITTED' | 'UNDER_REVIEW' | 'APPROVED' | 'REJECTED';
export type PolicyStatus = 'ACTIVE' | 'LAPSED' | 'SURRENDERED' | 'EXPIRED' | 'CANCELLED';

export interface ProposalSummary {
  proposalId: string;
  productCode: string;
  insuredName: string;
  /** 보험나이 — 생년월일로 서버가 산정한다(생년월일 자체는 저장하지 않는다, PII 최소화). */
  insuranceAge: number;
  coverageAmount: number;
  paymentTermYears: number;
  appliedRatePerMille: number;
  annualPremium: number;
  status: string;
  quotedOn: string;
  /** 이 날짜가 지나면 전환이 409 로 막힌다 — 화면이 남은 기한을 보여 줘야 하는 이유. */
  validUntil: string;
  convertedApplicationId: string | null;
}

export interface CreateProposalInput {
  consultationId?: string;
  productCode: string;
  insuredName: string;
  insuredBirthDate: string;
  insuredGender: Gender;
  coverageAmount: number;
  paymentTermYears: number;
  salesChannel: SalesChannel;
  /** BANCA 설계 시 필수 — 도메인이 강제한다. */
  partnerBankCode?: string;
}

export interface ConversionResult {
  proposalId: string;
  applicationId: string;
  annualPremium: number;
}

export interface IssuedPolicy {
  applicationId: string;
  policyId: string;
  policyNumber: string;
  firstYearCommissionTotal: number;
  installmentCount: number;
}

export interface GeneralPayout {
  payoutId: string;
  payoutType: string;
  amount: number;
  status: string;
  requestedOn: string;
  paidOn: string | null;
  paidPremiumTotal: number;
  appliedRate: number;
  elapsedMonths: number;
  installmentCount: number;
}

export interface PolicyTermination {
  policyNumber: string;
  status: PolicyStatus;
  payout: GeneralPayout | null;
}

export interface SubmitApplicationInput {
  consultationId?: string;
  productCode: string;
  insuredName: string;
  contractorName: string;
  insuredRrn?: string;
  contractorPhone?: string;
  desiredCoverage: number;
  desiredPremium: number;
  salesChannel: SalesChannel;
  partnerBankCode?: string;
}

/**
 * 승인이 게이트에 막혔다 — 실패가 아니라 <b>완전판매를 지키는 규칙</b>이다.
 *
 * <p>{@code kind} 로 두 게이트를 가른다: 교부 증빙 없음은 상품설명서 교부 화면으로,
 * 서류 미대사는 증빙 리뷰 큐로 가야 한다. 하나로 뭉개면 운영자가 어디로 갈지 모른다.
 */
export class UnderwritingGateError extends Error {
  readonly kind: 'DISCLOSURE' | 'DOCUMENT' | 'UNKNOWN';
  constructor(kind: UnderwritingGateError['kind'], message: string) {
    super(message);
    this.name = 'UnderwritingGateError';
    this.kind = kind;
  }
}

/** 서버 오류 본문은 {@code {error}} 한 칸이다(세 컨트롤러 공통). */
const messageOf = (err: unknown): string =>
  (err as { response?: { data?: { error?: string } } })?.response?.data?.error ?? '';

const statusOf = (err: unknown): number | undefined =>
  (err as { response?: { status?: number } })?.response?.status;

export const proposalApi = {
  create: async (input: CreateProposalInput): Promise<ProposalSummary> =>
    (await api.post<ProposalSummary>('/api/insurance/proposals', input)).data,

  get: async (proposalId: string): Promise<ProposalSummary> =>
    (await api.get<ProposalSummary>(`/api/insurance/proposals/${encodeURIComponent(proposalId)}`)).data,

  /** 청약 전환 — 금액은 서버가 설계에서 주입한다(화면이 보낼 자리가 없다). 만기면 409. */
  convert: async (proposalId: string, contractorName: string,
    extra: { insuredRrn?: string; contractorPhone?: string } = {}): Promise<ConversionResult> =>
    (await api.post<ConversionResult>(
      `/api/insurance/proposals/${encodeURIComponent(proposalId)}/convert`,
      { contractorName, ...extra })).data,

  /** 가입설계서 PDF — 피보험자·보장금액이 실려 본인 설계만 내려받을 수 있다. */
  sheet: async (proposalId: string): Promise<Blob> =>
    (await api.get<Blob>(`/api/insurance/proposals/${encodeURIComponent(proposalId)}/sheet`,
      { responseType: 'blob' })).data,
};

export const applicationApi = {
  submit: async (input: SubmitApplicationInput): Promise<string> =>
    (await api.post<{ applicationId: string }>('/api/insurance/applications', input)).data.applicationId,

  startReview: async (applicationId: string): Promise<ApplicationStatus> =>
    (await api.post<{ status: ApplicationStatus }>(
      `/api/insurance/applications/${encodeURIComponent(applicationId)}/review`)).data.status,

  /**
   * 승인 — 계약 발행 + 수수료 확정. 완전판매 게이트·서류 대사 게이트를 통과해야 한다.
   * 막히면 {@link UnderwritingGateError} 로 갈라 던진다.
   */
  approve: async (applicationId: string): Promise<IssuedPolicy> => {
    try {
      return (await api.post<IssuedPolicy>(
        `/api/insurance/applications/${encodeURIComponent(applicationId)}/approve`)).data;
    } catch (err) {
      if (statusOf(err) === 409) {
        const message = messageOf(err);
        // 서버는 두 게이트를 각각 다른 예외로 던지지만 상태코드가 같다 — 문구로 가른다.
        const kind = /교부|설명서|disclosure/i.test(message) ? 'DISCLOSURE'
          : /서류|대사|document|MATCH/i.test(message) ? 'DOCUMENT' : 'UNKNOWN';
        throw new UnderwritingGateError(kind, message || '승인 게이트를 통과하지 못했습니다.');
      }
      throw err;
    }
  },

  reject: async (applicationId: string, reason: string): Promise<ApplicationStatus> =>
    (await api.post<{ status: ApplicationStatus }>(
      `/api/insurance/applications/${encodeURIComponent(applicationId)}/reject`, { reason })).data.status,
};

export const policyApi = {
  /** 해지(중도 해지) — 해지환급금이 나간다. */
  surrender: async (policyNumber: string): Promise<PolicyTermination> =>
    (await api.post<PolicyTermination>(
      `/api/insurance/policies/${encodeURIComponent(policyNumber)}/surrender`)).data,

  /** 철회(청약 철회) — 납입 보험료를 돌려준다. 해지와 성격이 다르다. */
  cancel: async (policyNumber: string): Promise<PolicyTermination> =>
    (await api.post<PolicyTermination>(
      `/api/insurance/policies/${encodeURIComponent(policyNumber)}/cancel`)).data,

  payouts: async (policyNumber: string): Promise<GeneralPayout[]> =>
    (await api.get<GeneralPayout[]>(
      `/api/insurance/policies/${encodeURIComponent(policyNumber)}/payouts`)).data,
};
