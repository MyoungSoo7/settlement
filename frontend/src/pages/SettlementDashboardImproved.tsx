import React, { useState, useEffect } from 'react';
import { settlementApi } from '@/api/settlement';
import { SettlementSearchRequest, SettlementSearchResponse, SettlementSearchItem } from '@/types';
import StatusBadge from '@/components/StatusBadge';
import DateRangePicker from '@/components/DateRangePicker';
import EmptyState from '@/components/EmptyState';
import LoadingSkeleton from '@/components/LoadingSkeleton';
import { useToast } from '@/contexts/ToastContext';

const SettlementDashboard: React.FC = () => {
  const [data, setData] = useState<SettlementSearchResponse | null>(null);
  const [loading, setLoading] = useState<boolean>(false);
  const [error, setError] = useState<string | null>(null);
  const [dateError, setDateError] = useState<string>('');
  const { showToast } = useToast();

  const [filters, setFilters] = useState<SettlementSearchRequest>({
    page: 0,
    size: 20,
    sortBy: 'createdAt',
    sortDirection: 'DESC',
  });

  const validateDates = (startDate?: string, endDate?: string): boolean => {
    if (startDate && endDate && startDate > endDate) {
      setDateError('시작일은 종료일보다 이전이어야 합니다.');
      return false;
    }
    setDateError('');
    return true;
  };

  const fetchSettlements = async () => {
    if (!validateDates(filters.startDate, filters.endDate)) {
      showToast('시작일은 종료일보다 이전이어야 합니다.', 'error');
      return;
    }

    setLoading(true);
    setError(null);

    try {
      const response = await settlementApi.search(filters);
      setData(response);
    } catch (err: any) {
      const errorMsg = err.response?.data?.message || '데이터를 불러오는데 실패했습니다.';
      setError(errorMsg);
      showToast(errorMsg, 'error');
      console.error('Settlement search error:', err);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchSettlements();
  }, []);

  const handleFilterChange = (key: keyof SettlementSearchRequest, value: any) => {
    setFilters((prev) => ({
      ...prev,
      [key]: value,
      page: 0,
    }));
  };

  const handleQuickDateSelect = (start: string, end: string) => {
    if (validateDates(start, end)) {
      setFilters((prev) => ({
        ...prev,
        startDate: start,
        endDate: end,
        page: 0,
      }));
    }
  };

  const handlePageChange = (newPage: number) => {
    setFilters((prev) => ({ ...prev, page: newPage }));
    fetchSettlements();
  };

  const handleSortChange = (sortBy: string) => {
    setFilters((prev) => ({
      ...prev,
      sortBy,
      sortDirection: prev.sortBy === sortBy && prev.sortDirection === 'ASC' ? 'DESC' : 'ASC',
      page: 0,
    }));
  };

  const formatCurrency = (amount: number): string => {
    return new Intl.NumberFormat('ko-KR', {
      style: 'currency',
      currency: 'KRW',
    }).format(amount);
  };

  const isSearchDisabled = !!dateError || loading;

  return (
    <div className="container mx-auto px-4 py-8">
      <h1 className="text-3xl font-bold mb-8">정산 대시보드</h1>

      <div className="bg-white rounded-lg shadow p-6 mb-6">
        <h2 className="text-xl font-semibold mb-4">검색 필터</h2>
        <div className="grid grid-cols-1 md:grid-cols-3 gap-4 mb-4">
          <div>
            <label className="block text-sm font-medium text-gray-700 mb-2">주문자명</label>
            <input
              type="text"
              className="w-full px-3 py-2 border border-gray-300 rounded-md focus:outline-none focus:ring-2 focus:ring-blue-500 text-gray-900"
              placeholder="주문자명 입력"
              value={filters.ordererName || ''}
              onChange={(e) => handleFilterChange('ordererName', e.target.value)}
            />
          </div>

          <div>
            <label className="block text-sm font-medium text-gray-700 mb-2">상품명</label>
            <input
              type="text"
              className="w-full px-3 py-2 border border-gray-300 rounded-md focus:outline-none focus:ring-2 focus:ring-blue-500 text-gray-900"
              placeholder="상품명 입력"
              value={filters.productName || ''}
              onChange={(e) => handleFilterChange('productName', e.target.value)}
            />
          </div>

          <div>
            <label className="block text-sm font-medium text-gray-700 mb-2">정산 상태</label>
            <select
              className="w-full px-3 py-2 border border-gray-300 rounded-md focus:outline-none focus:ring-2 focus:ring-blue-500 text-gray-900"
              value={filters.status || ''}
              onChange={(e) => handleFilterChange('status', e.target.value || undefined)}
            >
              <option value="">전체</option>
              <option value="REQUESTED">요청됨</option>
              <option value="PROCESSING">처리중</option>
              <option value="DONE">완료</option>
              <option value="FAILED">실패</option>
              <option value="CALCULATED">계산됨</option>
              <option value="WAITING_APPROVAL">승인대기</option>
              <option value="APPROVED">승인됨</option>
              <option value="REJECTED">거부됨</option>
              <option value="PENDING">대기</option>
              <option value="CONFIRMED">확정</option>
              <option value="CANCELED">취소됨</option>
            </select>
          </div>
        </div>

        <div className="mb-4">
          <DateRangePicker
            startDate={filters.startDate || ''}
            endDate={filters.endDate || ''}
            onStartDateChange={(date) => {
              handleFilterChange('startDate', date);
              validateDates(date, filters.endDate);
            }}
            onEndDateChange={(date) => {
              handleFilterChange('endDate', date);
              validateDates(filters.startDate, date);
            }}
            onQuickSelect={handleQuickDateSelect}
            error={dateError}
          />
        </div>

        <div className="grid grid-cols-1 md:grid-cols-3 gap-4 mb-4">
          <div>
            <label className="block text-sm font-medium text-gray-700 mb-2">환불 여부</label>
            <select
              className="w-full px-3 py-2 border border-gray-300 rounded-md focus:outline-none focus:ring-2 focus:ring-blue-500 text-gray-900"
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

        <div className="relative group">
          <button
            onClick={fetchSettlements}
            disabled={isSearchDisabled}
            className={`px-6 py-2 rounded-md transition-colors ${
              isSearchDisabled
                ? 'bg-gray-300 text-gray-500 cursor-not-allowed'
                : 'bg-blue-600 text-white hover:bg-blue-700'
            }`}
          >
            {loading ? '검색 중...' : '검색'}
          </button>
          {dateError && (
            <div className="absolute left-0 top-full mt-2 w-64 p-2 bg-red-50 border border-red-200 rounded text-xs text-red-600 opacity-0 group-hover:opacity-100 transition-opacity">
              {dateError}
            </div>
          )}
        </div>
      </div>

      {loading && <LoadingSkeleton type="card" />}
      {!loading && data?.aggregations && (
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

      {loading && <LoadingSkeleton type="table" rows={10} />}

      {error && !loading && (
        <div className="bg-red-50 border border-red-200 rounded-lg p-4 mb-6">
          <p className="text-red-800">{error}</p>
        </div>
      )}

      {!loading && data && data.settlements.length === 0 && (
        <EmptyState
          title="정산 데이터가 없습니다"
          description="검색 조건에 맞는 정산 내역이 없습니다. 필터를 변경해보세요."
          action={{
            label: '필터 초기화',
            onClick: () => {
              setFilters({
                page: 0,
                size: 20,
                sortBy: 'createdAt',
                sortDirection: 'DESC',
              });
              fetchSettlements();
            },
          }}
        />
      )}

      {!loading && data && data.settlements.length > 0 && (
        <>
          <div className="bg-white rounded-lg shadow overflow-hidden">
            <div className="overflow-x-auto">
              <table className="min-w-full divide-y divide-gray-200">
                <thead className="bg-gray-50">
                  <tr>
                    <th
                      className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider cursor-pointer hover:bg-gray-100"
                      onClick={() => handleSortChange('settlementId')}
                    >
                      정산 ID
                      {filters.sortBy === 'settlementId' && (
                        <span className="ml-1">{filters.sortDirection === 'ASC' ? '↑' : '↓'}</span>
                      )}
                    </th>
                    <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">
                      주문자명
                    </th>
                    <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">
                      상품명
                    </th>
                    <th
                      className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider cursor-pointer hover:bg-gray-100"
                      onClick={() => handleSortChange('amount')}
                    >
                      금액
                      {filters.sortBy === 'amount' && (
                        <span className="ml-1">{filters.sortDirection === 'ASC' ? '↑' : '↓'}</span>
                      )}
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
                    <th
                      className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider cursor-pointer hover:bg-gray-100"
                      onClick={() => handleSortChange('settlementDate')}
                    >
                      정산일
                      {filters.sortBy === 'settlementDate' && (
                        <span className="ml-1">{filters.sortDirection === 'ASC' ? '↑' : '↓'}</span>
                      )}
                    </th>
                  </tr>
                </thead>
                <tbody className="bg-white divide-y divide-gray-200">
                  {data.settlements.map((settlement: SettlementSearchItem) => (
                    <tr key={settlement.settlementId} className="hover:bg-gray-50 transition-colors">
                      <td className="px-6 py-4 whitespace-nowrap text-sm font-medium text-gray-900">
                        #{settlement.settlementId}
                      </td>
                      <td className="px-6 py-4 whitespace-nowrap text-sm text-gray-900">
                        {settlement.ordererName}
                      </td>
                      <td className="px-6 py-4 text-sm text-gray-900 max-w-xs truncate">{settlement.productName}</td>
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
                        <StatusBadge status={settlement.status} type="settlement" />
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

          <div className="bg-white px-4 py-3 flex items-center justify-between border-t border-gray-200 sm:px-6 mt-4 rounded-lg shadow">
            <div className="flex-1 flex justify-between sm:hidden">
              <button
                onClick={() => handlePageChange(filters.page! - 1)}
                disabled={filters.page === 0}
                className="relative inline-flex items-center px-4 py-2 border border-gray-300 text-sm font-medium rounded-md text-gray-700 bg-white hover:bg-gray-50 disabled:opacity-50 disabled:cursor-not-allowed"
              >
                이전
              </button>
              <button
                onClick={() => handlePageChange(filters.page! + 1)}
                disabled={filters.page! >= data.totalPages - 1}
                className="ml-3 relative inline-flex items-center px-4 py-2 border border-gray-300 text-sm font-medium rounded-md text-gray-700 bg-white hover:bg-gray-50 disabled:opacity-50 disabled:cursor-not-allowed"
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
                    className="relative inline-flex items-center px-2 py-2 rounded-l-md border border-gray-300 bg-white text-sm font-medium text-gray-500 hover:bg-gray-50 disabled:opacity-50 disabled:cursor-not-allowed"
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
                    className="relative inline-flex items-center px-2 py-2 rounded-r-md border border-gray-300 bg-white text-sm font-medium text-gray-500 hover:bg-gray-50 disabled:opacity-50 disabled:cursor-not-allowed"
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
