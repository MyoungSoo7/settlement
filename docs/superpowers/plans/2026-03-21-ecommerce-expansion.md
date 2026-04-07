# E-Commerce System Expansion Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Expand the existing e-commerce system with 12 missing features across 3 phases: Cart, Multi-Item Order, Shipping, Stock Reservation, Return/Exchange, Notification, Product Variant, Wishlist, Seller, Search, Social Login, Points.

**Architecture:** Hexagonal architecture (ports & adapters) following existing domain patterns. Each new feature gets its own domain package with domain model (pure POJO), use case ports, persistence adapters (JPA + MapStruct), and REST controllers.

**Tech Stack:** Spring Boot 3.5, JPA/Hibernate, PostgreSQL, Flyway, MapStruct, Lombok, React 18 + TypeScript + Vite, Tailwind CSS

---

## Phase 1: Core Order Flow Completion

### Task 1: Cart Domain (Server Persistence)

**Files:**
- Create: `src/main/java/github/lms/lemuel/cart/domain/Cart.java`
- Create: `src/main/java/github/lms/lemuel/cart/domain/CartItem.java`
- Create: `src/main/java/github/lms/lemuel/cart/domain/CartStatus.java`
- Create: `src/main/java/github/lms/lemuel/cart/application/port/in/CartUseCase.java`
- Create: `src/main/java/github/lms/lemuel/cart/application/port/out/LoadCartPort.java`
- Create: `src/main/java/github/lms/lemuel/cart/application/port/out/SaveCartPort.java`
- Create: `src/main/java/github/lms/lemuel/cart/application/service/CartService.java`
- Create: `src/main/java/github/lms/lemuel/cart/adapter/out/persistence/CartJpaEntity.java`
- Create: `src/main/java/github/lms/lemuel/cart/adapter/out/persistence/CartItemJpaEntity.java`
- Create: `src/main/java/github/lms/lemuel/cart/adapter/out/persistence/SpringDataCartJpaRepository.java`
- Create: `src/main/java/github/lms/lemuel/cart/adapter/out/persistence/SpringDataCartItemJpaRepository.java`
- Create: `src/main/java/github/lms/lemuel/cart/adapter/out/persistence/CartPersistenceAdapter.java`
- Create: `src/main/java/github/lms/lemuel/cart/adapter/out/persistence/CartPersistenceMapper.java`
- Create: `src/main/java/github/lms/lemuel/cart/adapter/in/web/CartController.java`
- Create: `src/main/java/github/lms/lemuel/cart/adapter/in/web/request/AddCartItemRequest.java`
- Create: `src/main/java/github/lms/lemuel/cart/adapter/in/web/request/UpdateCartItemRequest.java`
- Create: `src/main/java/github/lms/lemuel/cart/adapter/in/web/response/CartResponse.java`
- Create: `src/main/resources/db/migration/V23__create_cart_tables.sql`
- Create: `frontend/src/api/cart.ts`
- Modify: `frontend/src/types/index.ts`
- Modify: `src/main/java/github/lms/lemuel/common/config/jwt/SecurityConfig.java`

- [ ] **Step 1: Create Flyway migration V23**
- [ ] **Step 2: Create Cart and CartItem domain entities**
- [ ] **Step 3: Create CartStatus enum**
- [ ] **Step 4: Create inbound port (CartUseCase)**
- [ ] **Step 5: Create outbound ports (LoadCartPort, SaveCartPort)**
- [ ] **Step 6: Create JPA entities (CartJpaEntity, CartItemJpaEntity)**
- [ ] **Step 7: Create Spring Data repositories**
- [ ] **Step 8: Create persistence mapper**
- [ ] **Step 9: Create persistence adapter**
- [ ] **Step 10: Create CartService**
- [ ] **Step 11: Create request/response DTOs**
- [ ] **Step 12: Create CartController**
- [ ] **Step 13: Update SecurityConfig for cart endpoints**
- [ ] **Step 14: Add frontend types and API module**

---

