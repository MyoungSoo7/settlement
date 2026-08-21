import api from './axios';

/**
 * 수신 상품 3종 — account-service {@code /api/banking/**} (정기예금·적금·퇴직연금).
 *
 * <p>세 상품 모두 <b>계약 주체가 가입자 본인</b>이라 목록 조회에 식별자를 싣지 않는다. 서버가
 * JWT 주체에서 파생하고 소유권도 각 서비스가 판정한다(IDOR 방지).
 *
 * <p><b>두 경로만 운영자 전용이다.</b> 운용수익 인식({@code interest-settlements})과 수급 지급
 * ({@code benefit-payments})은 "기관이 돈을 인식·지급하는" 행위라 SecurityConfig 가 ADMIN·MANAGER 로
 * 막는다 — 가입자에게 열어두면 임의 증액이 된다. 나머지는 authenticated.
 *
 * <p>이 경로들은 2026-08-22 에야 게이트웨이로 열렸다. 그전에는 화면을 만들어도 404 였다.
 */

// ── 공통 ────────────────────────────────────────────────────────────────────
export type Compounding = 'SIMPLE' | 'MONTHLY_COMPOUND';

// ── 정기예금 ────────────────────────────────────────────────────────────────
export type TimeDepositStatus = 'ACTIVE' | 'CLOSED';

export interface TimeDeposit {
  id: number;
  depositorId: string;
  productName: string;
  principal: number;
  annualRate: number;
  /** 중도해지 시 적용되는 낮은 이율 — 만기 이율과 다르다는 사실이 화면에 있어야 한다. */
  earlyTerminationRate: number;
  compounding: Compounding;
  termMonths: number;
  openedOn: string;
  maturityDate: string;
  status: TimeDepositStatus;
  closedOn: string | null;
  settledInterest: number | null;
  payoutAmount: number | null;
}

export interface OpenTimeDepositInput {
  productName: string;
  principal: number;
  annualRate: number;
  earlyTerminationRate: number;
  compounding: Compounding;
  termMonths: number;
}

// ── 적금 ────────────────────────────────────────────────────────────────────
export type SavingsType = 'FIXED' | 'FLEXIBLE';
export type SavingsStatus = 'ACTIVE' | 'CLOSED';

export interface SavingsInstallment {
  round: number;
  amount: number;
  paidOn: string;
}

export interface InstallmentSavings {
  id: number;
  depositorId: string;
  productName: string;
  savingsType: SavingsType;
  monthlyAmount: number;
  paymentLimit: number | null;
  annualRate: number;
  earlyTerminationRate: number;
  termMonths: number;
  openedOn: string;
  maturityDate: string;
  status: SavingsStatus;
  closedOn: string | null;
  totalPaidAmount: number;
  settledInterest: number | null;
  payoutAmount: number | null;
  installments: SavingsInstallment[];
}

export interface OpenInstallmentSavingsInput {
  productName: string;
  savingsType: SavingsType;
  monthlyAmount: number;
  paymentLimit?: number;
  annualRate: number;
  earlyTerminationRate: number;
  termMonths: number;
}

// ── 퇴직연금 ────────────────────────────────────────────────────────────────
export type PensionScheme = 'DB' | 'DC' | 'IRP';
export type PensionStatus = 'ACCUMULATING' | 'RECEIVING' | 'CLOSED';
export type BenefitType = 'ANNUITY' | 'LUMP_SUM';
export type ContributionSource = 'EMPLOYER' | 'EMPLOYEE';
export type MidWithdrawalReason =
  | 'HOMELESS_HOUSE_PURCHASE' | 'LONG_TERM_CARE_6_MONTHS' | 'BANKRUPTCY'
  | 'PERSONAL_REHABILITATION' | 'NATURAL_DISASTER' | 'MINISTER_NOTICE';

/**
 * 제도별 규칙 — 서버 {@code PensionScheme} enum 이 들고 있는 것과 같은 값이다.
 *
 * <p>화면이 이걸 아는 이유는 서버 판정을 대신하려는 게 아니라 <b>불가능한 입력을 미리 막기</b>
 * 위해서다. DB 형은 중도인출이 제도적으로 없고, 납입 주체도 제도마다 다르다.
 */
export const PENSION_RULES: Record<PensionScheme, {
  employerNameRequired: boolean;
  midWithdrawalPermitted: boolean;
  contributionSources: ContributionSource[];
}> = {
  DB: { employerNameRequired: true, midWithdrawalPermitted: false, contributionSources: ['EMPLOYER'] },
  DC: { employerNameRequired: true, midWithdrawalPermitted: true, contributionSources: ['EMPLOYER', 'EMPLOYEE'] },
  IRP: { employerNameRequired: false, midWithdrawalPermitted: true, contributionSources: ['EMPLOYEE'] },
};

