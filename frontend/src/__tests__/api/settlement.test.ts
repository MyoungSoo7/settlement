import { describe, it, expect, vi, beforeEach } from 'vitest';
import { settlementApi } from '@/api/settlement';
import api from '@/api/axios';
import type { SettlementSearchRequest, SettlementSearchResponse, SettlementDetail } from '@/types';

vi.mock('@/api/axios', () => ({
  default: {
    get: vi.fn(),
    post: vi.fn(),
  },
}));

const mockSearchResponse: SettlementSearchResponse = {
  settlements: [
    {
      settlementId: 1,
      orderId: 10,
      paymentId: 100,
      ordererName: 'user@test.com',
      productName: '테스트 상품',
      amount: 100000,
      refundedAmount: 0,
      finalAmount: 97000,
      status: 'DONE',
      isRefunded: false,
      settlementDate: '2026-01-15',
    },
  ],
  totalElements: 1,
  totalPages: 1,
  currentPage: 0,
  pageSize: 20,
  aggregations: {
    totalAmount: 100000,
    totalRefundedAmount: 0,
    totalFinalAmount: 97000,
    statusCounts: { DONE: 1 },
  },
};

const mockDetail: SettlementDetail = {
  id: 1,
  paymentId: 100,
  orderId: 10,
  amount: 100000,
  status: 'DONE',
  settlementDate: '2026-01-15',
  createdAt: '2026-01-01T00:00:00',
  updatedAt: '2026-01-15T00:00:00',
};

describe('settlementApi', () => {
  beforeEach(() => {
    vi.resetAllMocks();
  });

  // ─── search (GET) ─────────────────────────────────────────
  describe('search', () => {
    it('쿼리 파라미터로 GET 요청하고 SettlementSearchResponse를 반환한다', async () => {
      vi.mocked(api.get).mockResolvedValueOnce({ data: mockSearchResponse });
      const params: SettlementSearchRequest = { startDate: '2026-01-01', endDate: '2026-01-31', page: 0, size: 20 };

      const result = await settlementApi.search(params);

      expect(api.get).toHaveBeenCalledWith('/api/settlements/search', { params });
      expect(result).toEqual(mockSearchResponse);
    });

    it('파라미터 없이도 호출할 수 있다', async () => {
      vi.mocked(api.get).mockResolvedValueOnce({ data: { ...mockSearchResponse, settlements: [] } });

      const result = await settlementApi.search({});

      expect(api.get).toHaveBeenCalledWith('/api/settlements/search', { params: {} });
      expect(result.settlements).toHaveLength(0);
    });

    it('status 필터로 검색할 수 있다', async () => {
      vi.mocked(api.get).mockResolvedValueOnce({ data: mockSearchResponse });

      await settlementApi.search({ status: 'DONE' });

      expect(api.get).toHaveBeenCalledWith('/api/settlements/search', { params: { status: 'DONE' } });
    });

    it('API 오류 시 에러를 throw한다', async () => {
      const error = { response: { status: 403, data: { message: '권한 없음' } } };
      vi.mocked(api.get).mockRejectedValueOnce(error);

      await expect(settlementApi.search({})).rejects.toMatchObject({ response: { status: 403 } });
    });
  });

  // ─── searchByPost (POST) ──────────────────────────────────
  describe('searchByPost', () => {
    it('요청 바디로 POST 요청하고 SettlementSearchResponse를 반환한다', async () => {
      vi.mocked(api.post).mockResolvedValueOnce({ data: mockSearchResponse });
      const request: SettlementSearchRequest = { startDate: '2026-01-01', endDate: '2026-01-31' };

      const result = await settlementApi.searchByPost(request);

      expect(api.post).toHaveBeenCalledWith('/api/settlements/search', request);
      expect(result).toEqual(mockSearchResponse);
    });
  });

  // ─── getSettlement ────────────────────────────────────────
  describe('getSettlement', () => {
    it('ID로 정산 상세를 조회한다', async () => {
      vi.mocked(api.get).mockResolvedValueOnce({ data: mockDetail });

      const result = await settlementApi.getSettlement(1);

      expect(api.get).toHaveBeenCalledWith('/api/settlements/1');
      expect(result).toEqual(mockDetail);
    });

    it('존재하지 않는 ID 조회 시 404 에러를 throw한다', async () => {
      const error = { response: { status: 404 } };
      vi.mocked(api.get).mockRejectedValueOnce(error);

      await expect(settlementApi.getSettlement(9999)).rejects.toMatchObject({ response: { status: 404 } });
    });
  });

  // ─── approveSettlement ────────────────────────────────────
  describe('approveSettlement', () => {
    it('정산을 승인하고 갱신된 SettlementDetail을 반환한다', async () => {
      const approved = { ...mockDetail, status: 'CONFIRMED' };
      vi.mocked(api.post).mockResolvedValueOnce({ data: approved });

      const result = await settlementApi.approveSettlement(1);

      expect(api.post).toHaveBeenCalledWith('/api/settlements/1/approve');
      expect(result.status).toBe('CONFIRMED');
    });
  });

  // ─── rejectSettlement ─────────────────────────────────────
  describe('rejectSettlement', () => {
    it('반려 사유와 함께 정산을 반려한다', async () => {
      const rejected = { ...mockDetail, status: 'REJECTED' };
      vi.mocked(api.post).mockResolvedValueOnce({ data: rejected });

      const result = await settlementApi.rejectSettlement(1, '검토 필요');

      expect(api.post).toHaveBeenCalledWith('/api/settlements/1/reject', { reason: '검토 필요' });
      expect(result.status).toBe('REJECTED');
    });
  });
});