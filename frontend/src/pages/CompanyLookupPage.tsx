import React, { useEffect, useState } from 'react';
import { companyApi, Company, CompanyPage, Reputation, Article, CompanyDocument } from '@/api/company';
import Card from '@/components/Card';
import Spinner from '@/components/Spinner';

/** 평판 등급별 뱃지 색 */
const gradeClass = (grade: string) => {
  switch (grade) {
    case 'A': return 'bg-green-100 text-green-800';
    case 'B': return 'bg-emerald-100 text-emerald-800';
    case 'C': return 'bg-yellow-100 text-yellow-800';
    case 'D': return 'bg-orange-100 text-orange-800';
    default:  return 'bg-red-100 text-red-800';   // E
  }
};

const CATEGORY_LABEL: Record<string, string> = {
  FINANCIAL: '재무', LEGAL: '법률', GOVERNANCE: '지배구조', LABOR: '노동', PRODUCT: '제품',
};

const fmtDate = (iso: string | null) => (iso ? iso.slice(0, 10) : '');

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

const CompanyLookupPage: React.FC = () => {
  const [keyword, setKeyword] = useState('');
  const [query, setQuery] = useState('');
  const [page, setPage] = useState(0);
  const [companies, setCompanies] = useState<CompanyPage | null>(null);
  const [loadingList, setLoadingList] = useState(false);

  const [selected, setSelected] = useState<Company | null>(null);
  const [reputation, setReputation] = useState<Reputation | null>(null);
  const [articles, setArticles] = useState<Article[]>([]);
  const [documents, setDocuments] = useState<CompanyDocument[]>([]);
  const [loadingDetail, setLoadingDetail] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    setLoadingList(true);
    setError(null);
    companyApi
      .companies(query, page)
      .then((data) => { if (!cancelled) setCompanies(data); })
      .catch((err: any) => { if (!cancelled) setError(err.response?.data?.message || '기업 목록 조회에 실패했습니다.'); })
      .finally(() => { if (!cancelled) setLoadingList(false); });
    return () => { cancelled = true; };
  }, [query, page]);

  const openCompany = async (company: Company) => {
    setSelected(company);
    setLoadingDetail(true);
    setError(null);
    try {
      const [rep, arts, docs] = await Promise.all([
        companyApi.reputation(company.stockCode),
        companyApi.articles(company.stockCode),
        companyApi.documents(company.stockCode).catch(() => [] as CompanyDocument[]),
      ]);
      setReputation(rep);
      setArticles(arts.content);
      setDocuments(docs);
    } catch (err: any) {
      setError(err.response?.data?.message || '기업 상세 조회에 실패했습니다.');
      setReputation(null);
      setArticles([]);
      setDocuments([]);
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
          <h1 className="text-3xl font-bold text-gray-900">기업 뉴스·평판 조회</h1>
          <p className="mt-2 text-sm text-gray-500">
            코스피 상장사의 최신 뉴스와 평판 등급(A~E)을 조회합니다. 기사 본문은 저장하지 않고 제목·요약·원문 링크만 제공합니다.
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
                    <tr className="text-left text-gray-900 border-b">
                      <th className="py-2 pr-4">종목코드</th>
                      <th className="py-2 pr-4">기업명</th>
                      <th className="py-2 pr-4">시장</th>
                      <th className="py-2" />
                    </tr>
                  </thead>
                  <tbody>
                    {companies?.content.map((c) => (
                      <tr key={c.stockCode} className="border-b last:border-0 hover:bg-indigo-50/60">
                        <td className="py-2 pr-4 font-mono text-gray-900">{c.stockCode}</td>
                        <td className="py-2 pr-4 font-medium text-gray-900">{c.name}</td>
                        <td className="py-2 pr-4 text-gray-900">{c.market}</td>
                        <td className="py-2 text-right">
                          <button
                            onClick={() => openCompany(c)}
                            className="text-indigo-600 hover:text-indigo-800 font-medium"
                          >
                            뉴스·평판 보기
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
                    <button disabled={page === 0} onClick={() => setPage(page - 1)} className="px-3 py-1 rounded border disabled:opacity-40">이전</button>
                    <span className="px-2 py-1">{page + 1} / {companies.totalPages}</span>
                    <button disabled={page + 1 >= companies.totalPages} onClick={() => setPage(page + 1)} className="px-3 py-1 rounded border disabled:opacity-40">다음</button>
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
            ) : (
              <div className="space-y-6">
                {/* 평판 요약 */}
                <div>
                  <h3 className="text-sm font-semibold text-gray-500 mb-2">평판</h3>
                  {reputation ? (
                    <div className="flex flex-wrap items-center gap-4 bg-gray-50 rounded-lg px-4 py-3">
                      <span className={`text-2xl font-bold px-3 py-1 rounded-lg ${gradeClass(reputation.grade)}`}>
                        {reputation.grade}
                      </span>
                      <div className="text-sm text-gray-700">
                        <div><span className="font-semibold">{reputation.score}</span> / 100점</div>
                        <div className="text-gray-500 text-xs">기준일 {fmtDate(reputation.snapshotDate)} · 기사 {reputation.articleCount}건</div>
                      </div>
                      <div className="flex gap-2 text-xs">
                        <span className="px-2 py-1 rounded bg-green-100 text-green-800">긍정 {reputation.positiveCount}</span>
                        <span className="px-2 py-1 rounded bg-gray-200 text-gray-700">중립 {reputation.neutralCount}</span>
                        <span className="px-2 py-1 rounded bg-red-100 text-red-800">부정 {reputation.negativeCount}</span>
                      </div>
                      {Object.entries(reputation.negativeByCategory || {}).length > 0 && (
                        <div className="flex gap-1.5 text-xs">
                          {Object.entries(reputation.negativeByCategory).map(([cat, cnt]) => (
                            <span key={cat} className="px-2 py-1 rounded bg-orange-100 text-orange-800">
                              {CATEGORY_LABEL[cat] || cat} {cnt}
                            </span>
                          ))}
                        </div>
                      )}
                    </div>
                  ) : (
                    <p className="text-sm text-gray-400">아직 평판이 산정되지 않았습니다 (수집된 기사로 재계산 필요).</p>
                  )}
                </div>

                {/* CEO 브리핑 문서함 — 외부 파이프라인 산출물(docx·pdf 등) 다운로드 */}
                <div>
                  <h3 className="text-sm font-semibold text-gray-500 mb-2">CEO 브리핑 문서</h3>
                  {documents.length === 0 ? (
                    <p className="text-sm text-gray-400">등록된 브리핑 문서가 없습니다.</p>
                  ) : (
                    <ul className="grid gap-2 sm:grid-cols-2">
                      {documents.map((d) => (
                        <li key={d.id}>
                          <a
                            href={companyApi.documentDownloadUrl(d.id)}
                            download={d.fileName}
                            className="flex items-center gap-3 bg-gray-50 hover:bg-indigo-50 border border-gray-200 rounded-lg px-3 py-2.5 group"
                          >
                            <span className="text-xl">{docIcon(d.contentType)}</span>
                            <span className="min-w-0">
                              <span className="block text-sm font-medium text-gray-900 group-hover:text-indigo-700 truncate">
                                {d.title}
                              </span>
                              <span className="block text-xs text-gray-400">
                                {d.fileName} · {fmtBytes(d.sizeBytes)} · {fmtDate(d.uploadedAt)}
                              </span>
                            </span>
                          </a>
                        </li>
                      ))}
                    </ul>
                  )}
                </div>

                {/* 뉴스 기사 */}
                <div>
                  <h3 className="text-sm font-semibold text-gray-500 mb-2">최근 뉴스</h3>
                  {articles.length === 0 ? (
                    <p className="text-sm text-gray-400">수집된 기사가 없습니다.</p>
                  ) : (
                    <ul className="divide-y">
                      {articles.map((a, i) => (
                        <li key={i} className="py-2.5">
                          <a href={a.url} target="_blank" rel="noopener noreferrer"
                             className="text-sm font-medium text-gray-900 hover:text-indigo-700">
                            {a.title}
                          </a>
                          {a.summary && <p className="text-xs text-gray-500 mt-0.5 line-clamp-2">{a.summary}</p>}
                          <div className="text-xs text-gray-400 mt-1 flex gap-2">
                            {a.publisher && <span>{a.publisher}</span>}
                            {a.publishedAt && <span>· {fmtDate(a.publishedAt)}</span>}
                          </div>
                        </li>
                      ))}
                    </ul>
                  )}
                </div>
              </div>
            )}
          </Card>
        )}
      </div>
    </div>
  );
};

export default CompanyLookupPage;