/** 급여 개시 요건 — 서버 {@code BenefitType} enum 의 값. */
export const BENEFIT_RULES: Record<BenefitType, { minimumAge: number; minimumSubscribedYears: number }> = {
  ANNUITY: { minimumAge: 55, minimumSubscribedYears: 10 },
  LUMP_SUM: { minimumAge: 55, minimumSubscribedYears: 0 },
};

export interface PensionTransaction {
  seq: number;
  type: string;
  amount: number;
  occurredOn: string;
}

export interface RetirementPension {
  id: number;
  subscriberId: string;
  scheme: PensionScheme;
  employerName: string | null;
  birthDate: string;
  annualRate: number;
  productName: string | null;
  productRate: number | null;
  status: PensionStatus;
  openedOn: string;
  lastInterestSettledOn: string | null;
  benefitStartedOn: string | null;
  benefitType: BenefitType | null;
  accumulatedAmount: number;
  nextSeq: number;
  transactions: PensionTransaction[];
}

export interface OpenPensionInput {
  scheme: PensionScheme;
  employerName?: string;
  birthDate: string;
  annualRate: number;
  productName?: string;
  productRate?: number;
}

// ── API ─────────────────────────────────────────────────────────────────────
export const timeDepositApi = {
  listMine: async (): Promise<TimeDeposit[]> =>
    (await api.get<TimeDeposit[]>('/api/banking/time-deposits')).data,
  open: async (input: OpenTimeDepositInput): Promise<TimeDeposit> =>
    (await api.post<TimeDeposit>('/api/banking/time-deposits', input)).data,
  /** 만기 해지 — 약정 이율로 정산된다. */
  closeOnMaturity: async (id: number): Promise<TimeDeposit> =>
    (await api.post<TimeDeposit>(`/api/banking/time-deposits/${id}/close`)).data,
  /** 중도 해지 — <b>중도해지 이율</b>이 적용된다(약정 이율보다 낮다). */
  closeEarly: async (id: number): Promise<TimeDeposit> =>
    (await api.post<TimeDeposit>(`/api/banking/time-deposits/${id}/close-early`)).data,
};

export const savingsApi = {
  listMine: async (): Promise<InstallmentSavings[]> =>
    (await api.get<InstallmentSavings[]>('/api/banking/savings')).data,
  open: async (input: OpenInstallmentSavingsInput): Promise<InstallmentSavings> =>
    (await api.post<InstallmentSavings>('/api/banking/savings', input)).data,
  /** 회차 납입 — {@code round} 는 몇 회차인지다. 같은 회차 재납입은 서버가 막는다. */
  pay: async (id: number, round: number, amount: number): Promise<InstallmentSavings> =>
    (await api.post<InstallmentSavings>(`/api/banking/savings/${id}/installments`, { round, amount })).data,
  closeOnMaturity: async (id: number): Promise<InstallmentSavings> =>
    (await api.post<InstallmentSavings>(`/api/banking/savings/${id}/close/maturity`)).data,
  closeEarly: async (id: number): Promise<InstallmentSavings> =>
    (await api.post<InstallmentSavings>(`/api/banking/savings/${id}/close/early`)).data,
};

export const pensionApi = {
  listMine: async (): Promise<RetirementPension[]> =>
    (await api.get<RetirementPension[]>('/api/banking/pensions')).data,
  open: async (input: OpenPensionInput): Promise<RetirementPension> =>
    (await api.post<RetirementPension>('/api/banking/pensions', input)).data,
  contribute: async (id: number, amount: number, source: ContributionSource): Promise<RetirementPension> =>
    (await api.post<RetirementPension>(`/api/banking/pensions/${id}/contributions`, { amount, source })).data,
  /** <b>ADMIN·MANAGER 전용</b> — 운용수익 인식(운용사 통지). 금액도 발생일도 서버가 정한다. */
  settleInterest: async (id: number): Promise<RetirementPension> =>
    (await api.post<RetirementPension>(`/api/banking/pensions/${id}/interest-settlements`)).data,
  startBenefit: async (id: number, benefitType: BenefitType): Promise<RetirementPension> =>
    (await api.post<RetirementPension>(`/api/banking/pensions/${id}/benefit`, { benefitType })).data,
  /** <b>ADMIN·MANAGER 전용</b> — 수급 지급 집행. */
  payBenefit: async (id: number, amount: number): Promise<RetirementPension> =>
    (await api.post<RetirementPension>(`/api/banking/pensions/${id}/benefit-payments`, { amount })).data,
  /** 중도인출 — DB 형에는 제도적으로 없다({@link PENSION_RULES}). */
  withdrawMidway: async (id: number, amount: number, reason: MidWithdrawalReason): Promise<RetirementPension> =>
    (await api.post<RetirementPension>(`/api/banking/pensions/${id}/mid-withdrawals`, { amount, reason })).data,
  changeInvestmentInstruction: async (id: number, productName: string, rate: number): Promise<RetirementPension> =>
    (await api.put<RetirementPension>(`/api/banking/pensions/${id}/investment-instruction`,
      { productName, rate })).data,
};
