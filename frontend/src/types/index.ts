// Auth Types
export interface LoginRequest {
  email: string;
  password: string;
}

export interface LoginResponse {
  token: string;
  email: string;
  role: string;
}

// Settlement Search Types
export interface SettlementSearchRequest {
  ordererName?: string;
  productName?: string;
  isRefunded?: boolean;
  status?: 'PENDING' | 'CONFIRMED' | 'COMPLETED';
  startDate?: string;
  endDate?: string;
  page?: number;
  size?: number;
  sortBy?: string;
  sortDirection?: 'ASC' | 'DESC';
}

export interface SettlementSearchItem {
  settlementId: number;
  orderId: number;
  paymentId: number;
  ordererName: string;
  productName: string;
  amount: number;
  refundedAmount: number;
  finalAmount: number;
  status: string;
  isRefunded: boolean;
  settlementDate: string;
  createdAt: string;
}

export interface SettlementAggregations {
  totalAmount: number;
  totalRefundedAmount: number;
  totalFinalAmount: number;
  statusCounts: Record<string, number>;
}

export interface SettlementSearchResponse {
  settlements: SettlementSearchItem[];
  totalElements: number;
  totalPages: number;
  currentPage: number;
  pageSize: number;
  aggregations: SettlementAggregations;
}

// Order Types
export interface OrderCreateRequest {
  userId: number;
  amount: number;
}

export interface OrderResponse {
  id: number;
  userId: number;
  amount: number;
  status: string;
  createdAt: string;
  updatedAt: string;
}

// Payment Types
export interface PaymentRequest {
  orderId: number;
  paymentMethod: string;
}

export interface PaymentResponse {
  id: number;
  orderId: number;
  amount: number;
  refundedAmount: number;
  paymentMethod: string;
  status: string;
  pgTransactionId?: string;
  capturedAt?: string;
  createdAt: string;
  updatedAt: string;
}

// Refund Types
export interface RefundRequest {
  amount: number;
  reason?: string;
}

export interface RefundResponse {
  id: number;
  paymentId: number;
  amount: number;
  reason?: string;
  status: string;
  idempotencyKey: string;
  createdAt: string;
  payment: PaymentResponse;
}
