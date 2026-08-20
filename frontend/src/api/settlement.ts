import api from './axios';
import { SettlementSearchRequest, SettlementSearchResponse, SettlementDetail } from '@/types';

/** 해제 예정 1건. */
export interface ReleasableLine {
  settlementId: number;
  paymentId: number;
  holdbackAmount: number;
  releaseDate: string;
}

/**
 * 홀드백 해제 미리보기.
 *
 * <p>{@code truncated} 가 true 면 limit 까지 가득 찼다는 뜻이다 — 화면이 이걸 숨기면 운영자가
 * 목록 길이를 전체 규모로 읽고 자금 계획을 세운다. count·totalAmount 도 잘린 범위의 값이다.
 */
export interface HoldbackReleasePreview {
  count: number;
  totalAmount: number;
  truncated: boolean;
  lines: ReleasableLine[];
}

export const settlementApi = {
  /**
   * 정산 복합 검색 (GET)
   * GET /api/settlements/search
   */
  search: async (params: SettlementSearchRequest): Promise<SettlementSearchResponse> => {
    const response = await api.get<SettlementSearchResponse>('/api/settlements/search', {
      params,
    });
    return response.data;
  },

  /**
   * 정산 복합 검색 (POST)
   * POST /api/settlements/search
   */
  searchByPost: async (request: SettlementSearchRequest): Promise<SettlementSearchResponse> => {
    const response = await api.post<SettlementSearchResponse>('/api/settlements/search', request);
    return response.data;
  },

  /**
   * 정산 상세 조회 (SettlementController @RequestMapping("/settlements"))
   * GET /settlements/{id}
   */
  getSettlement: async (id: number): Promise<SettlementDetail> => {
    const response = await api.get<SettlementDetail>(`/settlements/${id}`);
    return response.data;
  },

  /**
   * 홀드백 해제 미리보기 — GET /admin/settlements/holdback-preview (ADMIN).
   *
   * <p>조회 전용이다. 실제 해제는 배치나 재실행 콘솔의 몫이라, 이 호출로는 아무것도 풀리지 않는다.
   * {@code date} 를 미래로 주면 그날 풀릴 물량을 미리 볼 수 있다(자금 계획).
   */
  holdbackPreview: async (date?: string, limit?: number): Promise<HoldbackReleasePreview> => {
    const response = await api.get<HoldbackReleasePreview>(
      '/admin/settlements/holdback-preview', { params: { date, limit } });
    return response.data;
  },
};