### Task 2: Multi-Item Order (OrderItem)

**Files:**
- Create: `src/main/java/github/lms/lemuel/order/domain/OrderItem.java`
- Create: `src/main/java/github/lms/lemuel/order/adapter/out/persistence/OrderItemJpaEntity.java`
- Create: `src/main/java/github/lms/lemuel/order/adapter/out/persistence/SpringDataOrderItemJpaRepository.java`
- Create: `src/main/java/github/lms/lemuel/order/adapter/in/web/request/CreateMultiItemOrderRequest.java`
- Create: `src/main/resources/db/migration/V24__create_order_items_table.sql`
- Modify: `src/main/java/github/lms/lemuel/order/domain/Order.java`
- Modify: `src/main/java/github/lms/lemuel/order/application/port/in/CreateOrderUseCase.java`
- Modify: `src/main/java/github/lms/lemuel/order/application/service/CreateOrderService.java`
- Modify: `src/main/java/github/lms/lemuel/order/adapter/in/web/OrderController.java`
- Modify: `src/main/java/github/lms/lemuel/order/adapter/in/web/response/OrderResponse.java`
- Modify: `frontend/src/types/index.ts`
- Modify: `frontend/src/api/order.ts`

- [ ] **Step 1: Create V24 migration for order_items table**
- [ ] **Step 2: Create OrderItem domain entity**
- [ ] **Step 3: Extend Order domain with items list, totalAmount, shippingFee, discountAmount**
- [ ] **Step 4: Create OrderItemJpaEntity**
- [ ] **Step 5: Update persistence layer for order items**
- [ ] **Step 6: Create CreateMultiItemOrderRequest**
- [ ] **Step 7: Update CreateOrderUseCase and service**
- [ ] **Step 8: Update OrderController with multi-item endpoint**
- [ ] **Step 9: Update OrderResponse to include items**
- [ ] **Step 10: Update frontend types and API**

---

### Task 3: Shipping Domain

**Files:**
- Create: `src/main/java/github/lms/lemuel/shipping/domain/ShippingAddress.java`
- Create: `src/main/java/github/lms/lemuel/shipping/domain/Delivery.java`
- Create: `src/main/java/github/lms/lemuel/shipping/domain/DeliveryStatus.java`
- Create: `src/main/java/github/lms/lemuel/shipping/application/port/in/ShippingAddressUseCase.java`
- Create: `src/main/java/github/lms/lemuel/shipping/application/port/in/DeliveryUseCase.java`
- Create: `src/main/java/github/lms/lemuel/shipping/application/port/out/LoadShippingAddressPort.java`
- Create: `src/main/java/github/lms/lemuel/shipping/application/port/out/SaveShippingAddressPort.java`
- Create: `src/main/java/github/lms/lemuel/shipping/application/port/out/LoadDeliveryPort.java`
- Create: `src/main/java/github/lms/lemuel/shipping/application/port/out/SaveDeliveryPort.java`
- Create: `src/main/java/github/lms/lemuel/shipping/application/service/ShippingAddressService.java`
- Create: `src/main/java/github/lms/lemuel/shipping/application/service/DeliveryService.java`
- Create: `src/main/java/github/lms/lemuel/shipping/adapter/out/persistence/ShippingAddressJpaEntity.java`
- Create: `src/main/java/github/lms/lemuel/shipping/adapter/out/persistence/DeliveryJpaEntity.java`
- Create: `src/main/java/github/lms/lemuel/shipping/adapter/out/persistence/SpringDataShippingAddressRepository.java`
- Create: `src/main/java/github/lms/lemuel/shipping/adapter/out/persistence/SpringDataDeliveryRepository.java`
- Create: `src/main/java/github/lms/lemuel/shipping/adapter/out/persistence/ShippingPersistenceAdapter.java`
- Create: `src/main/java/github/lms/lemuel/shipping/adapter/out/persistence/ShippingPersistenceMapper.java`
- Create: `src/main/java/github/lms/lemuel/shipping/adapter/in/web/ShippingAddressController.java`
- Create: `src/main/java/github/lms/lemuel/shipping/adapter/in/web/DeliveryController.java`
- Create: `src/main/java/github/lms/lemuel/shipping/adapter/in/web/request/CreateShippingAddressRequest.java`
- Create: `src/main/java/github/lms/lemuel/shipping/adapter/in/web/request/UpdateDeliveryRequest.java`
- Create: `src/main/java/github/lms/lemuel/shipping/adapter/in/web/response/ShippingAddressResponse.java`
- Create: `src/main/java/github/lms/lemuel/shipping/adapter/in/web/response/DeliveryResponse.java`
- Create: `src/main/resources/db/migration/V25__create_shipping_tables.sql`
- Create: `frontend/src/api/shipping.ts`
- Modify: `frontend/src/types/index.ts`
- Modify: `src/main/java/github/lms/lemuel/common/config/jwt/SecurityConfig.java`

