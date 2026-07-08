import api from './axios';
import { SettlementSearchRequest, SettlementSearchResponse, SettlementDetail } from '@/types';

export const settlementApi = {
  /**
   * 정산 복합 검색 (GET)
   * GET /api/settlements/search — settlement-service(SettlementSearchController) 소관.
   * (INC-2026-0708 후속: 이 경로는 원래 유효했다 — 장애 원인은 서비스 미배포·라우팅 부재였음)
   */
  search: async (params: SettlementSearchRequest): Promise<SettlementSearchResponse> => {
    const response = await api.get<SettlementSearchResponse>('/api/settlements/search', {
      params,
    });
    return response.data;
  },

  /**
   * @deprecated 백엔드는 GET 검색만 제공 — GET search 로 위임 (미사용, 호환용).
   */
  searchByPost: async (request: SettlementSearchRequest): Promise<SettlementSearchResponse> => {
    return settlementApi.search(request);
  },

  /**
   * 정산 상세 조회 (SettlementController @RequestMapping("/settlements"))
   * GET /settlements/{id}
   */
  getSettlement: async (id: number): Promise<SettlementDetail> => {
    const response = await api.get<SettlementDetail>(`/settlements/${id}`);
    return response.data;
  },
};
