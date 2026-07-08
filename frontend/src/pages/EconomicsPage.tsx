import React, { useEffect, useState } from 'react';
import { economicsApi, EconomicIndicator, IndicatorSeries } from '@/api/economics';
import Card from '@/components/Card';
import Spinner from '@/components/Spinner';

const fmtValue = (v: number | null | undefined, unit: string) => {
  if (v === null || v === undefined) return 'N/A';
  return `${v.toLocaleString('ko-KR', { maximumFractionDigits: 4 })} ${unit}`;
};

const fmtDate = (d: string | null | undefined) => (d ? d : '-');

const changeClass = (amount: number | null | undefined) => {
  if (amount === null || amount === undefined) return 'text-gray-400';
  if (amount > 0) return 'text-red-600';
  if (amount < 0) return 'text-blue-600';
  return 'text-gray-500';
};

const changeArrow = (amount: number | null | undefined) => {
  if (amount === null || amount === undefined) return '';
  if (amount > 0) return '▲';
  if (amount < 0) return '▼';
  return '-';
};

const EconomicsPage: React.FC = () => {
  const [indicators, setIndicators] = useState<EconomicIndicator[]>([]);
  const [loadingList, setLoadingList] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const [selected, setSelected] = useState<EconomicIndicator | null>(null);
  const [series, setSeries] = useState<IndicatorSeries | null>(null);
  const [loadingSeries, setLoadingSeries] = useState(false);

  useEffect(() => {
    let cancelled = false;
    setLoadingList(true);
    setError(null);
    economicsApi
      .indicators()
      .then((data) => { if (!cancelled) setIndicators(data); })
      .catch((err: any) => { if (!cancelled) setError(err.response?.data?.message || '경제지표 조회에 실패했습니다.'); })
      .finally(() => { if (!cancelled) setLoadingList(false); });
    return () => { cancelled = true; };
  }, []);

  const openIndicator = async (indicator: EconomicIndicator) => {
    setSelected(indicator);
    setLoadingSeries(true);
    setError(null);
    try {
      setSeries(await economicsApi.series(indicator.code));
    } catch (err: any) {
      setError(err.response?.data?.message || '시계열 조회에 실패했습니다.');
      setSeries(null);
    } finally {
      setLoadingSeries(false);
    }
  };

  return (
    <div className="min-h-screen bg-gradient-to-br from-sky-50 to-indigo-50 py-10 px-4 sm:px-6 lg:px-8">
      <div className="max-w-6xl mx-auto space-y-6">
        <div className="text-center">
          <h1 className="text-3xl font-bold text-gray-900">경제지표</h1>
          <p className="mt-2 text-sm text-gray-500">
            한국은행 ECOS 기준금리·국고채3년·USD/KRW·소비자물가지수 (SEED 표기는 근사 샘플, ECOS 는 실데이터)
          </p>
        </div>

        {error && (
          <div className="bg-red-50 border border-red-200 text-red-700 rounded-lg px-4 py-3 text-sm">{error}</div>
        )}

        {loadingList ? (
          <div className="py-10 flex justify-center"><Spinner /></div>
        ) : (
          <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
            {indicators.map((ind) => (
              <button
                key={ind.code}
                onClick={() => openIndicator(ind)}
                className={`text-left bg-white rounded-lg shadow px-5 py-4 hover:shadow-md transition-shadow border ${
                  selected?.code === ind.code ? 'border-indigo-400' : 'border-transparent'
                }`}
              >
                <p className="text-sm text-gray-500">{ind.name}</p>
                <p className="mt-1 text-2xl font-bold text-gray-900">
                  {fmtValue(ind.latest?.value, ind.unit)}
                </p>
                <p className="mt-1 text-xs text-gray-400">{fmtDate(ind.latest?.observedDate)}</p>
                <p className={`mt-2 text-sm font-medium ${changeClass(ind.change?.amount)}`}>
                  {ind.change ? (
                    <>
                      {changeArrow(ind.change.amount)} {Math.abs(ind.change.amount).toLocaleString('ko-KR', { maximumFractionDigits: 4 })}
                      {ind.change.ratePercent !== null && ind.change.ratePercent !== undefined
                        ? ` (${ind.change.ratePercent > 0 ? '+' : ''}${ind.change.ratePercent.toFixed(2)}%)`
                        : ''}
                    </>
                  ) : (
                    '전기 대비 변동 없음'
                  )}
                </p>
              </button>
            ))}
            {!loadingList && indicators.length === 0 && (
              <p className="col-span-full py-6 text-center text-gray-400 text-sm">등록된 지표가 없습니다</p>
            )}
          </div>
        )}

        {selected && (
          <Card>
            <div className="flex items-baseline justify-between mb-4">
              <h2 className="text-xl font-semibold text-gray-900">
                {selected.name} <span className="text-gray-400 text-sm">({selected.unit})</span>
              </h2>
              <button onClick={() => { setSelected(null); setSeries(null); }} className="text-sm text-gray-400 hover:text-gray-600">닫기 ✕</button>
            </div>

            {loadingSeries ? (
              <div className="py-10 flex justify-center"><Spinner /></div>
            ) : !series || series.points.length === 0 ? (
              <p className="py-6 text-center text-gray-400 text-sm">등록된 시계열이 없습니다</p>
            ) : (
              <div className="overflow-x-auto max-h-96 overflow-y-auto">
                <table className="min-w-full text-sm">
                  <thead className="sticky top-0 bg-white">
                    <tr className="text-left text-gray-500 border-b">
                      <th className="py-2 pr-4">관측일</th>
                      <th className="py-2 pr-4 text-right">값</th>
                      <th className="py-2 text-right">출처</th>
                    </tr>
                  </thead>
                  <tbody>
                    {[...series.points].reverse().map((p) => (
                      <tr key={p.observedDate} className="border-b last:border-0">
                        <td className="py-2 pr-4 font-mono">{p.observedDate}</td>
                        <td className="py-2 pr-4 text-right">{p.value.toLocaleString('ko-KR', { maximumFractionDigits: 4 })}</td>
                        <td className="py-2 text-right">
                          <span className={`px-2 py-0.5 rounded text-xs font-medium ${
                            p.source === 'ECOS' ? 'bg-green-100 text-green-800' : 'bg-yellow-100 text-yellow-800'
                          }`}>
                            {p.source}
                          </span>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
                <p className="mt-3 text-xs text-gray-400">
                  SEED 는 근사 샘플 데이터이며, ECOS 수집 후 실데이터로 대체됩니다.
                </p>
              </div>
            )}
          </Card>
        )}
      </div>
    </div>
  );
};

export default EconomicsPage;
