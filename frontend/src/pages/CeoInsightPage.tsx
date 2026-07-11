import React, { useEffect, useState } from 'react';
import { ceoApi, type CeoInsight, type CeoRisk, type CeoSummaryCard } from '@/api/ceo';
import { companyApi } from '@/api/company';
import type { FinancialCompany, FinancialCompanyPage } from '@/api/financial';
import Card from '@/components/Card';
import Spinner from '@/components/Spinner';

const fmtAmount = (value: number | null | undefined) => {
  if (value === null || value === undefined) return 'N/A';
  const abs = Math.abs(value);
  if (abs >= 1_0000_0000_0000) {
    return `${(value / 1_0000_0000_0000).toLocaleString('ko-KR', { maximumFractionDigits: 1 })}조`;
  }
  if (abs >= 1_0000_0000) {
    return `${(value / 1_0000_0000).toLocaleString('ko-KR', { maximumFractionDigits: 0 })}억`;
  }
  return value.toLocaleString('ko-KR');
};

const fmtMultiple = (value: number | null | undefined) =>
  value === null || value === undefined ? 'N/A' : `${value.toFixed(1)}배`;

const fmtRate = (value: number | null | undefined) => {
  if (value === null || value === undefined) return '';
  const arrow = value > 0 ? '▲' : value < 0 ? '▼' : '';
  return `${arrow}${Math.abs(value).toFixed(2)}%`;
};

const rateToneClass = (value: number | null | undefined) => {
  if (value === null || value === undefined || value === 0) return 'text-slate-400';
  return value > 0 ? 'text-red-600' : 'text-blue-600';
};

const cardToneClass = (tone: CeoSummaryCard['tone']) => {
  switch (tone) {
    case 'good':
      return 'border-emerald-200 bg-emerald-50 text-emerald-900';
    case 'warning':
      return 'border-amber-200 bg-amber-50 text-amber-900';
    case 'danger':
      return 'border-red-200 bg-red-50 text-red-900';
    default:
      return 'border-slate-200 bg-slate-50 text-slate-900';
  }
};

const severityClass = (severity: CeoRisk['severity']) => {
  switch (severity) {
    case 'high':
      return 'bg-red-100 text-red-800';
    case 'medium':
      return 'bg-amber-100 text-amber-800';
    default:
      return 'bg-slate-100 text-slate-700';
  }
};

const severityLabel = (severity: CeoRisk['severity']) => {
  if (severity === 'high') return '높음';
  if (severity === 'medium') return '중간';
  return '낮음';
};

const fmtBytes = (bytes: number) => {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
};

/** 문서 종류별 아이콘 (contentType 기준) */
const docIcon = (contentType: string) => {
  if (contentType.includes('wordprocessingml')) return '📄';
  if (contentType === 'application/pdf') return '📕';
  if (contentType === 'image/png') return '🖼️';
  return '📝';
};