- [ ] **Step 1: Create V25 migration for shipping_addresses and deliveries tables**
- [ ] **Step 2: Create ShippingAddress domain entity**
- [ ] **Step 3: Create Delivery domain entity with DeliveryStatus enum**
- [ ] **Step 4: Create inbound/outbound ports**
- [ ] **Step 5: Create JPA entities**
- [ ] **Step 6: Create repositories and persistence adapter**
- [ ] **Step 7: Create services**
- [ ] **Step 8: Create request/response DTOs**
- [ ] **Step 9: Create controllers**
- [ ] **Step 10: Update SecurityConfig**
- [ ] **Step 11: Add frontend types and API module**

---

### Task 4: Stock Reservation System

**Files:**
- Create: `src/main/java/github/lms/lemuel/product/domain/StockReservation.java`
- Create: `src/main/java/github/lms/lemuel/product/domain/ReservationStatus.java`
- Create: `src/main/java/github/lms/lemuel/product/application/port/in/StockReservationUseCase.java`
- Create: `src/main/java/github/lms/lemuel/product/application/port/out/LoadStockReservationPort.java`
- Create: `src/main/java/github/lms/lemuel/product/application/port/out/SaveStockReservationPort.java`
- Create: `src/main/java/github/lms/lemuel/product/application/service/StockReservationService.java`
- Create: `src/main/java/github/lms/lemuel/product/adapter/out/persistence/StockReservationJpaEntity.java`
- Create: `src/main/java/github/lms/lemuel/product/adapter/out/persistence/SpringDataStockReservationRepository.java`
- Create: `src/main/java/github/lms/lemuel/product/adapter/out/persistence/StockReservationPersistenceAdapter.java`
- Create: `src/main/java/github/lms/lemuel/common/config/StockReservationScheduler.java`
- Create: `src/main/resources/db/migration/V26__create_stock_reservations_table.sql`

- [ ] **Step 1: Create V26 migration for stock_reservations table**
- [ ] **Step 2: Create StockReservation domain and ReservationStatus enum**
- [ ] **Step 3: Create ports (in/out)**
- [ ] **Step 4: Create JPA entity and repository**
- [ ] **Step 5: Create persistence adapter**
- [ ] **Step 6: Create StockReservationService with reserve/confirm/release**
- [ ] **Step 7: Create scheduler for expired reservation cleanup (30min TTL)**
- [ ] **Step 8: Integrate with order creation flow**

---

## Phase 2: Operational Essentials

### Task 5: Return/Exchange Workflow

