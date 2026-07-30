import React, { useEffect, useState } from 'react';
import {
  companyApi,
  Workforce,
  WorkforcePage as WorkforcePageData,
  WorkforceComparison,
  WorkforceGroupComparison,
  ComparisonUnavailableReason,
} from '@/api/company';
import Card from '@/components/Card';
import Spinner from '@/components/Spinner';
import { apiErrorMessage } from '@/lib/apiError';

/** 금액(소수 문자열 또는 수치) → "43,750,000원". null 은 표시 불가 대시 */
const fmtWon = (amount: string | number | null): string => {
  if (amount === null || amount === undefined) return '—';
  const n = typeof amount === 'string' ? Number(amount) : amount;
  return Number.isFinite(n) ? `${n.toLocaleString('ko-KR')}원` : '—';
};

/** 차이값에 부호를 붙인다 (+는 내 사업장이 집단 중앙값보다 높음) */
const fmtSigned = (value: string | number | null, unit: string): string => {
  if (value === null || value === undefined) return '—';
  const n = typeof value === 'string' ? Number(value) : value;
  if (!Number.isFinite(n)) return '—';
  return `${n > 0 ? '+' : ''}${n.toLocaleString('ko-KR')}${unit}`;
};

const UNAVAILABLE_MESSAGE: Record<ComparisonUnavailableReason, string> = {
  SAMPLE_TOO_SMALL: '집단을 넓혀도 표본이 10개 미만이라 비교를 제공하지 않습니다.',
  INDUSTRY_CODE_MISSING: '원본 데이터에 업종코드가 없어(미신고 공란) 업종 비교가 불가합니다.',
  REGION_UNPARSEABLE: '주소에서 시도를 읽을 수 없어 지역 비교가 불가합니다.',
};

/** 비교단계 뱃지 문구 — 축별로 확대 방식이 다르다 */
const levelLabel = (axis: 'industry' | 'region', comparison: WorkforceGroupComparison): string | null => {
  if (!comparison.comparisonLevel) return null;
  if (comparison.comparisonLevel === 'EXACT') return axis === 'industry' ? '동일 업종' : '동일 시군구';
  return axis === 'industry' ? '상위 업종(앞 3자리)으로 확대' : '시도 단위로 확대';
};

/** 한 지표(중앙값·차이·증감률·백분위) 표시 행 */
const MetricRow: React.FC<{
  label: string;
  median: string | number;
  difference: string | number;
  differenceRate: number | null;
  percentile: number;
  unit: string;
  isMoney?: boolean;
}> = ({ label, median, difference, differenceRate, percentile, unit, isMoney }) => (
  <div className="grid grid-cols-2 sm:grid-cols-4 gap-2 text-sm bg-gray-50 rounded-lg px-3 py-2.5">
    <div>
      <div className="text-xs text-gray-400">{label} 중앙값</div>
      <div className="font-medium text-gray-900">{isMoney ? fmtWon(median) : `${median}${unit}`}</div>
    </div>
    <div>
      <div className="text-xs text-gray-400">차이</div>
      <div className={`font-medium ${Number(difference) >= 0 ? 'text-blue-700' : 'text-red-600'}`}>
        {fmtSigned(difference, isMoney ? '원' : unit)}
      </div>
    </div>
    <div>
      <div className="text-xs text-gray-400">증감률</div>
      <div className="font-medium text-gray-900">
        {differenceRate === null ? '—' : fmtSigned(differenceRate, '%')}
      </div>
    </div>
    <div>
      <div className="text-xs text-gray-400" title="같은 집단에서 이 값 이하인 사업장의 비율">백분위</div>
      <div className="font-medium text-gray-900">{percentile}%</div>
    </div>
  </div>
);

