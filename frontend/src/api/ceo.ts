import { companyApi, type Article, type Company, type Reputation } from './company';
import { economicsApi, type EconomicIndicator } from './economics';
import { financialApi, type FinancialCompany, type FinancialStatement } from './financial';

export type RiskSeverity = 'high' | 'medium' | 'low';

export interface CeoStatementInput {
  fiscalYear: number;
  fsDivision: string;
  revenue: number | null;
  operatingProfit: number | null;
  netIncome: number | null;
  totalAssets: number | null;
  totalLiabilities: number | null;
  totalEquity: number | null;
  operatingMargin: number | null;
  netMargin: number | null;
  debtRatio: number | null;
  equityRatio: number | null;
  roa: number | null;
  source: string;
}

export interface CeoReputationInput {
  grade: string;
  score: number;
  negativeCount: number;
  negativeByCategory: Record<string, number>;
}

export interface CeoIndicatorInput {
  code: string;
  name: string;
  unit: string;
  latest: { observedDate: string; value: number } | null;
  change: { amount: number; ratePercent: number | null } | null;
}

export interface CeoSummaryCard {
  label: string;
  value: string;
  hint: string;
  tone: 'neutral' | 'good' | 'warning' | 'danger';
}

export interface CeoRisk {
  category: string;
  title: string;
  severity: RiskSeverity;
  evidence: string[];
  interpretation: string;
  action: string;
}

export interface CeoBriefing {
  headline: string;
  summaryCards: CeoSummaryCard[];
  risks: CeoRisk[];
}

export interface CeoInsight {
  company: FinancialCompany;
  companyProfile: Company | null;
  statements: FinancialStatement[];
  latestStatement: FinancialStatement | null;
  reputation: Reputation | null;
  articles: Article[];
  indicators: EconomicIndicator[];
  briefing: CeoBriefing;
}

const pct = (value: number | null | undefined) =>
  value === null || value === undefined ? 'N/A' : `${value.toFixed(2)}%`;

const valueWithUnit = (indicator: CeoIndicatorInput) => {
  if (!indicator.latest) return 'N/A';
  return `${indicator.latest.value.toLocaleString('ko-KR', { maximumFractionDigits: 4 })}${indicator.unit}`;
};

const indicatorBy = (indicators: CeoIndicatorInput[], code: string, fallbackName: string) =>
  indicators.find((indicator) => indicator.code === code || indicator.name.includes(fallbackName));

const addIf = (risks: CeoRisk[], condition: boolean, risk: CeoRisk) => {
  if (condition) risks.push(risk);
};

export const pickLatestStatement = <T extends CeoStatementInput>(statements: T[]): T | null => {
  if (statements.length === 0) return null;
  return [...statements].sort((a, b) => {
    if (b.fiscalYear !== a.fiscalYear) return b.fiscalYear - a.fiscalYear;
    if (a.fsDivision === b.fsDivision) return 0;
    return a.fsDivision === 'CFS' ? -1 : 1;
  })[0];
};

