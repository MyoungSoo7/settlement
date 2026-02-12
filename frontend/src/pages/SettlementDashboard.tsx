import React, { useState, useEffect } from 'react';
import { settlementApi } from '@/api/settlement';
import { SettlementSearchRequest, SettlementSearchResponse, SettlementSearchItem } from '@/types';
import { format } from 'date-fns';

const SettlementDashboard: React.FC = () => {
  const [data, setData] = useState<SettlementSearchResponse | null>(null);
  const [loading, setLoading] = useState<boolean>(false);
  const [error, setError] = useState<string | null>(null);

  // 검색 필터 상태
  const [filters, setFilters] = useState<SettlementSearchRequest>({
    page: 0,
    size: 20,
    sortBy: 'createdAt',
    sortDirection: 'DESC',
  });

  // 정산 데이터 조회
  const fetchSettlements = async () => {
    setLoading(true);
    setError(null);

    try {
      const response = await settlementApi.search(filters);
      setData(response);
    } catch (err: any) {
      setError(err.response?.data?.message || '데이터를 불러오는데 실패했습니다.');
      console.error('Settlement search error:', err);
    } finally {
      setLoading(false);
    }
  };

  // 초기 로드 및 필터 변경 시 재조회
  useEffect(() => {
    fetchSettlements();
  }, [filters]);

  // 필터 변경 핸들러
  const handleFilterChange = (key: keyof SettlementSearchRequest, value: any) => {
    setFilters((prev) => ({
      ...prev,
      [key]: value,
      page: 0, // 필터 변경 시 첫 페이지로
    }));
  };

  // 페이지 변경
  const handlePageChange = (newPage: number) => {
    setFilters((prev) => ({ ...prev, page: newPage }));
  };

  // 금액 포맷팅
  const formatCurrency = (amount: number): string => {
    return new Intl.NumberFormat('ko-KR', {
      style: 'currency',
      currency: 'KRW',
    }).format(amount);
  };

  // 상태 뱃지 스타일
  const getStatusBadge = (status: string) => {
    const styles: Record<string, string> = {
      PENDING: 'bg-yellow-100 text-yellow-800',
      CONFIRMED: 'bg-blue-100 text-blue-800',
      COMPLETED: 'bg-green-100 text-green-800',
    };
    return styles[status] || 'bg-gray-100 text-gray-800';
  };

  return (
    <div className="container mx-auto px-4 py-8">
      <h1 className="text-3xl font-bold mb-8">정산 대시보드</h1>

      {/* 검색 필터 */}
      <div className="bg-white rounded-lg shadow p-6 mb-6">
        <h2 className="text-xl font-semibold mb-4">검색 필터</h2>
        <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
          <div>
            <label className="block text-sm font-medium text-gray-700 mb-2">주문자명</label>
            <input
              type="text"
              className="w-full px-3 py-2 border border-gray-300 rounded-md"
              placeholder="주문자명 입력"
              value={filters.ordererName || ''}
              onChange={(e) => handleFilterChange('ordererName', e.target.value)}
            />
          </div>

          <div>
            <label className="block text-sm font-medium text-gray-700 mb-2">상품명</label>
            <input
              type="text"
              className="w-full px-3 py-2 border border-gray-300 rounded-md"
              placeholder="상품명 입력"
              value={filters.productName || ''}
              onChange={(e) => handleFilterChange('productName', e.target.value)}
            />
          </div>

          <div>
            <label className="block text-sm font-medium text-gray-700 mb-2">정산 상태</label>
            <select
              className="w-full px-3 py-2 border border-gray-300 rounded-md"
              value={filters.status || ''}
              onChange={(e) => handleFilterChange('status', e.target.value || undefined)}
            >
              <option value="">전체</option>
              <option value="PENDING">대기</option>
              <option value="CONFIRMED">확정</option>
              <option value="COMPLETED">완료</option>
            </select>
          </div>

          <div>
            <label className="block text-sm font-medium text-gray-700 mb-2">시작일</label>
            <input
              type="date"
              className="w-full px-3 py-2 border border-gray-300 rounded-md"
              value={filters.startDate || ''}
              onChange={(e) => handleFilterChange('startDate', e.target.value)}
            />
          </div>

          <div>
            <label className="block text-sm font-medium text-gray-700 mb-2">종료일</label>
            <input
              type="date"
              className="w-full px-3 py-2 border border-gray-300 rounded-md"
              value={filters.endDate || ''}
              onChange={(e) => handleFilterChange('endDate', e.target.value)}
            />
          </div>

          <div>
            <label className="block text-sm font-medium text-gray-700 mb-2">환불 여부</label>
            <select
              className="w-full px-3 py-2 border border-gray-300 rounded-md"
              value={filters.isRefunded === undefined ? '' : String(filters.isRefunded)}
              onChange={(e) =>
                handleFilterChange('isRefunded', e.target.value === '' ? undefined : e.target.value === 'true')
              }
            >
              <option value="">전체</option>
              <option value="false">환불 없음</option>
              <option value="true">환불 있음</option>
            </select>
          </div>
        </div>

        <button
          onClick={fetchSettlements}
          className="mt-4 px-6 py-2 bg-blue-600 text-white rounded-md hover:bg-blue-700"
        >
          검색
        </button>
      </div>

      {/* 집계 정보 */}
      {data?.aggregations && (
        <div className="grid grid-cols-1 md:grid-cols-3 gap-4 mb-6">
          <div className="bg-white rounded-lg shadow p-6">
            <h3 className="text-sm font-medium text-gray-500">총 정산 금액</h3>
            <p className="text-2xl font-bold text-gray-900 mt-2">
              {formatCurrency(data.aggregations.totalAmount)}
            </p>
          </div>
          <div className="bg-white rounded-lg shadow p-6">
            <h3 className="text-sm font-medium text-gray-500">총 환불 금액</h3>
            <p className="text-2xl font-bold text-red-600 mt-2">
              {formatCurrency(data.aggregations.totalRefundedAmount)}
            </p>
          </div>
          <div className="bg-white rounded-lg shadow p-6">
            <h3 className="text-sm font-medium text-gray-500">최종 정산 금액</h3>
            <p className="text-2xl font-bold text-green-600 mt-2">
              {formatCurrency(data.aggregations.totalFinalAmount)}
            </p>
          </div>
        </div>
      )}

      {/* 로딩 상태 */}
      {loading && (
        <div className="text-center py-12">
          <div className="inline-block animate-spin rounded-full h-12 w-12 border-b-2 border-blue-600"></div>
          <p className="mt-4 text-gray-600">데이터를 불러오는 중...</p>
        </div>
      )}

      {/* 에러 상태 */}
      {error && (
        <div className="bg-red-50 border border-red-200 rounded-lg p-4 mb-6">
          <p className="text-red-800">{error}</p>
        </div>
      )}

      {/* 정산 테이블 */}
      {!loading && data && (
        <>
          <div className="bg-white rounded-lg shadow overflow-hidden">
            <div className="overflow-x-auto">
              <table className="min-w-full divide-y divide-gray-200">
                <thead className="bg-gray-50">
                  <tr>
                    <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">
                      정산 ID
                    </th>
                    <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">
                      주문자명
                    </th>
                    <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">
                      상품명
                    </th>
                    <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">
                      금액
                    </th>
                    <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">
                      환불금액
                    </th>
                    <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">
                      최종금액
                    </th>
                    <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">
                      상태
                    </th>
                    <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">
                      정산일
                    </th>
                  </tr>
                </thead>
                <tbody className="bg-white divide-y divide-gray-200">
                  {data.settlements.map((settlement: SettlementSearchItem) => (
                    <tr key={settlement.settlementId} className="hover:bg-gray-50">
                      <td className="px-6 py-4 whitespace-nowrap text-sm font-medium text-gray-900">
                        #{settlement.settlementId}
                      </td>
                      <td className="px-6 py-4 whitespace-nowrap text-sm text-gray-900">
                        {settlement.ordererName}
                      </td>
                      <td className="px-6 py-4 text-sm text-gray-900">{settlement.productName}</td>
                      <td className="px-6 py-4 whitespace-nowrap text-sm text-gray-900">
                        {formatCurrency(settlement.amount)}
                      </td>
                      <td className="px-6 py-4 whitespace-nowrap text-sm text-red-600">
                        {formatCurrency(settlement.refundedAmount)}
                      </td>
                      <td className="px-6 py-4 whitespace-nowrap text-sm font-medium text-green-600">
                        {formatCurrency(settlement.finalAmount)}
                      </td>
                      <td className="px-6 py-4 whitespace-nowrap">
                        <span
                          className={`px-2 inline-flex text-xs leading-5 font-semibold rounded-full ${getStatusBadge(
                            settlement.status
                          )}`}
                        >
                          {settlement.status}
                        </span>
                      </td>
                      <td className="px-6 py-4 whitespace-nowrap text-sm text-gray-500">
                        {settlement.settlementDate}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>

          {/* 페이지네이션 */}
          <div className="bg-white px-4 py-3 flex items-center justify-between border-t border-gray-200 sm:px-6 mt-4 rounded-lg shadow">
            <div className="flex-1 flex justify-between sm:hidden">
              <button
                onClick={() => handlePageChange(filters.page! - 1)}
                disabled={filters.page === 0}
                className="relative inline-flex items-center px-4 py-2 border border-gray-300 text-sm font-medium rounded-md text-gray-700 bg-white hover:bg-gray-50 disabled:opacity-50"
              >
                이전
              </button>
              <button
                onClick={() => handlePageChange(filters.page! + 1)}
                disabled={filters.page! >= data.totalPages - 1}
                className="ml-3 relative inline-flex items-center px-4 py-2 border border-gray-300 text-sm font-medium rounded-md text-gray-700 bg-white hover:bg-gray-50 disabled:opacity-50"
              >
                다음
              </button>
            </div>
            <div className="hidden sm:flex-1 sm:flex sm:items-center sm:justify-between">
              <div>
                <p className="text-sm text-gray-700">
                  전체 <span className="font-medium">{data.totalElements}</span>건 중{' '}
                  <span className="font-medium">{filters.page! * filters.size! + 1}</span> -{' '}
                  <span className="font-medium">
                    {Math.min((filters.page! + 1) * filters.size!, data.totalElements)}
                  </span>{' '}
                  건 표시
                </p>
              </div>
              <div>
                <nav className="relative z-0 inline-flex rounded-md shadow-sm -space-x-px">
                  <button
                    onClick={() => handlePageChange(filters.page! - 1)}
                    disabled={filters.page === 0}
                    className="relative inline-flex items-center px-2 py-2 rounded-l-md border border-gray-300 bg-white text-sm font-medium text-gray-500 hover:bg-gray-50 disabled:opacity-50"
                  >
                    이전
                  </button>
                  {Array.from({ length: Math.min(data.totalPages, 5) }, (_, i) => {
                    const pageNum = Math.max(0, filters.page! - 2) + i;
                    if (pageNum >= data.totalPages) return null;
                    return (
                      <button
                        key={pageNum}
                        onClick={() => handlePageChange(pageNum)}
                        className={`relative inline-flex items-center px-4 py-2 border text-sm font-medium ${
                          pageNum === filters.page
                            ? 'z-10 bg-blue-50 border-blue-500 text-blue-600'
                            : 'bg-white border-gray-300 text-gray-500 hover:bg-gray-50'
                        }`}
                      >
                        {pageNum + 1}
                      </button>
                    );
                  })}
                  <button
                    onClick={() => handlePageChange(filters.page! + 1)}
                    disabled={filters.page! >= data.totalPages - 1}
                    className="relative inline-flex items-center px-2 py-2 rounded-r-md border border-gray-300 bg-white text-sm font-medium text-gray-500 hover:bg-gray-50 disabled:opacity-50"
                  >
                    다음
                  </button>
                </nav>
              </div>
            </div>
          </div>
        </>
      )}
    </div>
  );
};

export default SettlementDashboard;