/** 한 비교축(업종/지역) 카드 */
const ComparisonCard: React.FC<{
  title: string;
  axis: 'industry' | 'region';
  comparison: WorkforceGroupComparison;
}> = ({ title, axis, comparison }) => (
  <div className="border border-gray-200 rounded-xl p-4 space-y-3">
    <div className="flex flex-wrap items-center gap-2">
      <h4 className="text-sm font-semibold text-gray-700">{title}</h4>
      {comparison.comparisonLevel && (
        <span
          className={`text-xs px-2 py-0.5 rounded-full ${
            comparison.comparisonLevel === 'EXACT'
              ? 'bg-green-100 text-green-800'
              : 'bg-amber-100 text-amber-800'
          }`}
        >
          {levelLabel(axis, comparison)}
        </span>
      )}
      {comparison.groupKey && (
        <span className="text-xs text-gray-400 font-mono">{comparison.groupKey}</span>
      )}
      {comparison.sampleSize > 0 && (
        <span className="text-xs text-gray-400">표본 {comparison.sampleSize.toLocaleString()}개</span>
      )}
    </div>

    {comparison.unavailableReason ? (
      <p className="text-sm text-gray-400">{UNAVAILABLE_MESSAGE[comparison.unavailableReason]}</p>
    ) : (
      <div className="space-y-2">
        {comparison.headcount && (
          <MetricRow
            label="인원수"
            median={comparison.headcount.median}
            difference={comparison.headcount.difference}
            differenceRate={comparison.headcount.differenceRate}
            percentile={comparison.headcount.percentile}
            unit="명"
          />
        )}
        {comparison.estimatedAnnualSalary && (
          <MetricRow
            label="추정연봉"
            median={comparison.estimatedAnnualSalary.median}
            difference={comparison.estimatedAnnualSalary.difference}
            differenceRate={comparison.estimatedAnnualSalary.differenceRate}
            percentile={comparison.estimatedAnnualSalary.percentile}
            unit="원"
            isMoney
          />
        )}
      </div>
    )}
  </div>
);

