import React, { useEffect, useState } from 'react';
import {
  companyApi,
  Workforce,
  WorkforcePage as WorkforcePageData,
  WorkforceComparison,
  WorkforceGroupComparison,
  WorkforceHistory,
  WorkforceTrendPoint,
  ComparisonUnavailableReason,
} from '@/api/company';
import Card from '@/components/Card';
import Spinner from '@/components/Spinner';
import { apiErrorMessage } from '@/lib/apiError';
import { decimalSign, formatDecimal } from '@/lib/decimal';

/** 금액(소수 문자열 또는 수치) → "43,750,000원". null 은 표시 불가 대시 */
const fmtWon = (amount: string | number | null): string => {
  const formatted = formatDecimal(amount);
  return formatted === null ? '—' : `${formatted}원`;
};

/** 차이값 색 — 판정 불가는 중립색. Number() 를 태우면 파싱실패(NaN)·언더플로(-0) 에서
 *  표시 문자열과 색이 어긋난다(대시인데 빨강, 음수인데 파랑). */
const diffColor = (value: string | number | null): string => {
  const sign = decimalSign(value);
  if (sign === null) return 'text-gray-500';
  return sign < 0 ? 'text-red-600' : 'text-blue-700';
};

/** 차이값에 부호를 붙인다 (+는 내 사업장이 집단 중앙값보다 높음) */
const fmtSigned = (value: string | number | null, unit: string): string => {
  const formatted = formatDecimal(value);
  if (formatted === null) return '—';
  return `${decimalSign(value) === 1 ? '+' : ''}${formatted}${unit}`;
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

/**
 * 증감률 표시 — 세 자릿수부터는 소수를 버린다.
 *
 * 집단 중앙값이 한 자릿수(전국 인원수 중앙값 7명)면 대기업 증감률이 백만 % 단위로 나온다
 * (삼성전자 +1,794,071.43%). 이 길이를 그대로 그리면 4등분 그리드의 칸이 내용 폭만큼 늘어나
 * 옆 칸(백분위)을 밀어내 두 값이 붙어 보인다. 이 크기에서 소수 두 자리는 정보 가치가 없으므로
 * 버리되, 정확한 값은 잘라낸 자리 없이 title 로 남긴다.
 */
const RATE_DECIMALS_CUTOFF = 100;

const rateText = (rate: number | null): { shown: string; exact: string | undefined } => {
  if (rate === null) return { shown: '—', exact: undefined };
  const exact = fmtSigned(rate, '%');
  if (Math.abs(rate) < RATE_DECIMALS_CUTOFF) return { shown: exact, exact: undefined };
  return { shown: fmtSigned(Math.round(rate), '%'), exact };
};

/**
 * 지표 한 칸.
 *
 * `min-w-0` 가 핵심 — 그리드 아이템의 기본 `min-width: auto` 를 풀지 않으면 긴 값이 칸 폭을
 * 밀어 올려 이웃 칸을 침범한다. 말줄임(truncate)은 쓰지 않는다: 금액은 단위 "원" 앞에서 줄바꿈이
 * 일어나 두 줄로 온전히 보이는데, 말줄임으로 바꾸면 그 값이 잘려 사라진다. 줄바꿈 지점이 없는
 * 값(퍼센트)만 `break-words` 로 마지막 방어를 두되, 그 전에 {@link rateText} 가 길이를 줄여
 * 실데이터에서는 한 줄에 들어간다.
 */
const MetricCell: React.FC<{
  label: string;
  labelTitle?: string;
  value: string;
  valueTitle?: string;
  valueClass?: string;
}> = ({ label, labelTitle, value, valueTitle, valueClass }) => (
  <div className="min-w-0">
    <div className="text-xs text-gray-400" title={labelTitle}>{label}</div>
    <div className={`font-medium break-words ${valueClass ?? 'text-gray-900'}`} title={valueTitle}>
      {value}
    </div>
  </div>
);

/** 한 지표(중앙값·차이·증감률·백분위) 표시 행 */
const MetricRow: React.FC<{
  label: string;
  median: string | number;
  difference: string | number;
  differenceRate: number | null;
  percentile: number;
  unit: string;
  isMoney?: boolean;
}> = ({ label, median, difference, differenceRate, percentile, unit, isMoney }) => {
  const rate = rateText(differenceRate);
  return (
    <div className="grid grid-cols-2 sm:grid-cols-4 gap-2 text-sm bg-gray-50 rounded-lg px-3 py-2.5">
      <MetricCell
        label={`${label} 중앙값`}
        value={isMoney ? fmtWon(median) : `${median}${unit}`}
      />
      <MetricCell
        label="차이"
        value={fmtSigned(difference, isMoney ? '원' : unit)}
        valueClass={diffColor(difference)}
      />
      <MetricCell label="증감률" value={rate.shown} valueTitle={rate.exact} />
      <MetricCell
        label="백분위"
        labelTitle="같은 집단에서 이 값 이하인 사업장의 비율"
        value={`${percentile}%`}
      />
    </div>
  );
};

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

/** 증감 셀 — 값이 null(첫 월·결측 갭)이면 대시, 부호별 색상 */
const ChangeCell: React.FC<{ change: string | number | null; rate: number | null; isMoney?: boolean }> = ({
  change, rate, isMoney,
}) => {
  const sign = decimalSign(change);
  if (sign === null) return <span className="text-gray-300">—</span>;
  const color = sign > 0 ? 'text-blue-700' : sign < 0 ? 'text-red-600' : 'text-gray-500';
  return (
    <span className={color}>
      {fmtSigned(change, isMoney ? '원' : '명')}
      {rate !== null && <span className="text-xs ml-1">({fmtSigned(rate, '%')})</span>}
    </span>
  );
};

/** 월별 추이 섹션 — 결측 월은 보간 없이 빠진 채 노출, 증감은 연속 인접 월만 */
const TrendSection: React.FC<{ history: WorkforceHistory }> = ({ history }) => (
  <div className="border border-gray-200 rounded-xl p-4 space-y-3">
    <div className="flex flex-wrap items-center gap-2">
      <h4 className="text-sm font-semibold text-gray-700">월별 추이</h4>
      <span className="text-xs text-gray-400">{history.series.length}개월</span>
    </div>
    {history.series.length === 1 && (
      <p className="text-sm text-amber-700 bg-amber-50 border border-amber-200 rounded-lg px-3 py-2">
        추이 데이터가 아직 1개월뿐입니다 — 다음 월 스냅샷이 적재되면 전월 대비 증감이 표시됩니다.
      </p>
    )}
    <div className="overflow-x-auto">
      <table className="min-w-full text-sm">
        <thead>
          <tr className="text-left text-gray-500 border-b text-xs">
            <th className="py-1.5 pr-4">기준월</th>
            <th className="py-1.5 pr-4 text-right">인원수</th>
            <th className="py-1.5 pr-4 text-right">전월 대비</th>
            <th className="py-1.5 pr-4 text-right">추정연봉</th>
            <th className="py-1.5 text-right">전월 대비</th>
          </tr>
        </thead>
        <tbody>
          {history.series.map((p: WorkforceTrendPoint) => (
            <tr key={p.snapshotMonth} className="border-b last:border-0">
              <td className="py-1.5 pr-4 font-mono text-gray-700">
                {p.snapshotMonth}
                {p.salaryCapReached && <span title="기준소득월액 상한 도달" className="ml-1">⚠️</span>}
              </td>
              <td className="py-1.5 pr-4 text-right text-gray-900">{p.headcount.toLocaleString()}명</td>
              <td className="py-1.5 pr-4 text-right"><ChangeCell change={p.headcountChange} rate={p.headcountChangeRate} /></td>
              <td className="py-1.5 pr-4 text-right text-gray-900">{fmtWon(p.estimatedAnnualSalary)}</td>
              <td className="py-1.5 text-right"><ChangeCell change={p.salaryChange} rate={p.salaryChangeRate} isMoney /></td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  </div>
);

const WorkforcePage: React.FC = () => {
  const [keyword, setKeyword] = useState('');
  const [query, setQuery] = useState('');
  const [page, setPage] = useState(0);
  const [list, setList] = useState<WorkforcePageData | null>(null);
  const [loadingList, setLoadingList] = useState(false);

  const [detail, setDetail] = useState<WorkforceComparison | null>(null);
  const [history, setHistory] = useState<WorkforceHistory | null>(null);
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
      // 추이는 부가 정보 — 실패해도 비교 조회를 막지 않는다
      const [data, hist] = await Promise.all([
        companyApi.workforceDetail(row.workplaceName, row.bizRegNoPrefix, row.snapshotMonth),
        companyApi.workforceHistory(row.workplaceName, row.bizRegNoPrefix).catch(() => null),
      ]);
      setDetail(data);
      setHistory(hist);
    } catch (err) {
      setError(apiErrorMessage(err, '사업장 비교 조회에 실패했습니다.'));
      setDetail(null);
      setHistory(null);
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
                  <button onClick={() => { setDetail(null); setHistory(null); }} className="text-sm text-gray-400 hover:text-gray-600">닫기 ✕</button>
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

                {/* 비교 2축 — 업종·지역 각각 독립 판정.
                    카드를 나란히 놓지 않고 세로로 쌓는다: 컨테이너가 max-w-6xl(1152px)이라 2열이면
                    카드가 550px 안쪽이고, 그 안에서 지표 4칸을 나누면 칸당 90px 남짓이라 금액이
                    "42,934,857 / 원" 처럼 단위만 다음 줄로 떨어진다. 1열이면 칸당 190px 이 나와
                    억 단위 금액도 한 줄에 들어간다 — 화면 높이보다 값 가독성을 택한 것. */}
                <div className="grid gap-4">
                  <ComparisonCard title="업종 비교" axis="industry" comparison={detail.industryComparison} />
                  <ComparisonCard title="지역 비교" axis="region" comparison={detail.regionComparison} />
                </div>

                {/* 월별 추이 — 시리즈 키(사업장명+앞6자리) 고정, 결측 월 보간 없음 */}
                {history && <TrendSection history={history} />}

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
