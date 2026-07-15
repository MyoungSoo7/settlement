import React, { useState } from 'react';
import { investmentApi, type StockRecommendations } from '@/api/investment';
import Card from '@/components/Card';
import Spinner from '@/components/Spinner';

const fmtWon = (value: number) =>
  new Intl.NumberFormat('ko-KR', { style: 'currency', currency: 'KRW' }).format(value);

const fmtDate = (isoDate: string) => {
  const [y, m, d] = isoDate.split('-');
  return `${y}년 ${Number(m)}월 ${Number(d)}일`;
};

/**
 * CEO 투자 추천 — 규칙 스크리닝(재무 R1·R2 / 악재 뉴스 R3 / 시세 위치 R4·R5) 산출물 조회.
 * 버튼 클릭 시 최신 추천 세트를 조회해 종목·이유·1차매수가·손절가·1차익절가를 표로 보여준다.
 * 추천일·고지문은 응답에 필수 포함(백엔드 계약)이며 화면에 항상 텍스트로 노출한다.
 */
const CeoInvestRecommendPage: React.FC = () => {
  const [data, setData] = useState<StockRecommendations | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const load = async () => {
    setLoading(true);
    setError(null);
    try {
      setData(await investmentApi.recommendations());
    } catch {
      setError('추천 종목을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-bold text-gray-900">투자 추천</h1>
        <p className="text-sm text-gray-500 mt-1">
          규칙 스크리닝(재무·악재 뉴스·시세 위치) 산출물 — 예측이 아니라 규칙입니다
        </p>
      </div>

      <Card>
        <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-3">
          <div>
            <p className="text-sm font-semibold text-gray-900">최신 추천 종목 세트</p>
            <p className="text-xs text-gray-500 mt-0.5">
              종목 · 추천 이유 · 1차매수가 · 손절가 · 1차익절가를 확인합니다
            </p>
          </div>
          <button
            type="button"
            onClick={load}
            disabled={loading}
            className="px-4 py-2 rounded-lg bg-gray-900 text-white text-sm font-semibold hover:bg-gray-700 disabled:opacity-50 transition-colors"
          >
            {loading ? '조회 중…' : data ? '다시 조회' : '추천 종목 보기'}
          </button>
        </div>
      </Card>

      {loading && (
        <div className="flex justify-center py-10">
          <Spinner />
        </div>
      )}

      {error && (
        <Card>
          <p className="text-sm text-red-600">{error}</p>
        </Card>
      )}

      {!loading && data && (
        <>
          <Card>
            {data.recommendedDate ? (
              <p className="text-sm font-semibold text-gray-900">
                추천일자: {fmtDate(data.recommendedDate)}
              </p>
            ) : (
              <p className="text-sm text-gray-500">등록된 추천 세트가 없습니다.</p>
            )}

            {data.items.length > 0 && (
              <div className="mt-4 overflow-x-auto">
                <table className="min-w-full text-sm">
                  <thead>
                    <tr className="border-b border-gray-200 text-left text-xs text-gray-500">
                      <th className="py-2 pr-4 font-semibold">종목</th>
                      <th className="py-2 pr-4 font-semibold">추천 이유</th>
                      <th className="py-2 pr-4 font-semibold text-right">1차매수가</th>
                      <th className="py-2 pr-4 font-semibold text-right">손절가</th>
                      <th className="py-2 font-semibold text-right">1차익절가</th>
                    </tr>
                  </thead>
                  <tbody>
                    {data.items.map((item) => (
                      <tr key={item.stockCode} className="border-b border-gray-100 align-top">
                        <td className="py-3 pr-4 whitespace-nowrap">
                          <p className="font-semibold text-gray-900">{item.stockName}</p>
                          <p className="text-xs text-gray-400">
                            {item.stockCode} · {item.sector}
                          </p>
                        </td>
                        <td className="py-3 pr-4 text-gray-700 min-w-[16rem]">{item.reason}</td>
                        <td className="py-3 pr-4 text-right whitespace-nowrap font-medium text-gray-900">
                          {fmtWon(item.entryPrice)}
                        </td>
                        <td className="py-3 pr-4 text-right whitespace-nowrap font-medium text-red-600">
                          {fmtWon(item.stopLossPrice)}
                        </td>
                        <td className="py-3 text-right whitespace-nowrap font-medium text-emerald-600">
                          {fmtWon(item.takeProfitPrice)}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </Card>

          <Card>
            <p className="text-xs text-gray-600">{data.priceRule}</p>
            <p className="text-xs text-gray-500 mt-2">{data.disclaimer}</p>
          </Card>
        </>
      )}
    </div>
  );
};

export default CeoInvestRecommendPage;