const WorkforcePage: React.FC = () => {
  const [keyword, setKeyword] = useState('');
  const [query, setQuery] = useState('');
  const [page, setPage] = useState(0);
  const [list, setList] = useState<WorkforcePageData | null>(null);
  const [loadingList, setLoadingList] = useState(false);

  const [detail, setDetail] = useState<WorkforceComparison | null>(null);
  const [loadingDetail, setLoadingDetail] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    setLoadingList(true);
    setError(null);
    companyApi
      .workforce(query, page)
      .then((data) => { if (!cancelled) setList(data); })
      .catch((err: unknown) => { if (!cancelled) setError(apiErrorMessage(err, '사업장 목록 조회에 실패했습니다.')); })
      .finally(() => { if (!cancelled) setLoadingList(false); });
    return () => { cancelled = true; };
  }, [query, page]);

  const openDetail = async (row: Workforce) => {
    setLoadingDetail(true);
    setError(null);
    try {
      const data = await companyApi.workforceDetail(row.workplaceName, row.bizRegNoPrefix, row.snapshotMonth);
      setDetail(data);
    } catch (err) {
      setError(apiErrorMessage(err, '사업장 비교 조회에 실패했습니다.'));
      setDetail(null);
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
          <h1 className="text-3xl font-bold text-gray-900">사업장 인원·연봉 비교</h1>
          <p className="mt-2 text-sm text-gray-500">
            국민연금 가입 사업장 공개데이터로 인원수·추정연봉을 조회하고, 같은 업종·같은 지역 집단의
            중앙값과 비교합니다. 3인 이상 법인(개인은 10인 이상) 사업장만 수록된 절단 표본입니다.
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
              placeholder="사업장명 검색 (예: 삼성전자, 카카오)"
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
                      <th className="py-2 pr-4">사업장명</th>
                      <th className="py-2 pr-4">업종</th>
                      <th className="py-2 pr-4">주소</th>
                      <th className="py-2 pr-4 text-right">인원수</th>
                      <th className="py-2 pr-4 text-right">추정연봉</th>
                      <th className="py-2 pr-4">기준월</th>
                      <th className="py-2" />
                    </tr>
                  </thead>
                  <tbody>
                    {list?.content.map((w) => (
                      <tr
                        key={`${w.workplaceName}|${w.bizRegNoPrefix}|${w.snapshotMonth}`}
                        className="border-b last:border-0 hover:bg-indigo-50/60"
                      >
                        <td className="py-2 pr-4 font-medium text-gray-900">{w.workplaceName}</td>
                        <td className="py-2 pr-4 text-gray-700">{w.industryName ?? '—'}</td>
                        <td className="py-2 pr-4 text-gray-500 max-w-xs truncate">{w.address ?? '—'}</td>
                        <td className="py-2 pr-4 text-right text-gray-900">{w.headcount.toLocaleString()}명</td>
                        <td className="py-2 pr-4 text-right text-gray-900">{fmtWon(w.estimatedAnnualSalary)}</td>
                        <td className="py-2 pr-4 font-mono text-gray-500">{w.snapshotMonth}</td>
                        <td className="py-2 text-right">
                          <button
                            onClick={() => openDetail(w)}
                            className="text-indigo-600 hover:text-indigo-800 font-medium whitespace-nowrap"
                          >
                            비교 보기
                          </button>
                        </td>
                      </tr>
                    ))}
                    {list && list.content.length === 0 && (
                      <tr><td colSpan={7} className="py-6 text-center text-gray-400">검색 결과가 없습니다</td></tr>
                    )}
                  </tbody>
                </table>
              </div>

              {list && list.totalPages > 1 && (
                <div className="flex items-center justify-between mt-4 text-sm text-gray-600">
                  <span>총 {list.totalElements.toLocaleString()}개 사업장</span>
                  <div className="flex gap-2">
                    <button disabled={page === 0} onClick={() => setPage(page - 1)} className="px-3 py-1 rounded border disabled:opacity-40">이전</button>
                    <span className="px-2 py-1">{page + 1} / {list.totalPages}</span>
                    <button disabled={page + 1 >= list.totalPages} onClick={() => setPage(page + 1)} className="px-3 py-1 rounded border disabled:opacity-40">다음</button>
                  </div>
                </div>
              )}
            </>
          )}
        </Card>

        {(detail || loadingDetail) && (
          <Card>
            {loadingDetail || !detail ? (
              <div className="py-10 flex justify-center"><Spinner /></div>
            ) : (
              <div className="space-y-5">
                <div className="flex items-baseline justify-between">
                  <h2 className="text-xl font-semibold text-gray-900">
                    {detail.workplaceName}{' '}
                    <span className="text-gray-400 font-mono text-sm">
                      ({detail.bizRegNoPrefix}-**-***** · {detail.snapshotMonth})
                    </span>
                  </h2>
                  <button onClick={() => setDetail(null)} className="text-sm text-gray-400 hover:text-gray-600">닫기 ✕</button>
                </div>

                {/* 기본 정보 */}
                <div className="grid grid-cols-2 sm:grid-cols-4 gap-3 text-sm">
                  <div className="bg-gray-50 rounded-lg px-3 py-2.5">
                    <div className="text-xs text-gray-400">업종</div>
                    <div className="font-medium text-gray-900">
                      {detail.industryName ?? '—'}
                      {detail.industryCode && <span className="text-xs text-gray-400 font-mono ml-1">({detail.industryCode})</span>}
                    </div>
                  </div>
                  <div className="bg-gray-50 rounded-lg px-3 py-2.5">
                    <div className="text-xs text-gray-400">지역</div>
                    <div className="font-medium text-gray-900">
                      {detail.sido ?? '—'}{detail.sigungu ? ` ${detail.sigungu}` : ''}
                    </div>
                  </div>
                  <div className="bg-gray-50 rounded-lg px-3 py-2.5">
                    <div className="text-xs text-gray-400">인원수</div>
                    <div className="font-medium text-gray-900">{detail.headcount.toLocaleString()}명</div>
                  </div>
                  <div className="bg-gray-50 rounded-lg px-3 py-2.5">
                    <div className="text-xs text-gray-400">추정연봉 (1인)</div>
                    <div className="font-medium text-gray-900">{fmtWon(detail.estimatedAnnualSalary)}</div>
                  </div>
                </div>

                {/* 상한 도달 — 실패가 아니라 신뢰도 플래그 */}
                {detail.salaryCapReached && (
                  <div className="bg-amber-50 border border-amber-200 text-amber-800 rounded-lg px-4 py-3 text-sm">
                    ⚠️ 추정연봉이 국민연금 기준소득월액 상한
                    {detail.salaryCapMonthlyAmount ? `(월 ${fmtWon(detail.salaryCapMonthlyAmount)})` : ''}에
                    도달했습니다. 실제 급여는 이보다 높을 수 있어 백분위 해석에 주의가 필요합니다.
                  </div>
                )}

                {/* 비교 2축 — 업종·지역 각각 독립 판정 */}
                <div className="grid gap-4 lg:grid-cols-2">
                  <ComparisonCard title="업종 비교" axis="industry" comparison={detail.industryComparison} />
                  <ComparisonCard title="지역 비교" axis="region" comparison={detail.regionComparison} />
                </div>

                <p className="text-xs text-gray-400">{detail.note}</p>
              </div>
            )}
          </Card>
        )}
      </div>
    </div>
  );
};

export default WorkforcePage;