**Files:**
- Create: `src/main/java/github/lms/lemuel/returns/domain/Return.java`
- Create: `src/main/java/github/lms/lemuel/returns/domain/ReturnStatus.java`
- Create: `src/main/java/github/lms/lemuel/returns/domain/ReturnReason.java`
- Create: `src/main/java/github/lms/lemuel/returns/domain/ReturnType.java`
- Create: `src/main/java/github/lms/lemuel/returns/application/port/in/ReturnUseCase.java`
- Create: `src/main/java/github/lms/lemuel/returns/application/port/out/LoadReturnPort.java`
- Create: `src/main/java/github/lms/lemuel/returns/application/port/out/SaveReturnPort.java`
- Create: `src/main/java/github/lms/lemuel/returns/application/service/ReturnService.java`
- Create: `src/main/java/github/lms/lemuel/returns/adapter/out/persistence/ReturnJpaEntity.java`
- Create: `src/main/java/github/lms/lemuel/returns/adapter/out/persistence/SpringDataReturnRepository.java`
- Create: `src/main/java/github/lms/lemuel/returns/adapter/out/persistence/ReturnPersistenceAdapter.java`
- Create: `src/main/java/github/lms/lemuel/returns/adapter/out/persistence/ReturnPersistenceMapper.java`
- Create: `src/main/java/github/lms/lemuel/returns/adapter/in/web/ReturnController.java`
- Create: `src/main/java/github/lms/lemuel/returns/adapter/in/web/request/CreateReturnRequest.java`
- Create: `src/main/java/github/lms/lemuel/returns/adapter/in/web/response/ReturnResponse.java`
- Create: `src/main/resources/db/migration/V27__create_returns_table.sql`
- Create: `frontend/src/api/returns.ts`
- Modify: `frontend/src/types/index.ts`

- [ ] **Step 1: Create V27 migration**
- [ ] **Step 2: Create domain entities (Return, ReturnStatus, ReturnReason, ReturnType)**
- [ ] **Step 3: Create ports**
- [ ] **Step 4: Create persistence layer**
- [ ] **Step 5: Create ReturnService**
- [ ] **Step 6: Create controller and DTOs**
- [ ] **Step 7: Add frontend types and API**

---

### Task 6: Notification System

**Files:**
- Create: `src/main/java/github/lms/lemuel/notification/domain/Notification.java`
- Create: `src/main/java/github/lms/lemuel/notification/domain/NotificationType.java`
- Create: `src/main/java/github/lms/lemuel/notification/domain/NotificationChannel.java`
- Create: `src/main/java/github/lms/lemuel/notification/domain/NotificationStatus.java`
- Create: `src/main/java/github/lms/lemuel/notification/application/port/in/NotificationUseCase.java`
- Create: `src/main/java/github/lms/lemuel/notification/application/port/out/LoadNotificationPort.java`
- Create: `src/main/java/github/lms/lemuel/notification/application/port/out/SaveNotificationPort.java`
- Create: `src/main/java/github/lms/lemuel/notification/application/port/out/SendNotificationPort.java`
- Create: `src/main/java/github/lms/lemuel/notification/application/service/NotificationService.java`
- Create: `src/main/java/github/lms/lemuel/notification/adapter/out/persistence/NotificationJpaEntity.java`
- Create: `src/main/java/github/lms/lemuel/notification/adapter/out/persistence/SpringDataNotificationRepository.java`
- Create: `src/main/java/github/lms/lemuel/notification/adapter/out/persistence/NotificationPersistenceAdapter.java`
- Create: `src/main/java/github/lms/lemuel/notification/adapter/out/persistence/NotificationPersistenceMapper.java`
- Create: `src/main/java/github/lms/lemuel/notification/adapter/out/email/EmailNotificationAdapter.java`
- Create: `src/main/java/github/lms/lemuel/notification/adapter/in/web/NotificationController.java`
- Create: `src/main/java/github/lms/lemuel/notification/adapter/in/web/response/NotificationResponse.java`
- Create: `src/main/resources/db/migration/V28__create_notifications_table.sql`
- Create: `frontend/src/api/notification.ts`
- Modify: `frontend/src/types/index.ts`

- [ ] **Step 1: Create V28 migration**
- [ ] **Step 2: Create domain entities and enums**
- [ ] **Step 3: Create ports (including SendNotificationPort)**
- [ ] **Step 4: Create persistence layer**
- [ ] **Step 5: Create EmailNotificationAdapter**
- [ ] **Step 6: Create NotificationService**
- [ ] **Step 7: Create controller**
- [ ] **Step 8: Add frontend types and API**