export const buildCeoBriefing = ({
  companyName,
  statement,
  reputation,
  indicators,
}: {
  companyName: string;
  statement: CeoStatementInput | null;
  reputation: CeoReputationInput | null;
  indicators: CeoIndicatorInput[];
}): CeoBriefing => {
  const risks: CeoRisk[] = [];
  const baseRate = indicatorBy(indicators, 'BASE_RATE', '기준금리');
  const exchangeRate = indicatorBy(indicators, 'USD_KRW', 'USD');
  const cpi = indicatorBy(indicators, 'CPI', 'CPI');

  if (statement) {
    addIf(risks, (statement.debtRatio ?? 0) >= 200, {
      category: '재무 안정성',
      title: '부채비율 우선 점검 필요',
      severity: (statement.debtRatio ?? 0) >= 300 ? 'high' : 'medium',
      evidence: [
        `부채비율 ${pct(statement.debtRatio)}`,
        `자기자본비율 ${pct(statement.equityRatio)}`,
        `기준연도 ${statement.fiscalYear} ${statement.fsDivision}`,
      ],
      interpretation: '차입 부담과 금리 민감도가 높을 수 있어 투자, 배당, 운전자본 계획을 보수적으로 점검해야 합니다.',
      action: 'CFO 조직이 차입 만기, 이자비용 민감도, 현금성 자산 커버리지를 함께 확인해야 합니다.',
    });

    addIf(risks, (statement.operatingMargin ?? 100) < 5 || (statement.netMargin ?? 100) < 2, {
      category: '수익성',
      title: '이익률 방어력 확인 필요',
      severity: (statement.netMargin ?? 100) < 1 ? 'high' : 'medium',
      evidence: [
        `영업이익률 ${pct(statement.operatingMargin)}`,
        `순이익률 ${pct(statement.netMargin)}`,
        `ROA ${pct(statement.roa)}`,
      ],
      interpretation: '매출 규모와 별개로 비용 상승이나 금융비용이 순이익을 압박하고 있을 수 있습니다.',
      action: '제품별 마진, 판관비 증가 항목, 금융비용을 분해해 다음 분기 손익 방어 계획을 수립해야 합니다.',
    });
  }

  if (reputation) {
    addIf(risks, ['D', 'E'].includes(reputation.grade) || reputation.negativeCount >= 5, {
      category: '기업 평판',
      title: '대외 평판 리스크 확인 필요',
      severity: reputation.grade === 'E' || reputation.negativeCount >= 8 ? 'high' : 'medium',
      evidence: [
        `평판 등급 ${reputation.grade}`,
        `평판 점수 ${reputation.score}/100`,
        `부정 기사 ${reputation.negativeCount}건`,
      ],
      interpretation: '부정 이슈가 재무 리스크보다 먼저 거래처, 투자자, 금융기관의 의사결정에 영향을 줄 수 있습니다.',
      action: '부정 기사 카테고리별 원인과 공시·IR·고객 커뮤니케이션 필요 여부를 확인해야 합니다.',
    });
  }

  const macroEvidence = [baseRate, exchangeRate, cpi]
    .filter((indicator): indicator is CeoIndicatorInput => Boolean(indicator?.latest))
    .map((indicator) => `${indicator.name} ${valueWithUnit(indicator)}`);
  const macroRising = [baseRate, exchangeRate, cpi].some((indicator) => (indicator?.change?.amount ?? 0) > 0);
  addIf(risks, macroRising, {
    category: '거시 환경',
    title: '외부환경 부담 반영 필요',
    severity: 'medium',
    evidence: macroEvidence.length > 0 ? macroEvidence : ['경제지표 최신값 확인 필요'],
    interpretation: '금리, 환율, 물가 상승은 차입비용, 원가, 수입 결제 조건에 부담을 줄 수 있습니다.',
    action: '재무팀이 환율 민감도, 이자비용, 주요 원재료 가격 전가 가능성을 함께 점검해야 합니다.',
  });

  const sortedRisks = risks.sort((a, b) => {
    const order: Record<RiskSeverity, number> = { high: 0, medium: 1, low: 2 };
    return order[a.severity] - order[b.severity];
  });

  return {
    headline: `${companyName} CEO 관점에서 ${sortedRisks.length}개 확인 과제가 감지되었습니다.`,
    summaryCards: [
      {
        label: '부채비율',
        value: pct(statement?.debtRatio),
        hint: statement ? `${statement.fiscalYear} ${statement.fsDivision}` : '재무제표 없음',
        tone: (statement?.debtRatio ?? 0) >= 200 ? 'danger' : 'neutral',
      },
      {
        label: '영업이익률',
        value: pct(statement?.operatingMargin),
        hint: statement?.source ?? 'N/A',
        tone: (statement?.operatingMargin ?? 100) < 5 ? 'warning' : 'good',
      },
      {
        label: '평판 등급',
        value: reputation?.grade ?? 'N/A',
        hint: reputation ? `${reputation.score}/100` : '평판 미산정',
        tone: reputation && ['D', 'E'].includes(reputation.grade) ? 'danger' : 'neutral',
      },
      {
        label: '경제환경',
        value: baseRate ? valueWithUnit(baseRate) : 'N/A',
        hint: baseRate?.name ?? '기준금리 없음',
        tone: (baseRate?.change?.amount ?? 0) > 0 ? 'warning' : 'neutral',
      },
    ],
    risks: sortedRisks,
  };
};

export const ceoApi = {
  searchCompanies: financialApi.companies,

  insight: async (company: FinancialCompany): Promise<CeoInsight> => {
    const [statements, reputation, articlesPage, indicators] = await Promise.all([
      financialApi.statements(company.stockCode),
      companyApi.reputation(company.stockCode).catch(() => null),
      companyApi.articles(company.stockCode, 0, 5).catch(() => ({ content: [], page: 0, size: 5, totalElements: 0, totalPages: 0 })),
      economicsApi.indicators(),
    ]);
    const companyProfile = await companyApi
      .companies(company.stockCode, 0, 1)
      .then((page) => page.content.find((item) => item.stockCode === company.stockCode) ?? null)
      .catch(() => null);
    const latestStatement = pickLatestStatement(statements);

    return {
      company,
      companyProfile,
      statements,
      latestStatement,
      reputation,
      articles: articlesPage.content,
      indicators,
      briefing: buildCeoBriefing({
        companyName: company.name,
        statement: latestStatement,
        reputation,
        indicators,
      }),
    };
  },
};
