import React, { useState, useEffect } from 'react';
import { settlementApi } from '@/api/settlement';
import {
  SettlementSearchRequest,
  SettlementSearchResponse,
  SettlementSearchItem,
  SettlementDetail,
} from '@/types';
import Card from '@/components/Card';
import Spinner from '@/components/Spinner';

const SettlementAdmin: React.FC = () => {
  const [data, setData] = useState<SettlementSearchResponse | null>(null);
  const [loading, setLoading] = useState<boolean>(false);
  const [error, setError] = useState<string | null>(null);
  const [selectedSettlement, setSelectedSettlement] = useState<SettlementDetail | null>(null);
  const [showApprovalModal, setShowApprovalModal] = useState(false);
  const [showRejectModal, setShowRejectModal] = useState(false);
  const [rejectReason, setRejectReason] = useState('');
  const [actionLoading, setActionLoading] = useState(false);

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

  useEffect(() => {
    fetchSettlements();
  }, [filters]);

  // 정산 상세 조회
  const handleViewDetail = async (id: number) => {
    try {
      const detail = await settlementApi.getSettlement(id);
      setSelectedSettlement(detail);
    } catch (err: any) {
      setError(err.response?.data?.message || '상세 정보를 불러오는데 실패했습니다.');
    }
  };

  // 정산 승인
  const handleApprove = async () => {
    if (!selectedSettlement) return;

    setActionLoading(true);
    try {
      await settlementApi.approveSettlement(selectedSettlement.id);
      setShowApprovalModal(false);
      setSelectedSettlement(null);
      fetchSettlements(); // 목록 새로고침
    } catch (err: any) {
      setError(err.response?.data?.message || '승인에 실패했습니다.');
    } finally {
      setActionLoading(false);
    }
  };

  // 정산 반려
  const handleReject = async () => {
    if (!selectedSettlement || !rejectReason.trim()) {
      setError('반려 사유를 입력해주세요.');
      return;
    }

    setActionLoading(true);
    try {
      await settlementApi.rejectSettlement(selectedSettlement.id, rejectReason);
      setShowRejectModal(false);
      setSelectedSettlement(null);
      setRejectReason('');
      fetchSettlements(); // 목록 새로고침
    } catch (err: any) {
      setError(err.response?.data?.message || '반려에 실패했습니다.');
    } finally {
      setActionLoading(false);
    }
  };

  // 필터 변경
  const handleFilterChange = (key: keyof SettlementSearchRequest, value: any) => {
    setFilters((prev) => ({
      ...prev,
      [key]: value,
      page: 0,
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

  // 상태 뱃지
  const getStatusBadge = (status: string) => {
    const styles: Record<string, string> = {
      CALCULATED: 'bg-yellow-100 text-yellow-800',
      WAITING_APPROVAL: 'bg-blue-100 text-blue-800',
      APPROVED: 'bg-green-100 text-green-800',
      REJECTED: 'bg-red-100 text-red-800',
      PENDING: 'bg-gray-100 text-gray-800',
      CONFIRMED: 'bg-indigo-100 text-indigo-800',
      CANCELED: 'bg-red-100 text-red-800',
    };
    return styles[status] || 'bg-gray-100 text-gray-800';
  };

  const getStatusText = (status: string) => {
    const texts: Record<string, string> = {
      CALCULATED: '계산완료',
      WAITING_APPROVAL: '승인대기',
      APPROVED: '승인됨',
      REJECTED: '반려됨',
      PENDING: '대기중',
      CONFIRMED: '확정',
      CANCELED: '취소됨',
    };
    return texts[status] || status;
  };

  return (
    <div className="min-h-screen bg-gradient-to-br from-blue-50 via-indigo-50 to-purple-50 py-8 px-4 sm:px-6 lg:px-8">
      <div className="max-w-7xl mx-auto">
        <div className="mb-8">
          <h1 className="text-4xl font-bold text-gray-900 mb-2">정산 관리 대시보드</h1>
          <p className="text-gray-600">정산 내역을 확인하고 승인/반려 처리를 할 수 있습니다</p>
        </div>

        {error && (
          <div className="mb-6 bg-red-50 border border-red-200 rounded-lg p-4 flex justify-between items-center">
            <p className="text-red-800">{error}</p>
            <button onClick={() => setError(null)} className="text-red-600 hover:text-red-800">
              ✕
            </button>
          </div>
        )}

        {/* 필터 */}
        <Card title="검색 필터" className="mb-6">
          <div className="grid grid-cols-1 md:grid-cols-4 gap-4">
            <div>
              <label className="block text-sm font-medium text-gray-700 mb-2">정산 상태</label>
              <select
                className="w-full px-3 py-2 border border-gray-300 rounded-md focus:ring-2 focus:ring-blue-500 text-gray-900"
                value={filters.status || ''}
                onChange={(e) => handleFilterChange('status', e.target.value || undefined)}
              >
                <option value="">전체</option>
                <option value="CALCULATED">계산완료</option>
                <option value="WAITING_APPROVAL">승인대기</option>
                <option value="APPROVED">승인됨</option>
                <option value="REJECTED">반려됨</option>
              </select>
            </div>

            <div>
              <label className="block text-sm font-medium text-gray-700 mb-2">시작일</label>
              <input
                type="date"
                className="w-full px-3 py-2 border border-gray-300 rounded-md focus:ring-2 focus:ring-blue-500 text-gray-900"
                value={filters.startDate || ''}
                onChange={(e) => handleFilterChange('startDate', e.target.value)}
              />
            </div>

            <div>
              <label className="block text-sm font-medium text-gray-700 mb-2">종료일</label>
              <input
                type="date"
                className="w-full px-3 py-2 border border-gray-300 rounded-md focus:ring-2 focus:ring-blue-500 text-gray-900"
                value={filters.endDate || ''}
                onChange={(e) => handleFilterChange('endDate', e.target.value)}
              />
            </div>

            <div className="flex items-end">
              <button
                onClick={fetchSettlements}
                className="w-full px-6 py-2 bg-blue-600 text-white rounded-md hover:bg-blue-700 transition-colors"
              >
                검색
              </button>
            </div>
          </div>
        </Card>

        {/* 집계 정보 */}
        {data?.aggregations && (
          <div className="grid grid-cols-1 md:grid-cols-4 gap-4 mb-6">
            <Card>
              <div className="text-center">
                <p className="text-sm text-gray-600 mb-1">총 건수</p>
                <p className="text-3xl font-bold text-gray-900">{data.totalElements}</p>
              </div>
            </Card>
            <Card>
              <div className="text-center">
                <p className="text-sm text-gray-600 mb-1">총 정산 금액</p>
                <p className="text-2xl font-bold text-blue-600">{formatCurrency(data.aggregations.totalAmount)}</p>
              </div>
            </Card>
            <Card>
              <div className="text-center">
                <p className="text-sm text-gray-600 mb-1">환불 금액</p>
                <p className="text-2xl font-bold text-red-600">
                  {formatCurrency(data.aggregations.totalRefundedAmount)}
                </p>
              </div>
            </Card>
            <Card>
              <div className="text-center">
                <p className="text-sm text-gray-600 mb-1">최종 금액</p>
                <p className="text-2xl font-bold text-green-600">
                  {formatCurrency(data.aggregations.totalFinalAmount)}
                </p>
              </div>
            </Card>
          </div>
        )}

        {/* 정산 목록 */}
        {loading && <Spinner size="lg" message="데이터를 불러오는 중..." />}

        {!loading && data && (
          <Card>
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
                      최종금액
                    </th>
                    <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">
                      상태
                    </th>
                    <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">
                      정산일
                    </th>
                    <th className="px-6 py-3 text-right text-xs font-medium text-gray-500 uppercase tracking-wider">
                      작업
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
                      <td className="px-6 py-4 whitespace-nowrap text-sm font-semibold text-green-600">
                        {formatCurrency(settlement.finalAmount)}
                      </td>
                      <td className="px-6 py-4 whitespace-nowrap">
                        <span
                          className={`px-2 inline-flex text-xs leading-5 font-semibold rounded-full ${getStatusBadge(
                            settlement.status
                          )}`}
                        >
                          {getStatusText(settlement.status)}
                        </span>
                      </td>
                      <td className="px-6 py-4 whitespace-nowrap text-sm text-gray-500">
                        {settlement.settlementDate}
                      </td>
                      <td className="px-6 py-4 whitespace-nowrap text-right text-sm font-medium space-x-2">
                        <button
                          onClick={() => handleViewDetail(settlement.settlementId)}
                          className="text-blue-600 hover:text-blue-900"
                        >
                          상세보기
                        </button>
                        {(settlement.status === 'WAITING_APPROVAL' || settlement.status === 'CALCULATED') && (
                          <>
                            <button
                              onClick={() => {
                                handleViewDetail(settlement.settlementId);
                                setShowApprovalModal(true);
                              }}
                              className="text-green-600 hover:text-green-900"
                            >
                              승인
                            </button>
                            <button
                              onClick={() => {
                                handleViewDetail(settlement.settlementId);
                                setShowRejectModal(true);
                              }}
                              className="text-red-600 hover:text-red-900"
                            >
                              반려
                            </button>
                          </>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>

            {/* 페이지네이션 */}
            <div className="bg-white px-4 py-3 flex items-center justify-between border-t border-gray-200 mt-4">
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
                    className="relative inline-flex items-center px-3 py-2 rounded-l-md border border-gray-300 bg-white text-sm font-medium text-gray-500 hover:bg-gray-50 disabled:opacity-50"
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
                    className="relative inline-flex items-center px-3 py-2 rounded-r-md border border-gray-300 bg-white text-sm font-medium text-gray-500 hover:bg-gray-50 disabled:opacity-50"
                  >
                    다음
                  </button>
                </nav>
              </div>
            </div>
          </Card>
        )}

        {/* 상세 정보 모달 */}
        {selectedSettlement && !showApprovalModal && !showRejectModal && (
          <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center p-4 z-50">
            <div className="bg-white rounded-lg max-w-2xl w-full p-6">
              <div className="flex justify-between items-center mb-4">
                <h2 className="text-2xl font-bold text-gray-900">정산 상세 정보</h2>
                <button
                  onClick={() => setSelectedSettlement(null)}
                  className="text-gray-400 hover:text-gray-600"
                >
                  ✕
                </button>
              </div>

              <div className="space-y-3">
                <div className="flex justify-between py-2 border-b">
                  <span className="text-gray-600">정산 ID</span>
                  <span className="font-semibold">#{selectedSettlement.id}</span>
                </div>
                <div className="flex justify-between py-2 border-b">
                  <span className="text-gray-600">주문 ID</span>
                  <span className="font-semibold">#{selectedSettlement.orderId}</span>
                </div>
                <div className="flex justify-between py-2 border-b">
                  <span className="text-gray-600">결제 ID</span>
                  <span className="font-semibold">#{selectedSettlement.paymentId}</span>
                </div>
                <div className="flex justify-between py-2 border-b">
                  <span className="text-gray-600">정산 금액</span>
                  <span className="text-lg font-bold text-green-600">
                    {formatCurrency(selectedSettlement.amount)}
                  </span>
                </div>
                <div className="flex justify-between py-2 border-b">
                  <span className="text-gray-600">정산 상태</span>
                  <span
                    className={`px-3 py-1 rounded-full text-sm font-semibold ${getStatusBadge(
                      selectedSettlement.status
                    )}`}
                  >
                    {getStatusText(selectedSettlement.status)}
                  </span>
                </div>
                <div className="flex justify-between py-2 border-b">
                  <span className="text-gray-600">정산일</span>
                  <span className="font-semibold">{selectedSettlement.settlementDate}</span>
                </div>
                {selectedSettlement.approvedAt && (
                  <div className="flex justify-between py-2 border-b">
                    <span className="text-gray-600">승인 일시</span>
                    <span className="font-semibold">{selectedSettlement.approvedAt}</span>
                  </div>
                )}
                {selectedSettlement.rejectedAt && (
                  <>
                    <div className="flex justify-between py-2 border-b">
                      <span className="text-gray-600">반려 일시</span>
                      <span className="font-semibold">{selectedSettlement.rejectedAt}</span>
                    </div>
                    <div className="flex justify-between py-2 border-b">
                      <span className="text-gray-600">반려 사유</span>
                      <span className="text-red-600">{selectedSettlement.rejectionReason}</span>
                    </div>
                  </>
                )}
              </div>
            </div>
          </div>
        )}

        {/* 승인 확인 모달 */}
        {showApprovalModal && selectedSettlement && (
          <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center p-4 z-50">
            <div className="bg-white rounded-lg max-w-md w-full p-6">
              <h2 className="text-xl font-bold text-gray-900 mb-4">정산 승인</h2>
              <p className="text-gray-600 mb-6">
                정산 ID #{selectedSettlement.id}를 승인하시겠습니까?
                <br />
                <span className="font-semibold text-green-600">
                  {formatCurrency(selectedSettlement.amount)}
                </span>
              </p>

              {actionLoading ? (
                <Spinner size="sm" message="처리 중..." />
              ) : (
                <div className="flex space-x-3">
                  <button
                    onClick={handleApprove}
                    className="flex-1 bg-green-600 text-white py-2 px-4 rounded-lg hover:bg-green-700"
                  >
                    승인
                  </button>
                  <button
                    onClick={() => {
                      setShowApprovalModal(false);
                      setSelectedSettlement(null);
                    }}
                    className="flex-1 bg-gray-300 text-gray-700 py-2 px-4 rounded-lg hover:bg-gray-400"
                  >
                    취소
                  </button>
                </div>
              )}
            </div>
          </div>
        )}

        {/* 반려 모달 */}
        {showRejectModal && selectedSettlement && (
          <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center p-4 z-50">
            <div className="bg-white rounded-lg max-w-md w-full p-6">
              <h2 className="text-xl font-bold text-gray-900 mb-4">정산 반려</h2>
              <p className="text-gray-600 mb-4">
                정산 ID #{selectedSettlement.id}를 반려합니다.
              </p>

              <div className="mb-4">
                <label className="block text-sm font-medium text-gray-700 mb-2">반려 사유 (필수)</label>
                <textarea
                  className="w-full px-3 py-2 border border-gray-300 rounded-md focus:ring-2 focus:ring-red-500 text-gray-900"
                  rows={4}
                  placeholder="반려 사유를 입력하세요..."
                  value={rejectReason}
                  onChange={(e) => setRejectReason(e.target.value)}
                />
              </div>

              {actionLoading ? (
                <Spinner size="sm" message="처리 중..." />
              ) : (
                <div className="flex space-x-3">
                  <button
                    onClick={handleReject}
                    className="flex-1 bg-red-600 text-white py-2 px-4 rounded-lg hover:bg-red-700"
                  >
                    반려
                  </button>
                  <button
                    onClick={() => {
                      setShowRejectModal(false);
                      setSelectedSettlement(null);
                      setRejectReason('');
                    }}
                    className="flex-1 bg-gray-300 text-gray-700 py-2 px-4 rounded-lg hover:bg-gray-400"
                  >
                    취소
                  </button>
                </div>
              )}
            </div>
          </div>
        )}
      </div>
    </div>
  );
};

export default SettlementAdmin;