---

### Task 7: Product Variant/Options

**Files:**
- Create: `src/main/java/github/lms/lemuel/product/domain/ProductOption.java`
- Create: `src/main/java/github/lms/lemuel/product/domain/ProductOptionValue.java`
- Create: `src/main/java/github/lms/lemuel/product/domain/ProductVariant.java`
- Create: `src/main/java/github/lms/lemuel/product/application/port/in/ProductVariantUseCase.java`
- Create: `src/main/java/github/lms/lemuel/product/application/port/out/LoadProductVariantPort.java`
- Create: `src/main/java/github/lms/lemuel/product/application/port/out/SaveProductVariantPort.java`
- Create: `src/main/java/github/lms/lemuel/product/application/service/ProductVariantService.java`
- Create: `src/main/java/github/lms/lemuel/product/adapter/out/persistence/ProductOptionJpaEntity.java`
- Create: `src/main/java/github/lms/lemuel/product/adapter/out/persistence/ProductOptionValueJpaEntity.java`
- Create: `src/main/java/github/lms/lemuel/product/adapter/out/persistence/ProductVariantJpaEntity.java`
- Create: `src/main/java/github/lms/lemuel/product/adapter/out/persistence/SpringDataProductOptionRepository.java`
- Create: `src/main/java/github/lms/lemuel/product/adapter/out/persistence/SpringDataProductVariantRepository.java`
- Create: `src/main/java/github/lms/lemuel/product/adapter/out/persistence/ProductVariantPersistenceAdapter.java`
- Create: `src/main/java/github/lms/lemuel/product/adapter/in/web/ProductVariantController.java`
- Create: `src/main/java/github/lms/lemuel/product/adapter/in/web/request/CreateProductOptionRequest.java`
- Create: `src/main/java/github/lms/lemuel/product/adapter/in/web/request/CreateProductVariantRequest.java`
- Create: `src/main/java/github/lms/lemuel/product/adapter/in/web/response/ProductVariantResponse.java`
- Create: `src/main/resources/db/migration/V29__create_product_variants_tables.sql`
- Create: `frontend/src/api/productVariant.ts`
- Modify: `frontend/src/types/index.ts`

- [ ] **Step 1: Create V29 migration for options, option_values, variants**
- [ ] **Step 2: Create domain entities**
- [ ] **Step 3: Create ports**
- [ ] **Step 4: Create persistence layer**
- [ ] **Step 5: Create ProductVariantService**
- [ ] **Step 6: Create controller and DTOs**
- [ ] **Step 7: Add frontend types and API**

---

### Task 8: Wishlist

**Files:**
- Create: `src/main/java/github/lms/lemuel/wishlist/domain/Wishlist.java`
- Create: `src/main/java/github/lms/lemuel/wishlist/domain/WishlistItem.java`
- Create: `src/main/java/github/lms/lemuel/wishlist/application/port/in/WishlistUseCase.java`
- Create: `src/main/java/github/lms/lemuel/wishlist/application/port/out/LoadWishlistPort.java`
- Create: `src/main/java/github/lms/lemuel/wishlist/application/port/out/SaveWishlistPort.java`
- Create: `src/main/java/github/lms/lemuel/wishlist/application/service/WishlistService.java`
- Create: `src/main/java/github/lms/lemuel/wishlist/adapter/out/persistence/WishlistItemJpaEntity.java`
- Create: `src/main/java/github/lms/lemuel/wishlist/adapter/out/persistence/SpringDataWishlistRepository.java`
- Create: `src/main/java/github/lms/lemuel/wishlist/adapter/out/persistence/WishlistPersistenceAdapter.java`
- Create: `src/main/java/github/lms/lemuel/wishlist/adapter/out/persistence/WishlistPersistenceMapper.java`
- Create: `src/main/java/github/lms/lemuel/wishlist/adapter/in/web/WishlistController.java`
- Create: `src/main/java/github/lms/lemuel/wishlist/adapter/in/web/response/WishlistResponse.java`
- Create: `src/main/resources/db/migration/V30__create_wishlist_table.sql`
- Create: `frontend/src/api/wishlist.ts`
- Modify: `frontend/src/types/index.ts`

