// Auth Types
export interface LoginRequest {
  email: string;
  password: string;
}

export interface RegisterRequest {
  email: string;
  password: string;
  role: 'USER' | 'ADMIN' | 'MANAGER';
}

export interface UserResponse {
  id: number;
  email: string;
  createdAt: string;
  updatedAt: string;
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
  status?: 'CALCULATED' | 'WAITING_APPROVAL' | 'APPROVED' | 'REJECTED' | 'PENDING' | 'CONFIRMED' | 'CANCELED';
  startDate?: string;
  endDate?: string;
  page?: number;
  size?: number;
  sortBy?: string;
  sortDirection?: 'ASC' | 'DESC';
}

export interface SettlementDetail {
  id: number;
  paymentId: number;
  orderId: number;
  amount: number;
  status: string;
  settlementDate: string;
  confirmedAt?: string;
  approvedBy?: number;
  approvedAt?: string;
  rejectedBy?: number;
  rejectedAt?: string;
  rejectionReason?: string;
  createdAt: string;
  updatedAt: string;
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
  productId: number;
  amount: number;
}

export interface OrderResponse {
  id: number;
  userId: number;
  productId: number;
  amount: number;
  status: string;
  createdAt: string;
  updatedAt: string;
}

// Multi-Item Order Types
export interface OrderItemRequest {
  productId: number;
  quantity: number;
}

export interface CreateMultiItemOrderRequest {
  userId: number;
  items: OrderItemRequest[];
  shippingAddressId?: number;
  couponCode?: string;
}

export interface OrderItemResponse {
  id: number;
  productId: number;
  quantity: number;
  unitPrice: number;
  subtotal: number;
  createdAt: string;
}

export interface MultiItemOrderResponse extends OrderResponse {
  items: OrderItemResponse[];
  shippingFee: number;
  discountAmount: number;
  totalAmount: number;
  couponCode?: string;
  itemCount: number;
}

// Payment Types
export interface PaymentRequest {
  orderId: number;
  paymentMethod: string;
}

export interface TossConfirmRequest {
  dbOrderId: number;
  paymentKey: string;
  tossOrderId: string;
  amount: number;
}

export interface TossCartConfirmRequest {
  orderIds: number[];
  paymentKey: string;
  tossOrderId: string;
  totalAmount: number;
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
  payment?: PaymentResponse;
}

// Product Types
export type ProductStatus = 'ACTIVE' | 'INACTIVE' | 'OUT_OF_STOCK' | 'DISCONTINUED';
export type StockOperation = 'INCREASE' | 'DECREASE';

export interface ProductCreateRequest {
  name: string;
  description?: string;
  price: number;
  stockQuantity: number;
}

export interface ProductResponse {
  id: number;
  name: string;
  description?: string;
  price: number;
  stockQuantity: number;
  status: ProductStatus;
  availableForSale: boolean;
  createdAt: string;
  updatedAt: string;
  primaryImageUrl?: string;
}

export interface UpdateProductInfoRequest {
  name?: string;
  description?: string;
}

export interface UpdateProductPriceRequest {
  newPrice: number;
}

export interface UpdateProductStockRequest {
  quantity: number;
  operation: StockOperation;
}

// Product Image Types
export interface ProductImageResponse {
  id: number;
  productId: number;
  originalFileName: string;
  storedFileName: string;
  filePath: string;
  url: string;
  contentType: string;
  sizeBytes: number;
  width?: number;
  height?: number;
  checksum?: string;
  isPrimary: boolean;
  orderIndex: number;
  createdAt: string;
  updatedAt: string;
}

// Review Types
export interface ReviewCreateRequest {
  productId: number;
  userId: number;
  rating: number; // 1-5
  content?: string;
}

export interface ReviewUpdateRequest {
  userId: number;
  rating: number;
  content?: string;
}

export interface ReviewResponse {
  id: number;
  productId: number;
  userId: number;
  rating: number;
  content?: string;
  createdAt: string;
  updatedAt: string;
}