const CeoInsightPage: React.FC = () => {
  const [keyword, setKeyword] = useState('');
  const [query, setQuery] = useState('');
  const [page, setPage] = useState(0);
  const [companies, setCompanies] = useState<FinancialCompanyPage | null>(null);
  const [loadingList, setLoadingList] = useState(false);
  const [selected, setSelected] = useState<FinancialCompany | null>(null);
  const [insight, setInsight] = useState<CeoInsight | null>(null);
  const [loadingInsight, setLoadingInsight] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    setLoadingList(true);
    setError(null);
    ceoApi
      .searchCompanies(query, page, 10)
      .then((data) => {
        if (!cancelled) setCompanies(data);
      })
      .catch((err: any) => {
        if (!cancelled) setError(err.response?.data?.message || '기업 목록 조회에 실패했습니다.');
      })
      .finally(() => {
        if (!cancelled) setLoadingList(false);
      });
    return () => {
      cancelled = true;
    };
  }, [query, page]);

  const handleSearch = (e: React.FormEvent) => {
    e.preventDefault();
    setPage(0);
    setQuery(keyword);
  };

  const openCompany = async (company: FinancialCompany) => {
    setSelected(company);
    setInsight(null);
    setLoadingInsight(true);
    setError(null);
    try {
      setInsight(await ceoApi.insight(company));
    } catch (err: any) {
      setError(err.response?.data?.message || 'CEO 인사이트 조회에 실패했습니다.');
    } finally {
      setLoadingInsight(false);
    }
  };

  return (
    <div className="min-h-screen bg-slate-50 py-8 px-4 sm:px-6 lg:px-8">
      <div className="max-w-7xl mx-auto space-y-6">
        <section className="flex flex-col gap-3 md:flex-row md:items-end md:justify-between">
          <div>
            <h1 className="text-3xl font-bold text-slate-950">CEO 인사이트</h1>
            <p className="mt-2 text-sm text-slate-600">
              재무제표, 기업 평판, 거시 경제지표를 MSA 공개 API로 조합해 경영진 확인 과제를 제안합니다.
            </p>
          </div>
          <form onSubmit={handleSearch} className="flex w-full gap-2 md:w-[420px]">
            <input
              type="text"
              value={keyword}
              onChange={(e) => setKeyword(e.target.value)}
              placeholder="기업명 또는 종목코드"
              className="min-w-0 flex-1 rounded-md border border-slate-300 px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-slate-500"
            />
            <button type="submit" className="rounded-md bg-slate-900 px-4 py-2 text-sm font-semibold text-white hover:bg-slate-700">
              검색
            </button>
          </form>
        </section>

        {error && <div className="rounded-md border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700">{error}</div>}

        <div className="grid grid-cols-1 gap-6 lg:grid-cols-[360px_1fr]">
          <Card title="기업 선택">
            {loadingList ? (
              <div className="flex justify-center py-10"><Spinner /></div>
            ) : (
              <div className="space-y-2">
                {companies?.content.map((company) => (
                  <button
                    key={company.stockCode}
                    onClick={() => openCompany(company)}
                    className={`w-full rounded-md border px-4 py-3 text-left transition-colors ${
                      selected?.stockCode === company.stockCode
                        ? 'border-slate-900 bg-slate-100'
                        : 'border-slate-200 hover:border-slate-400 hover:bg-slate-50'
                    }`}
                  >
                    <div className="flex items-center justify-between gap-3">
                      <span className="font-semibold text-slate-900">{company.name}</span>
                      <span className="font-mono text-xs text-slate-500">{company.stockCode}</span>
                    </div>
                    <p className="mt-1 text-xs text-slate-500">{company.market}</p>
                  </button>
                ))}
                {companies && companies.content.length === 0 && (
                  <p className="py-6 text-center text-sm text-slate-400">검색 결과가 없습니다.</p>
                )}
                {companies && companies.totalPages > 1 && (
                  <div className="flex items-center justify-between pt-3 text-sm text-slate-600">
                    <button disabled={page === 0} onClick={() => setPage(page - 1)} className="rounded border px-3 py-1 disabled:opacity-40">
                      이전
                    </button>
                    <span>{page + 1} / {companies.totalPages}</span>
                    <button
                      disabled={page + 1 >= companies.totalPages}
                      onClick={() => setPage(page + 1)}
                      className="rounded border px-3 py-1 disabled:opacity-40"
                    >
                      다음
                    </button>
                  </div>
                )}
              </div>
            )}
          </Card>

          <div className="space-y-6">
            {!selected && (
              <Card>
                <div className="py-14 text-center">
                  <p className="text-lg font-semibold text-slate-800">기업을 선택하면 CEO 브리핑이 생성됩니다.</p>
                  <p className="mt-2 text-sm text-slate-500">세 개의 독립 MSA 데이터를 읽기 전용으로 조합합니다.</p>
                </div>
              </Card>
            )}

            {loadingInsight && (
              <Card>
                <div className="flex justify-center py-16"><Spinner /></div>
              </Card>
            )}

            {insight && !loadingInsight && (
              <>
                <Card>
                  <div className="flex flex-col gap-2 md:flex-row md:items-start md:justify-between">
                    <div>
                      <h2 className="text-2xl font-bold text-slate-950">
                        {insight.company.name}
                        <span className="ml-2 font-mono text-sm font-medium text-slate-400">({insight.company.stockCode})</span>
                      </h2>
                      <p className="mt-2 text-sm text-slate-600">{insight.briefing.headline}</p>
                    </div>
                    <span className="rounded-full bg-slate-100 px-3 py-1 text-xs font-semibold text-slate-700">
                      {insight.companyProfile?.market ?? insight.company.market}
                    </span>
                  </div>

                  <div className="mt-6 grid grid-cols-1 gap-3 sm:grid-cols-2 xl:grid-cols-4">
                    {insight.briefing.summaryCards.map((card) => (
                      <div key={card.label} className={`rounded-md border px-4 py-3 ${cardToneClass(card.tone)}`}>
                        <p className="text-xs font-semibold">{card.label}</p>
                        <p className="mt-1 text-2xl font-bold">{card.value}</p>
                        <p className="mt-1 text-xs opacity-80">{card.hint}</p>
                      </div>
                    ))}
                  </div>
                </Card>

                <Card title="CEO 브리핑 문서">
                  {insight.documents.length === 0 ? (
                    <p className="text-sm text-slate-500">
                      등록된 브리핑 문서가 없습니다. 외부 파이프라인(분기 브리핑 배치)이 산출한 문서가 문서함에 업로드되면 여기서 바로 다운로드할 수 있습니다.
                    </p>
                  ) : (
                    <ul className="grid gap-2 sm:grid-cols-2">
                      {insight.documents.map((document) => (
                        <li key={document.id}>
                          <a
                            href={companyApi.documentDownloadUrl(document.id)}
                            download={document.fileName}
                            className="group flex items-center gap-3 rounded-md border border-slate-200 bg-slate-50 px-3 py-2.5 transition-colors hover:border-slate-400 hover:bg-white"
                          >
                            <span className="text-xl">{docIcon(document.contentType)}</span>
                            <span className="min-w-0">
                              <span className="block truncate text-sm font-semibold text-slate-900 group-hover:text-slate-700">
                                {document.title}
                              </span>
                              <span className="block text-xs text-slate-400">
                                {document.fileName} · {fmtBytes(document.sizeBytes)} · {document.uploadedAt.slice(0, 10)}
                              </span>
                            </span>
                            <span className="ml-auto shrink-0 rounded-md bg-slate-900 px-2.5 py-1 text-xs font-semibold text-white group-hover:bg-slate-700">
                              다운로드
                            </span>
                          </a>
                        </li>
                      ))}
                    </ul>
                  )}
                </Card>

                <Card title="시세 · 밸류에이션">
                  {insight.marketQuote ? (
                    <>
                      <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
                        <div className="rounded-md border border-slate-200 bg-slate-50 px-4 py-3">
                          <p className="text-xs font-semibold text-slate-500">시가총액</p>
                          <p className="mt-1 text-2xl font-bold text-slate-900">{fmtAmount(insight.marketQuote.marketCap)}</p>
                        </div>
                        <div className="rounded-md border border-slate-200 bg-slate-50 px-4 py-3">
                          <p className="text-xs font-semibold text-slate-500">종가</p>
                          <p className="mt-1 text-2xl font-bold text-slate-900">
                            {insight.marketQuote.closePrice.toLocaleString('ko-KR')}<span className="text-sm font-medium text-slate-500">원</span>
                          </p>
                          <p className={`mt-0.5 text-xs font-semibold ${rateToneClass(insight.marketQuote.fluctuationRate)}`}>
                            {fmtRate(insight.marketQuote.fluctuationRate)}
                          </p>
                        </div>
                        <div className="rounded-md border border-slate-200 bg-slate-50 px-4 py-3">
                          <p className="text-xs font-semibold text-slate-500">PER</p>
                          <p className="mt-1 text-2xl font-bold text-slate-900">{fmtMultiple(insight.valuation.per)}</p>
                          <p className="mt-0.5 text-xs text-slate-400">시총/순이익</p>
                        </div>
                        <div className="rounded-md border border-slate-200 bg-slate-50 px-4 py-3">
                          <p className="text-xs font-semibold text-slate-500">PBR</p>
                          <p className="mt-1 text-2xl font-bold text-slate-900">{fmtMultiple(insight.valuation.pbr)}</p>
                          <p className="mt-0.5 text-xs text-slate-400">시총/자본총계</p>
                        </div>
                      </div>
                      <p className="mt-3 text-xs text-slate-400">
                        기준일 {insight.marketQuote.baseDate} · 출처 {insight.marketQuote.source} · 재무제표 조인으로 산출한 참고치입니다.
                      </p>
                    </>
                  ) : (
                    <p className="text-sm text-slate-500">market-service에 등록된 시세가 없습니다 (미상장·미수집 종목).</p>
                  )}
                </Card>

                <Card title="Agent Briefing">
                  {insight.briefing.risks.length === 0 ? (
                    <p className="text-sm text-slate-500">현재 조합 가능한 데이터에서는 즉시 확인할 고위험 신호가 감지되지 않았습니다.</p>
                  ) : (
                    <div className="space-y-4">
                      {insight.briefing.risks.map((risk) => (
                        <section key={`${risk.category}-${risk.title}`} className="rounded-md border border-slate-200 p-4">
                          <div className="flex flex-wrap items-center gap-2">
                            <span className="text-sm font-semibold text-slate-500">{risk.category}</span>
                            <span className={`rounded-full px-2 py-0.5 text-xs font-semibold ${severityClass(risk.severity)}`}>
                              {severityLabel(risk.severity)}
                            </span>
                          </div>
                          <h3 className="mt-2 text-lg font-bold text-slate-950">{risk.title}</h3>
                          <ul className="mt-3 list-disc space-y-1 pl-5 text-sm text-slate-700">
                            {risk.evidence.map((item) => <li key={item}>{item}</li>)}
                          </ul>
                          <p className="mt-3 text-sm text-slate-700">{risk.interpretation}</p>
                          <p className="mt-3 rounded-md bg-slate-100 px-3 py-2 text-sm font-medium text-slate-800">{risk.action}</p>
                        </section>
                      ))}
                    </div>
                  )}
                </Card>

                <div className="grid grid-cols-1 gap-6 xl:grid-cols-2">
                  <Card title="재무 요약">
                    {insight.latestStatement ? (
                      <dl className="grid grid-cols-2 gap-x-4 gap-y-3 text-sm text-black">
                        <dt>기준연도</dt>
                        <dd className="text-right font-semibold">{insight.latestStatement.fiscalYear} {insight.latestStatement.fsDivision}</dd>
                        <dt>매출</dt>
                        <dd className="text-right font-semibold">{fmtAmount(insight.latestStatement.revenue)}</dd>
                        <dt>영업이익</dt>
                        <dd className="text-right font-semibold">{fmtAmount(insight.latestStatement.operatingProfit)}</dd>
                        <dt>순이익</dt>
                        <dd className="text-right font-semibold">{fmtAmount(insight.latestStatement.netIncome)}</dd>
                        <dt>자산총계</dt>
                        <dd className="text-right font-semibold">{fmtAmount(insight.latestStatement.totalAssets)}</dd>
                        <dt>출처</dt>
                        <dd className="text-right font-semibold">{insight.latestStatement.source}</dd>
                      </dl>
                    ) : (
                      <p className="text-sm text-slate-500">등록된 재무제표가 없습니다.</p>
                    )}
                  </Card>

                  <Card title="평판과 최신 기사">
                    {insight.reputation ? (
                      <div className="mb-4 flex flex-wrap items-center gap-3 text-sm">
                        <span className="rounded-md bg-slate-900 px-3 py-1 text-lg font-bold text-white">{insight.reputation.grade}</span>
                        <span className="font-semibold text-black">{insight.reputation.score}/100점</span>
                        <span className="text-black">부정 {insight.reputation.negativeCount}건</span>
                      </div>
                    ) : (
                      <p className="mb-4 text-sm text-slate-500">평판 스냅샷이 아직 없습니다.</p>
                    )}
                    <ul className="divide-y divide-slate-100">
                      {insight.articles.slice(0, 5).map((article) => (
                        <li key={article.url} className="py-2">
                          <a href={article.url} target="_blank" rel="noopener noreferrer" className="text-sm font-semibold text-black hover:text-slate-600">
                            {article.title}
                          </a>
                          {article.summary && <p className="mt-1 line-clamp-2 text-xs text-black">{article.summary}</p>}
                        </li>
                      ))}
                      {insight.articles.length === 0 && <li className="py-2 text-sm text-slate-500">수집된 기사가 없습니다.</li>}
                    </ul>
                  </Card>
                </div>
              </>
            )}
          </div>
        </div>
      </div>
    </div>
  );
};

export default CeoInsightPage;
