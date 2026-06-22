import api from './axios';

/** 시공 예약 등록 요청 (companyId 는 서버가 토큰에서 추출) */
export interface ReservationCreateRequest {
  scheduledDate: string;          // yyyy-MM-dd (필수)
  siteAddress: string;            // 필수
  sitePassword?: string;
  siteManagerName: string;        // 필수
  siteManagerPhone: string;       // 필수
  productId?: number | null;
  woodSpecies?: string;
  brand?: string;
  productName?: string;
  productSize?: string;
  constructionArea: number;       // 필수, > 0
  fieldMeasured?: boolean;
  expansion?: boolean;
  expansionArea?: number | null;
  newFloor?: boolean;
  baseboard?: boolean;
  protectionWork?: boolean;
  protectionArea?: number | null;
  note?: string;
}

export interface ReservationResponse {
  id: number;
  companyId: number;
  technicianId: number | null;
  status: string;                 // REQUESTED|CONFIRMED|ASSIGNED|IN_PROGRESS|COMPLETED|CANCELED
  scheduledDate: string;
  siteAddress: string;
  siteManagerName: string;
  siteManagerPhone: string;
  productId: number | null;
  woodSpecies: string | null;
  brand: string | null;
  productName: string | null;
  productSize: string | null;
  constructionArea: number;
  fieldMeasured: boolean;
  expansion: boolean;
  expansionArea: number | null;
  newFloor: boolean;
  baseboard: boolean;
  protectionWork: boolean;
  protectionArea: number | null;
  protectionFee: number | null;
  additionalFee: number | null;
  note: string | null;
  createdAt: string;
}

export const reservationApi = {
  /** 시공 예약 등록 — COMPANY 또는 ADMIN. POST /reservations */
  register: async (req: ReservationCreateRequest): Promise<ReservationResponse> => {
    const res = await api.post<ReservationResponse>('/reservations', req);
    return res.data;
  },

  /** 예약 단건 조회. GET /reservations/{id} */
  getById: async (id: number): Promise<ReservationResponse> => {
    const res = await api.get<ReservationResponse>(`/reservations/${id}`);
    return res.data;
  },

  /** 내 예약(업체 회원). GET /reservations/my */
  getMine: async (): Promise<ReservationResponse[]> => {
    const res = await api.get<ReservationResponse[]>('/reservations/my');
    return res.data;
  },

  /** 내 배정 작업(시공기사). GET /reservations/assigned/my */
  getMyAssignments: async (): Promise<ReservationResponse[]> => {
    const res = await api.get<ReservationResponse[]>('/reservations/assigned/my');
    return res.data;
  },

  /** 관리자 대시보드 — date/status 선택. GET /reservations/admin */
  adminSearch: async (date?: string, status?: string): Promise<ReservationResponse[]> => {
    const params = new URLSearchParams();
    if (date) params.append('date', date);
    if (status) params.append('status', status);
    const qs = params.toString();
    const res = await api.get<ReservationResponse[]>(`/reservations/admin${qs ? `?${qs}` : ''}`);
    return res.data;
  },

  // ── 상태 전이 (ADMIN/MANAGER) ──
  confirm:  async (id: number) => (await api.post<ReservationResponse>(`/reservations/${id}/confirm`)).data,
  assign:   async (id: number, technicianId: number) =>
    (await api.post<ReservationResponse>(`/reservations/${id}/assign`, { technicianId })).data,
  reassign: async (id: number, technicianId: number) =>
    (await api.post<ReservationResponse>(`/reservations/${id}/reassign`, { technicianId })).data,
  start:    async (id: number) => (await api.post<ReservationResponse>(`/reservations/${id}/start`)).data,
  complete: async (id: number) => (await api.post<ReservationResponse>(`/reservations/${id}/complete`)).data,
  cancel:   async (id: number, reason?: string) =>
    (await api.post<ReservationResponse>(`/reservations/${id}/cancel`, { reason })).data,
};

export const RESERVATION_STATUSES = [
  'REQUESTED', 'CONFIRMED', 'ASSIGNED', 'IN_PROGRESS', 'COMPLETED', 'CANCELED',
] as const;