- [ ] **Step 1: Create V30 migration**
- [ ] **Step 2: Create domain entities**
- [ ] **Step 3: Create ports**
- [ ] **Step 4: Create persistence layer**
- [ ] **Step 5: Create WishlistService**
- [ ] **Step 6: Create controller**
- [ ] **Step 7: Add frontend types and API**

---

## Phase 3: Expansion

### Task 9: Seller Domain (Marketplace)

**Files:**
- Create: `src/main/java/github/lms/lemuel/seller/domain/Seller.java`
- Create: `src/main/java/github/lms/lemuel/seller/domain/SellerStatus.java`
- Create: `src/main/java/github/lms/lemuel/seller/application/port/in/SellerUseCase.java`
- Create: `src/main/java/github/lms/lemuel/seller/application/port/out/LoadSellerPort.java`
- Create: `src/main/java/github/lms/lemuel/seller/application/port/out/SaveSellerPort.java`
- Create: `src/main/java/github/lms/lemuel/seller/application/service/SellerService.java`
- Create: `src/main/java/github/lms/lemuel/seller/adapter/out/persistence/SellerJpaEntity.java`
- Create: `src/main/java/github/lms/lemuel/seller/adapter/out/persistence/SpringDataSellerRepository.java`
- Create: `src/main/java/github/lms/lemuel/seller/adapter/out/persistence/SellerPersistenceAdapter.java`
- Create: `src/main/java/github/lms/lemuel/seller/adapter/out/persistence/SellerPersistenceMapper.java`
- Create: `src/main/java/github/lms/lemuel/seller/adapter/in/web/SellerController.java`
- Create: `src/main/java/github/lms/lemuel/seller/adapter/in/web/request/RegisterSellerRequest.java`
- Create: `src/main/java/github/lms/lemuel/seller/adapter/in/web/response/SellerResponse.java`
- Create: `src/main/resources/db/migration/V31__create_sellers_table.sql`
- Create: `frontend/src/api/seller.ts`
- Modify: `frontend/src/types/index.ts`

- [ ] **Step 1: Create V31 migration**
- [ ] **Step 2: Create domain entities**
- [ ] **Step 3: Create ports**
- [ ] **Step 4: Create persistence layer**
- [ ] **Step 5: Create SellerService**
- [ ] **Step 6: Create controller and DTOs**
- [ ] **Step 7: Add frontend types and API**

---

### Task 10: Product Search (Elasticsearch)

**Files:**
- Create: `src/main/java/github/lms/lemuel/product/adapter/out/search/ProductSearchAdapter.java`
- Create: `src/main/java/github/lms/lemuel/product/adapter/out/search/ProductSearchDocument.java`
- Create: `src/main/java/github/lms/lemuel/product/adapter/out/search/ProductSearchRepository.java`
- Create: `src/main/java/github/lms/lemuel/product/application/port/in/SearchProductUseCase.java`
- Create: `src/main/java/github/lms/lemuel/product/application/port/out/SearchProductPort.java`
- Create: `src/main/java/github/lms/lemuel/product/application/service/ProductSearchService.java`
- Create: `src/main/java/github/lms/lemuel/product/adapter/in/web/ProductSearchController.java`
- Create: `src/main/java/github/lms/lemuel/product/adapter/in/web/response/ProductSearchResponse.java`
- Create: `frontend/src/api/search.ts`
- Modify: `frontend/src/types/index.ts`

- [ ] **Step 1: Create ProductSearchDocument (ES index mapping)**
- [ ] **Step 2: Create SearchProductPort**
- [ ] **Step 3: Create ProductSearchRepository**
- [ ] **Step 4: Create ProductSearchAdapter**
- [ ] **Step 5: Create ProductSearchService**
- [ ] **Step 6: Create ProductSearchController**
- [ ] **Step 7: Add frontend types and API**

