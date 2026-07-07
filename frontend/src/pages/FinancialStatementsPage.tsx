import React, { useEffect, useState } from 'react';
import { financialApi, FinancialCompany, FinancialCompanyPage, FinancialStatement } from '@/api/financial';
import Card from '@/components/Card';
import Spinner from '@/components/Spinner';

/** 조/억 단위 축약 표기 (원 단위 금액) */
const fmtAmount = (v: number | null) => {
  if (v === null || v === undefined) return 'N/A';
  const abs = Math.abs(v);
  if (abs >= 1_0000_0000_0000) return `${(v / 1_0000_0000_0000).toLocaleString('ko-KR', { maximumFractionDigits: 1 })}조`;
  if (abs >= 1_0000_0000) return `${(v / 1_0000_0000).toLocaleString('ko-KR', { maximumFractionDigits: 0 })}억`;
  return v.toLocaleString('ko-KR');
};

const fmtPct = (v: number | null) => (v === null || v === undefined ? 'N/A' : `${v.toFixed(2)}%`);

const profitClass = (v: number | null) =>
  v === null ? 'text-gray-400' : v < 0 ? 'text-red-600' : 'text-gray-900';

const FinancialStatementsPage: React.FC = () => {
  const [keyword, setKeyword] = useState('');
  const [query, setQuery] = useState('');
  const [page, setPage] = useState(0);
  const [companies, setCompanies] = useState<FinancialCompanyPage | null>(null);
  const [loadingList, setLoadingList] = useState(false);

  const [selected, setSelected] = useState<FinancialCompany | null>(null);
  const [statements, setStatements] = useState<FinancialStatement[]>([]);
  const [loadingDetail, setLoadingDetail] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    setLoadingList(true);
    setError(null);
    financialApi
      .companies(query, page)
      .then((data) => { if (!cancelled) setCompanies(data); })
      .catch((err: any) => { if (!cancelled) setError(err.response?.data?.message || '기업 목록 조회에 실패했습니다.'); })
      .finally(() => { if (!cancelled) setLoadingList(false); });
    return () => { cancelled = true; };
  }, [query, page]);

  const openCompany = async (company: FinancialCompany) => {
    setSelected(company);
    setLoadingDetail(true);
    setError(null);
    try {
      setStatements(await financialApi.statements(company.stockCode));
    } catch (err: any) {
      setError(err.response?.data?.message || '재무제표 조회에 실패했습니다.');
      setStatements([]);
    } finally {
      setLoadingDetail(false);
    }
  };

  const handleSearch = (e: React.FormEvent) => {
    e.preventDefault();
    setPage(0);
    setQuery(keyword);
  };

  return (
    <div className="min-h-screen bg-gradient-to-br from-sky-50 to-indigo-50 py-10 px-4 sm:px-6 lg:px-8">
      <div className="max-w-6xl mx-auto space-y-6">
        <div className="text-center">
          <h1 className="text-3xl font-bold text-gray-900">코스피 기업 재무제표</h1>
          <p className="mt-2 text-sm text-gray-500">
            유가증권시장 상장사의 연간 요약 재무제표 (SEED 표기는 근사 샘플, DART 는 공시 실데이터)
          </p>
        </div>

        {error && (
          <div className="bg-red-50 border border-red-200 text-red-700 rounded-lg px-4 py-3 text-sm">{error}</div>
        )}

        <Card>
          <form onSubmit={handleSearch} className="flex gap-2 mb-4">
            <input
              type="text"
              value={keyword}
              onChange={(e) => setKeyword(e.target.value)}
              placeholder="기업명 또는 종목코드 검색 (예: 삼성, 005930)"
              className="flex-1 border border-gray-300 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-400"
            />
            <button type="submit" className="bg-indigo-600 hover:bg-indigo-700 text-white text-sm font-medium rounded-lg px-4 py-2">
              검색
            </button>
          </form>

          {loadingList ? (
            <div className="py-10 flex justify-center"><Spinner /></div>
          ) : (
            <>
              <div className="overflow-x-auto">
                <table className="min-w-full text-sm">
                  <thead>
                    <tr className="text-left text-black border-b">
                      <th className="py-2 pr-4">종목코드</th>
                      <th className="py-2 pr-4">기업명</th>
                      <th className="py-2 pr-4">시장</th>
                      <th className="py-2" />
                    </tr>
                  </thead>
                  <tbody>
                    {companies?.content.map((c) => (
                      <tr key={c.stockCode} className="border-b last:border-0 hover:bg-indigo-50/60">
                        <td className="py-2 pr-4 font-mono">{c.stockCode}</td>
                        <td className="py-2 pr-4 font-medium">{c.name}</td>
                        <td className="py-2 pr-4">{c.market}</td>
                        <td className="py-2 text-right">
                          <button
                            onClick={() => openCompany(c)}
                            className="text-indigo-600 hover:text-indigo-800 font-medium"
                          >
                            재무제표 보기
                          </button>
                        </td>
                      </tr>
                    ))}
                    {companies && companies.content.length === 0 && (
                      <tr><td colSpan={4} className="py-6 text-center text-gray-400">검색 결과가 없습니다</td></tr>
                    )}
                  </tbody>
                </table>
              </div>

              {companies && companies.totalPages > 1 && (
                <div className="flex items-center justify-between mt-4 text-sm text-gray-600">
                  <span>총 {companies.totalElements.toLocaleString()}개 기업</span>
                  <div className="flex gap-2">
                    <button
                      disabled={page === 0}
                      onClick={() => setPage(page - 1)}
                      className="px-3 py-1 rounded border disabled:opacity-40"
                    >
                      이전
                    </button>
                    <span className="px-2 py-1">{page + 1} / {companies.totalPages}</span>
                    <button
                      disabled={page + 1 >= companies.totalPages}
                      onClick={() => setPage(page + 1)}
                      className="px-3 py-1 rounded border disabled:opacity-40"
                    >
                      다음
                    </button>
                  </div>
                </div>
              )}
            </>
          )}
        </Card>

        {selected && (
          <Card>
            <div className="flex items-baseline justify-between mb-4">
              <h2 className="text-xl font-semibold text-gray-900">
                {selected.name} <span className="text-gray-400 font-mono text-sm">({selected.stockCode})</span>
              </h2>
              <button onClick={() => setSelected(null)} className="text-sm text-gray-400 hover:text-gray-600">닫기 ✕</button>
            </div>

            {loadingDetail ? (
              <div className="py-10 flex justify-center"><Spinner /></div>
            ) : statements.length === 0 ? (
              <p className="py-6 text-center text-gray-400 text-sm">등록된 재무제표가 없습니다</p>
            ) : (
              <div className="overflow-x-auto">
                <table className="min-w-full text-sm">
                  <thead>
                    <tr className="text-left text-gray-500 border-b">
                      <th className="py-2 pr-4">연도</th>
                      <th className="py-2 pr-4">구분</th>
                      <th className="py-2 pr-4 text-right">매출액</th>
                      <th className="py-2 pr-4 text-right">영업이익</th>
                      <th className="py-2 pr-4 text-right">당기순이익</th>
                      <th className="py-2 pr-4 text-right">자산총계</th>
                      <th className="py-2 pr-4 text-right">부채총계</th>
                      <th className="py-2 pr-4 text-right">자본총계</th>
                      <th className="py-2 pr-4 text-right">영업이익률</th>
                      <th className="py-2 pr-4 text-right">부채비율</th>
                      <th className="py-2 pr-4 text-right">ROA</th>
                      <th className="py-2 text-right">출처</th>
                    </tr>
                  </thead>
                  <tbody>
                    {statements.map((s) => (
                      <tr key={`${s.fiscalYear}-${s.fsDivision}`} className="border-b last:border-0">
                        <td className="py-2 pr-4 font-medium">{s.fiscalYear}</td>
                        <td className="py-2 pr-4">{s.fsDivision === 'CFS' ? '연결' : '별도'}</td>
                        <td className="py-2 pr-4 text-right">{fmtAmount(s.revenue)}</td>
                        <td className={`py-2 pr-4 text-right ${profitClass(s.operatingProfit)}`}>{fmtAmount(s.operatingProfit)}</td>
                        <td className={`py-2 pr-4 text-right ${profitClass(s.netIncome)}`}>{fmtAmount(s.netIncome)}</td>
                        <td className="py-2 pr-4 text-right">{fmtAmount(s.totalAssets)}</td>
                        <td className="py-2 pr-4 text-right">{fmtAmount(s.totalLiabilities)}</td>
                        <td className="py-2 pr-4 text-right">{fmtAmount(s.totalEquity)}</td>
                        <td className={`py-2 pr-4 text-right ${profitClass(s.operatingMargin)}`}>{fmtPct(s.operatingMargin)}</td>
                        <td className="py-2 pr-4 text-right">{fmtPct(s.debtRatio)}</td>
                        <td className="py-2 pr-4 text-right">{fmtPct(s.roa)}</td>
                        <td className="py-2 text-right">
                          <span className={`px-2 py-0.5 rounded text-xs font-medium ${
                            s.source === 'DART' ? 'bg-green-100 text-green-800' : 'bg-yellow-100 text-yellow-800'
                          }`}>
                            {s.source}
                          </span>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
                <p className="mt-3 text-xs text-gray-400">
                  금액은 원화 조/억 단위 축약 표기. SEED 는 근사 샘플 데이터이며, DART 수집 후 실데이터로 대체됩니다.
                </p>
              </div>
            )}
          </Card>
        )}
      </div>
    </div>
  );
};

export default FinancialStatementsPage;