// Category Types
export interface CategoryResponse {
  id: number;
  name: string;
  description?: string;
  parentId?: number;
  displayOrder: number;
  isActive: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface CreateCategoryRequest {
  name: string;
  description?: string;
  parentId?: number;
  displayOrder?: number;
}

export interface UpdateCategoryRequest {
  name?: string;
  description?: string;
  displayOrder?: number;
}

// Tag Types
export interface TagResponse {
  id: number;
  name: string;
  color: string;
  createdAt: string;
}

export interface CreateTagRequest {
  name: string;
  color: string;
}

export interface UpdateTagRequest {
  name?: string;
  color?: string;
}

// Coupon Types
export type CouponType = 'FIXED' | 'PERCENTAGE';

export interface CouponResponse {
  id: number;
  code: string;
  type: CouponType;
  discountValue: number;
  minOrderAmount: number;
  maxUses: number;
  usedCount: number;
  expiresAt?: string;
  isActive: boolean;
  createdAt: string;
}

export interface CouponValidateResponse {
  valid: boolean;
  message: string;
  discountAmount: number;
  finalAmount: number;
}

export interface CouponCreateRequest {
  code: string;
  type: CouponType;
  discountValue: number;
  minOrderAmount: number;
  maxUses: number;
  expiresAt?: string;
}

// Cart Types
export interface CartItemResponse {
  id: number;
  productId: number;
  productName?: string;
  quantity: number;
  priceSnapshot: number;
  subtotal: number;
  createdAt: string;
}

export interface CartResponse {
  id: number;
  userId: number;
  status: string;
  items: CartItemResponse[];
  totalAmount: number;
  totalItemCount: number;
}

export interface AddCartItemRequest {
  productId: number;
  quantity: number;
}

export interface UpdateCartItemRequest {
  quantity: number;
}

// Shipping
export type DeliveryStatus = 'PREPARING' | 'SHIPPED' | 'IN_TRANSIT' | 'OUT_FOR_DELIVERY' | 'DELIVERED' | 'CANCELED';

export interface ShippingAddressResponse {
  id: number;
  userId: number;
  recipientName: string;
  phone: string;
  zipCode: string;
  address: string;
  addressDetail?: string;
  isDefault: boolean;
}

export interface CreateShippingAddressRequest {
  userId: number;
  recipientName: string;
  phone: string;
  zipCode: string;
  address: string;
  addressDetail?: string;
}

export interface DeliveryResponse {
  id: number;
  orderId: number;
  status: DeliveryStatus;
  trackingNumber?: string;
  carrier?: string;
  recipientName: string;
  phone: string;
  address: string;
  shippingFee: number;
  shippedAt?: string;
  deliveredAt?: string;
  createdAt: string;
}

// Points
export type PointTransactionType = 'EARN' | 'USE' | 'CANCEL_EARN' | 'CANCEL_USE' | 'EXPIRE' | 'ADMIN_ADJUST';

export interface PointResponse {
  id: number;
  userId: number;
  balance: number;
  totalEarned: number;
  totalUsed: number;
}

export interface PointTransactionResponse {
  id: number;
  userId: number;
  type: PointTransactionType;
  amount: number;
  balanceAfter: number;
  description?: string;
  referenceType?: string;
  referenceId?: number;
  createdAt: string;
}

export interface EarnPointsRequest {
  userId: number;
  amount: number;
  description: string;
  referenceType?: string;
  referenceId?: number;
}

export interface UsePointsRequest {
  userId: number;
  amount: number;
  description: string;
  referenceType?: string;
  referenceId?: number;
}

// Wishlist
export interface WishlistItemResponse {
  id: number;
  userId: number;
  productId: number;
  createdAt: string;
}

// Notification
export type NotificationType = 'ORDER_CREATED' | 'ORDER_PAID' | 'ORDER_CANCELED' | 'PAYMENT_COMPLETED' | 'PAYMENT_REFUNDED' | 'DELIVERY_SHIPPED' | 'DELIVERY_DELIVERED' | 'RETURN_APPROVED' | 'RETURN_COMPLETED' | 'SETTLEMENT_CONFIRMED' | 'GENERAL';
export type NotificationChannel = 'EMAIL' | 'IN_APP' | 'SMS' | 'PUSH';
export type NotificationStatus = 'PENDING' | 'SENT' | 'FAILED' | 'READ';

export interface NotificationResponse {
  id: number;
  userId: number;
  type: NotificationType;
  channel: NotificationChannel;
  title: string;
  content: string;
  status: NotificationStatus;
  referenceType?: string;
  referenceId?: number;
  sentAt?: string;
  readAt?: string;
  createdAt: string;
}

// Returns
export type ReturnType = 'RETURN' | 'EXCHANGE';
export type ReturnStatus = 'REQUESTED' | 'APPROVED' | 'REJECTED' | 'SHIPPED' | 'RECEIVED' | 'COMPLETED' | 'CANCELED';
export type ReturnReason = 'DEFECTIVE' | 'WRONG_ITEM' | 'CHANGED_MIND' | 'SIZE_ISSUE' | 'QUALITY_ISSUE' | 'LATE_DELIVERY' | 'OTHER';

export interface CreateReturnRequest {
  orderId: number;
  userId: number;
  type: ReturnType;
  reason: ReturnReason;
  reasonDetail?: string;
  refundAmount?: number;
}

export interface ReturnResponse {
  id: number;
  orderId: number;
  userId: number;
  type: ReturnType;
  status: ReturnStatus;
  reason: ReturnReason;
  reasonDetail?: string;
  refundAmount?: number;
  exchangeOrderId?: number;
  trackingNumber?: string;
  carrier?: string;
  approvedAt?: string;
  receivedAt?: string;
  completedAt?: string;
  rejectedAt?: string;
  rejectionReason?: string;
  createdAt: string;
}

// Search
export interface ProductSearchResult {
  id: number;
  name: string;
  description: string;
  price: number;
  stockQuantity: number;
  status: string;
  categoryName?: string;
  tags?: string[];
}

export interface SearchPageResponse {
  content: ProductSearchResult[];
  totalElements: number;
  totalPages: number;
  page: number;
  size: number;
}

// Seller
export type SellerStatus = 'PENDING' | 'APPROVED' | 'SUSPENDED' | 'REJECTED';

export interface RegisterSellerRequest {
  userId: number;
  businessName: string;
  businessNumber: string;
  representativeName: string;
  phone: string;
  email: string;
}

export interface SellerResponse {
  id: number;
  userId: number;
  businessName: string;
  businessNumber: string;
  representativeName: string;
  phone: string;
  email: string;
  bankName?: string;
  bankAccountNumber?: string;
  bankAccountHolder?: string;
  commissionRate: number;
  status: SellerStatus;
  approvedAt?: string;
  createdAt: string;
}

export interface UpdateBankInfoRequest {
  bankName: string;
  bankAccountNumber: string;
  bankAccountHolder: string;
}

// Product Variants
export interface ProductOptionValueResponse {
  id: number;
  value: string;
  sortOrder: number;
}

export interface ProductOptionResponse {
  id: number;
  productId: number;
  name: string;
  sortOrder: number;
  values: ProductOptionValueResponse[];
}

export interface ProductVariantResponse {
  id: number;
  productId: number;
  sku: string;
  price: number;
  stockQuantity: number;
  optionValues: string;
  isActive: boolean;
}

export interface CreateProductOptionRequest {
  name: string;
  values: string[];
}

export interface CreateProductVariantRequest {
  sku: string;
  price: number;
  stockQuantity: number;
  optionValues: string;
}

// Social Login
export type SocialProvider = 'GOOGLE' | 'KAKAO' | 'NAVER';

export interface SocialLoginRequest {
  provider: SocialProvider;
  code: string;
  redirectUri: string;
}

export interface SocialLoginResponse {
  token: string;
  email: string;
  role: string;
  isNewUser: boolean;
}

export interface SocialAccountResponse {
  id: number;
  provider: SocialProvider;
  email?: string;
  name?: string;
  profileImage?: string;
  createdAt: string;
}