---

### Task 11: Social Login (OAuth2)

**Files:**
- Create: `src/main/java/github/lms/lemuel/user/domain/SocialProvider.java`
- Create: `src/main/java/github/lms/lemuel/user/domain/SocialAccount.java`
- Create: `src/main/java/github/lms/lemuel/user/application/port/in/SocialLoginUseCase.java`
- Create: `src/main/java/github/lms/lemuel/user/application/service/SocialLoginService.java`
- Create: `src/main/java/github/lms/lemuel/user/adapter/out/persistence/SocialAccountJpaEntity.java`
- Create: `src/main/java/github/lms/lemuel/user/adapter/out/persistence/SpringDataSocialAccountRepository.java`
- Create: `src/main/java/github/lms/lemuel/user/adapter/in/web/SocialLoginController.java`
- Create: `src/main/resources/db/migration/V32__create_social_accounts_table.sql`
- Create: `frontend/src/api/socialLogin.ts`
- Modify: `frontend/src/types/index.ts`
- Modify: `src/main/java/github/lms/lemuel/common/config/jwt/SecurityConfig.java`

- [ ] **Step 1: Create V32 migration**
- [ ] **Step 2: Create domain entities (SocialProvider, SocialAccount)**
- [ ] **Step 3: Create SocialLoginUseCase**
- [ ] **Step 4: Create persistence layer**
- [ ] **Step 5: Create SocialLoginService**
- [ ] **Step 6: Create SocialLoginController**
- [ ] **Step 7: Update SecurityConfig for OAuth2 endpoints**
- [ ] **Step 8: Add frontend types and API**

---

### Task 12: Points/Rewards System

**Files:**
- Create: `src/main/java/github/lms/lemuel/point/domain/Point.java`
- Create: `src/main/java/github/lms/lemuel/point/domain/PointTransaction.java`
- Create: `src/main/java/github/lms/lemuel/point/domain/PointTransactionType.java`
- Create: `src/main/java/github/lms/lemuel/point/application/port/in/PointUseCase.java`
- Create: `src/main/java/github/lms/lemuel/point/application/port/out/LoadPointPort.java`
- Create: `src/main/java/github/lms/lemuel/point/application/port/out/SavePointPort.java`
- Create: `src/main/java/github/lms/lemuel/point/application/service/PointService.java`
- Create: `src/main/java/github/lms/lemuel/point/adapter/out/persistence/PointJpaEntity.java`
- Create: `src/main/java/github/lms/lemuel/point/adapter/out/persistence/PointTransactionJpaEntity.java`
- Create: `src/main/java/github/lms/lemuel/point/adapter/out/persistence/SpringDataPointRepository.java`
- Create: `src/main/java/github/lms/lemuel/point/adapter/out/persistence/SpringDataPointTransactionRepository.java`
- Create: `src/main/java/github/lms/lemuel/point/adapter/out/persistence/PointPersistenceAdapter.java`
- Create: `src/main/java/github/lms/lemuel/point/adapter/out/persistence/PointPersistenceMapper.java`
- Create: `src/main/java/github/lms/lemuel/point/adapter/in/web/PointController.java`
- Create: `src/main/java/github/lms/lemuel/point/adapter/in/web/response/PointResponse.java`
- Create: `src/main/java/github/lms/lemuel/point/adapter/in/web/response/PointTransactionResponse.java`
- Create: `src/main/resources/db/migration/V33__create_points_tables.sql`
- Create: `frontend/src/api/point.ts`
- Modify: `frontend/src/types/index.ts`

- [ ] **Step 1: Create V33 migration for points and point_transactions**
- [ ] **Step 2: Create domain entities**
- [ ] **Step 3: Create ports**
- [ ] **Step 4: Create persistence layer**
- [ ] **Step 5: Create PointService**
- [ ] **Step 6: Create controller**
- [ ] **Step 7: Add frontend types and API**
