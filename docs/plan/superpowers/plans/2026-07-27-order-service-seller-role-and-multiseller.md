# Seller Role and Multi-Seller Order Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `SELLER`를 승인형 판매 전용 계정으로 도입하고, 판매자별 상품 소유권·멀티셀러 주문·단일 결제의 판매자별 배분·배분 단위 정산/환불을 안전하게 구현한다.

**Architecture:** 먼저 DB 기준 역할/토큰/소유권 경계를 세우고, `CheckoutGroup` 아래에 판매자별 `Order`를 생성한다. 고객의 PG 결제는 하나의 `Payment`로 유지하되 `PaymentAllocation`을 주문·판매자별로 확정하고, 정산과 환불은 allocation 이벤트를 Outbox로 전달한다. 기존 결제/정산은 expand-and-contract 방식으로 유지하며 allocation backfill 및 검증 뒤에만 구 제약을 제거한다.

**Tech Stack:** Java 21, Spring Boot, Spring Security, JPA, Flyway, PostgreSQL, Kafka/Outbox, JUnit 5, Mockito, Testcontainers, Gradle

## Global Constraints

- 승인 설계: `..-26-order-service-seller-role-design.md`
- 역할: `USER`는 구매, `SELLER`는 판매만, `MANAGER`는 플랫폼 운영 및 전체 판매자 대행 등록/구매, `ADMIN`은 시스템 관리 및 전체 판매자 대행 등록/구매가 가능하다.
- 공개 가입은 `USER`, `SELLER`와 기존 호환 역할만 허용한다. `MANAGER`, `ADMIN` 공개 가입은 거부한다. `SELLER`는 승인 전 `PENDING`이다.
- 모든 보호 요청은 JWT의 `uid`, `role`, `tokenVersion`을 DB 상태와 대조한다. 누락된 구 토큰은 거부한다.
- 금액은 `BigDecimal`만 사용하고 나눗셈/비율 연산은 `RoundingMode.HALF_UP`을 명시한다.
- payment/order/settlement/ledger/refund 경로의 금액 관련 파일은 수정 전에 MCP `guard_check(file_path, content)`를 통과해야 한다.
- 이벤트 발행은 `kafkaTemplate.send()`가 아니라 동일 트랜잭션의 `outbox_events` INSERT를 사용한다.
- Kafka 컨슈머는 `processed_events(consumer_group, event_id)`로 멱등 처리한다.
- 정산·원장·지급 이력은 UPDATE/DELETE하지 않는다. 환불은 adjustment와 reversal 레코드를 추가한다.
- 과거 Flyway 파일은 수정하지 않는다. 신규 timestamp migration만 추가한다.
- 오류 계약: 공개 특권역할 가입/잘못된 SELLER 대상은 400, stale token은 401, 역할·소유권·운영환경 위반은 403(존재 은닉 API는 일관된 404), fingerprint·금액 합계·초과 환불 충돌은 409로 매핑한다.
- 운영/스테이징 DB에 직접 접속하지 않는다. backfill은 애플리케이션 migration/job과 이벤트 replay로 수행한다.
- 이 계획의 각 작업은 RED → GREEN → 관련 테스트 → 명시된 파일만 커밋 순서로 수행한다.

---

## Task 1: SELLER 역할, 공개 가입 allowlist, 승인 및 로그인 차단

**Files:**
- Modify: `../../../../order-service/src/main/java/github/lms/lemuel/user/domain/UserRole.java`
- Modify: `../../../../order-service/src/main/java/github/lms/lemuel/user/domain/User.java`
- Modify: `../../../../order-service/src/main/java/github/lms/lemuel/user/adapter/in/web/request/CreateUserRequest.java`
- Modify: `../../../../order-service/src/main/java/github/lms/lemuel/user/application/port/in/CreateUserUseCase.java`
- Modify: `../../../../order-service/src/main/java/github/lms/lemuel/user/application/service/CreateUserService.java`
- Modify: `../../../../order-service/src/main/java/github/lms/lemuel/user/application/service/MembershipApprovalService.java`
- Modify: `../../../../order-service/src/main/java/github/lms/lemuel/user/application/service/LoginService.java`
- Modify: `../../../../shared-common/src/main/java/github/lms/lemuel/common/config/jwt/SecurityConfig.java`
- Test: `../../../../order-service/src/test/java/github/lms/lemuel/user/domain/UserRoleTest.java`
- Test: `../../../../order-service/src/test/java/github/lms/lemuel/user/domain/UserMembershipTest.java`
- Test: `../../../../order-service/src/test/java/github/lms/lemuel/user/application/service/CreateUserServiceTest.java`
- Test: `../../../../order-service/src/test/java/github/lms/lemuel/user/application/service/MembershipApprovalServiceTest.java`
- Test: `../../../../order-service/src/test/java/github/lms/lemuel/user/application/service/LoginServiceTest.java`
- Test: `../../../../order-service/src/test/java/github/lms/lemuel/user/adapter/in/web/UserControllerTest.java`
- Test: `../../../../order-service/src/test/java/github/lms/lemuel/user/adapter/in/web/MembershipControllerTest.java`
- Test: `../../../../order-service/src/test/java/github/lms/lemuel/user/adapter/in/web/AuthControllerSecurityTest.java`

**Interfaces:**
- `UserRole.SELLER`
- `boolean UserRole.isPublicSignupRole()`
- `boolean User.requiresApproval()`
- `void User.approveMembership()` changes domain state; `MembershipApprovalService.approve(Long userId, Long processedBy)` remains responsible for approver audit persistence.
- `LoginResult LoginService.login(LoginCommand command)` must reject inactive/PENDING users.

- [ ] Add failing role and signup tests.

```java
assertThat(UserRole.SELLER.isPublicSignupRole()).isTrue();
assertThat(UserRole.MANAGER.isPublicSignupRole()).isFalse();
assertThat(UserRole.ADMIN.isPublicSignupRole()).isFalse();
assertThatThrownBy(() -> service.createUser(new CreateUserCommand("ops", "pw", UserRole.MANAGER)))
    .isInstanceOf(IllegalArgumentException.class);
```

- [ ] Run `./gradlew.bat :order-service:test --tests "github.lms.lemuel.user.*"`; expect failures for missing `SELLER` and allowlist behavior.
- [ ] Add `SELLER`, centralize the public signup allowlist, initialize SELLER membership as PENDING, and require MANAGER/ADMIN for approval.

```java
public enum UserRole {
    USER, SELLER, MANAGER, ADMIN, CUSTOMER, COMPANY, TECHNICIAN;

    public boolean isPublicSignupRole() {
        return this == USER || this == SELLER
            || this == CUSTOMER || this == COMPANY || this == TECHNICIAN;
    }
}
```

- [ ] Make `LoginService` reject users for which `!user.canUseService()` before issuing any token.
- [ ] Add explicit SELLER route rules before `anyRequest()` in `SecurityConfig`; keep fine-grained ownership checks in services.
- [ ] Re-run the user/auth tests; expect PASS.
- [ ] Commit only Task 1 files: `git commit -m "feat(order): add approved seller account role"`.

## Task 2: DB-authoritative tokenVersion and immediate token revocation

**Files:**
- Create: `order-service/src/main/resources/db/migration/V20260727090000__users_token_version.sql`
- Modify: `../../../../order-service/src/main/java/github/lms/lemuel/user/domain/User.java`
- Modify: `../../../../order-service/src/main/java/github/lms/lemuel/user/adapter/out/persistence/UserJpaEntity.java`
- Modify: `../../../../order-service/src/main/java/github/lms/lemuel/user/adapter/out/persistence/UserPersistenceMapper.java`
- Modify: `../../../../order-service/src/main/java/github/lms/lemuel/user/application/service/LoginService.java`
- Modify: `../../../../order-service/src/main/java/github/lms/lemuel/user/application/service/PasswordResetService.java`
- Modify: `../../../../order-service/src/main/java/github/lms/lemuel/user/application/service/MembershipApprovalService.java`
- Modify: `../../../../order-service/src/main/java/github/lms/lemuel/user/application/service/DemoLoginService.java`
- Modify: `../../../../order-service/src/main/java/github/lms/lemuel/user/adapter/in/web/UserController.java`
- Modify: `../../../../order-service/src/main/java/github/lms/lemuel/user/application/port/out/TokenProviderPort.java`
- Modify: `../../../../order-service/src/main/java/github/lms/lemuel/user/adapter/out/security/JwtTokenProviderAdapter.java`
- Modify: `../../../../shared-common/src/main/java/github/lms/lemuel/common/config/jwt/JwtUtil.java`
- Modify: `../../../../shared-common/src/main/java/github/lms/lemuel/common/config/jwt/JwtAuthenticationFilter.java`
- Modify: `../../../../shared-common/src/main/java/github/lms/lemuel/common/config/jwt/AuthPrincipal.java`
- Create: `shared-common/src/main/java/github/lms/lemuel/common/config/jwt/TokenVersionVerifier.java`
- Create: `order-service/src/main/java/github/lms/lemuel/user/adapter/out/security/DbTokenVersionVerifier.java`
- Create: `order-service/src/main/java/github/lms/lemuel/user/adapter/in/web/internal/UserAuthorizationReconController.java`
- Create: `order-service/src/main/java/github/lms/lemuel/user/adapter/in/web/internal/UserAuthorizationSnapshotResponse.java`
- Create: `settlement-service/src/main/java/github/lms/lemuel/security/OrderUserTokenVersionVerifier.java`
- Modify: `../../../../settlement-service/src/main/resources/application.yml`
- Test: `../../../../shared-common/src/test/java/github/lms/lemuel/common/config/jwt/JwtUtilTest.java`
- Test: `../../../../shared-common/src/test/java/github/lms/lemuel/common/config/jwt/SecurityFiltersTest.java`
- Test: `order-service/src/test/java/github/lms/lemuel/user/adapter/out/security/DbTokenVersionVerifierTest.java`
- Test: `order-service/src/test/java/github/lms/lemuel/user/adapter/in/web/internal/UserAuthorizationReconControllerTest.java`
- Test: `settlement-service/src/test/java/github/lms/lemuel/security/OrderUserTokenVersionVerifierTest.java`
- Test: `../../../../order-service/src/test/java/github/lms/lemuel/user/application/service/PasswordResetServiceTest.java`
- Test: `../../../../order-service/src/test/java/github/lms/lemuel/user/application/service/MembershipApprovalServiceTest.java`
- Test: `../../../../order-service/src/test/java/github/lms/lemuel/user/application/service/DemoLoginServiceTest.java`
- Test: `../../../../order-service/src/test/java/github/lms/lemuel/user/adapter/in/web/UserControllerTest.java`
- Test: `../../../../order-service/src/test/java/github/lms/lemuel/user/adapter/out/persistence/UserPersistenceAdapterTest.java`

**Interfaces:**
- `String TokenProviderPort.generateToken(String email, String role, Long userId, long tokenVersion)`
- `boolean TokenVersionVerifier.verify(long uid, String role, long tokenVersion)`
- `AuthPrincipal(Long userId, String email, String role, long tokenVersion)` preserves the existing email/name behavior.
- Preserve source compatibility with `AuthPrincipal(Long userId, String email, String role) { this(userId, email, role, 0L); }`; the filter still rejects JWTs missing an explicit tokenVersion before constructing a principal.
- `void User.invalidateTokens()` increments the persisted version.
- Settlement verifies synchronously through `GET /internal/recon/users/{uid}/authorization` with `X-Internal-Api-Key`; the response contains current role, tokenVersion, active and canUseService.

- [ ] Add failing JWT tests for a valid current version, stale version, role mismatch, inactive user, and missing `uid`/`tokenVersion` claims.

```java
when(verifier.verify(42L, "SELLER", 3L)).thenReturn(false);
assertThat(filterAuthentication(tokenWith(42L, "SELLER", 3L))).isEmpty();
assertThat(filterAuthentication(legacyTokenWithoutVersion())).isEmpty();
```

- [ ] Run `./gradlew.bat :shared-common:test --tests "github.lms.lemuel.common.config.jwt.*"`; expect the stale/missing-token tests to fail.
- [ ] Add the migration and map `token_version BIGINT NOT NULL DEFAULT 0` through domain/JPA/mapper.

```sql
ALTER TABLE users
    ADD COLUMN token_version BIGINT NOT NULL DEFAULT 0;
```

- [ ] Add the shared interface without importing order persistence into `shared-common`. Add `app.security.db-token-verification-required=true` in order-service and settlement-service; when required, a missing verifier, timeout, 4xx/5xx, malformed response, role mismatch, or version mismatch must fail closed with 401.
- [ ] Implement `DbTokenVersionVerifier` using `LoadUserPort.findById(uid)` and require matching role/version plus `canUseService()`.
- [ ] Expose the DB-authoritative snapshot only at the allowed order internal recon path and protect it with constant-time `X-Internal-Api-Key` comparison. Production startup must fail when the key is blank/default; settlement sends the same secret through configuration, never logs it, and uses an uncached synchronous client so suspension/version changes invalidate the next request immediately.
- [ ] Include `uid`, `role`, and `tokenVersion` when issuing access tokens. Increment inside domain mutations `updatePassword`, `changeRole`, `deactivate`, and every membership transition so UserController password/withdrawal, PasswordResetService, MembershipApprovalService approve/reject/suspend/reinstate, and DemoLoginService role changes cannot omit revocation. Add a test for every call path.
- [ ] Run `./gradlew.bat :shared-common:test --tests "github.lms.lemuel.common.config.jwt.*"`, `./gradlew.bat :order-service:test --tests "github.lms.lemuel.user.*"`, and `./gradlew.bat :settlement-service:test --tests "github.lms.lemuel.security.*"`; expect PASS.
- [ ] Commit Task 2: `git commit -m "feat(auth): revoke tokens from persisted user version"`.

## Task 3: Product seller ownership dual-write in domain and repository

**Files:**
- Modify: `../../../../order-service/src/main/java/github/lms/lemuel/product/domain/Product.java`
- Modify: `../../../../order-service/src/main/java/github/lms/lemuel/product/adapter/out/persistence/ProductJpaEntity.java`
- Modify: `../../../../order-service/src/main/java/github/lms/lemuel/product/adapter/out/persistence/ProductPersistenceMapper.java`
- Modify: `../../../../order-service/src/main/java/github/lms/lemuel/product/application/port/out/LoadProductPort.java`
- Modify: `../../../../order-service/src/main/java/github/lms/lemuel/product/adapter/out/persistence/SpringDataProductJpaRepository.java`
- Modify: `../../../../order-service/src/main/java/github/lms/lemuel/product/adapter/out/persistence/ProductPersistenceAdapter.java`
- Modify: `../../../../order-service/src/main/java/github/lms/lemuel/product/adapter/in/web/response/ProductResponse.java`
- Test: `../../../../order-service/src/test/java/github/lms/lemuel/product/domain/ProductFullTest.java`
- Test: `../../../../order-service/src/test/java/github/lms/lemuel/product/adapter/out/persistence/ProductPersistenceAdapterTest.java`
- Test: `../../../../order-service/src/test/java/github/lms/lemuel/product/adapter/in/web/response/ProductResponseTest.java`
- Test: `../../../../order-service/src/test/java/github/lms/lemuel/schema/SchemaIntegrationTest.java`

**Interfaces:**
- `Product.create(long sellerId, String name, String description, BigDecimal price, Integer stockQuantity, String optionsJson)`
- `Optional<Product> LoadProductPort.findBySellerIdAndName(long sellerId, String name)`
- `List<Product> LoadProductPort.findAllBySellerId(long sellerId, Pageable pageable)`

- [ ] Add failing mapping/repository tests that preserve `sellerId` and permit the same product name for different sellers while rejecting duplicates for one seller.
- [ ] Run `./gradlew.bat :order-service:test --tests "github.lms.lemuel.product.*"`; expect seller ownership failures.
- [ ] Wire the existing nullable `products.seller_id` through domain/JPA/mapper/response and replace global duplicate-name queries with seller-scoped queries.

```java
public static Product create(long sellerId, String name, String description,
                             BigDecimal price, Integer stockQuantity, String optionsJson) {
    if (sellerId <= 0) throw new IllegalArgumentException("sellerId must be positive");
    Product product = create(name, description, price, stockQuantity, optionsJson);
    product.sellerId = sellerId;
    return product;
}
```

- [ ] Run product and schema tests; expect PASS.
- [ ] Deploy this dual-write version while `seller_id` remains nullable for old pods/legacy reads. Confirm all newly created products carry a SELLER owner.
- [ ] Commit Task 3: `git commit -m "feat(product): dual-write seller ownership"`.

## Task 4: Product, variant and image authorization plus dev test seller

**Files:**
- Modify: `../../../../order-service/src/main/java/github/lms/lemuel/product/adapter/in/web/ProductController.java`
- Modify: `../../../../order-service/src/main/java/github/lms/lemuel/product/adapter/in/web/ProductImageController.java`
- Modify: `../../../../order-service/src/main/java/github/lms/lemuel/product/adapter/in/web/ProductVariantController.java`
- Modify: `../../../../order-service/src/main/java/github/lms/lemuel/product/adapter/in/web/request/CreateProductRequest.java`
- Create: `order-service/src/main/java/github/lms/lemuel/product/adapter/in/web/request/ProductOwnerMode.java`
- Modify: `../../../../order-service/src/main/java/github/lms/lemuel/product/application/port/in/CreateProductUseCase.java`
- Modify: `../../../../order-service/src/main/java/github/lms/lemuel/product/application/service/CreateProductService.java`
- Modify: `../../../../order-service/src/main/java/github/lms/lemuel/product/application/service/UpdateProductService.java`
- Modify: `../../../../order-service/src/main/java/github/lms/lemuel/product/application/service/ManageProductStatusService.java`
- Modify: `../../../../order-service/src/main/java/github/lms/lemuel/product/application/service/ProductImageService.java`
- Modify: `../../../../order-service/src/main/java/github/lms/lemuel/product/application/service/ProductVariantService.java`
- Create: `order-service/src/main/java/github/lms/lemuel/product/application/service/ProductOwnershipPolicy.java`
- Create: `order-service/src/main/java/github/lms/lemuel/product/application/service/ProductAuditRecorder.java`
- Create: `order-service/src/main/java/github/lms/lemuel/product/config/DevelopmentPlatformSellerConfiguration.java`
- Modify: `../../../../order-service/src/main/resources/application.yml`
- Test: `../../../../order-service/src/test/java/github/lms/lemuel/product/application/service/CreateProductServiceTest.java`
- Test: `order-service/src/test/java/github/lms/lemuel/product/application/service/ProductOwnershipPolicyTest.java`
- Test: `order-service/src/test/java/github/lms/lemuel/product/application/service/ProductAuditRecorderTest.java`
- Test: `../../../../order-service/src/test/java/github/lms/lemuel/product/adapter/in/web/ProductControllerTest.java`
- Test: `../../../../order-service/src/test/java/github/lms/lemuel/product/adapter/in/web/ProductImageControllerTest.java`
- Test: `../../../../order-service/src/test/java/github/lms/lemuel/product/adapter/in/web/ProductVariantControllerTest.java`

**Interfaces:**
- `CreateProductCommand(long actorId, UserRole actorRole, ProductOwnerMode ownerMode, Long requestedSellerId, String name, String description, BigDecimal price, Integer stockQuantity, String optionsJson)`
- `long ProductOwnershipPolicy.resolveOwner(AuthPrincipal actor, ProductOwnerMode ownerMode, Long requestedSellerId)`
- `void ProductOwnershipPolicy.requireCanManage(AuthPrincipal actor, Product product)`

- [ ] Add failing policy tests: SELLER owns only self, USER denied, MANAGER/ADMIN with `ownerMode=SELLER` must select an approved SELLER, and `ownerMode=PLATFORM_TEST` accepts no sellerId and only resolves the dedicated dev SELLER under the `dev` profile.

```java
assertThat(policy.resolveOwner(sellerPrincipal(7L), null, null)).isEqualTo(7L);
assertThatThrownBy(() -> policy.resolveOwner(sellerPrincipal(7L), ProductOwnerMode.SELLER, 8L))
    .isInstanceOf(AccessDeniedException.class);
assertThat(policy.resolveOwner(managerPrincipal(), ProductOwnerMode.SELLER, 8L)).isEqualTo(8L);
```

- [ ] Run the product service/controller tests; expect authorization failures.
- [ ] Derive actor identity from `AuthPrincipal`; never trust client-supplied actor/user IDs. SELLER always owns itself. MANAGER/ADMIN must use `ownerMode=SELLER` with an approved SELLER or `ownerMode=PLATFORM_TEST` with the dev-only dedicated SELLER; MANAGER/ADMIN ids are not product owner ids.
- [ ] Apply `requireCanManage` before product update/status/image/variant mutations.
- [ ] Record delegated creation and every ownership-sensitive change through `ProductAuditRecorder` using `SaveAuditLogPort` in the same business transaction. Persist `resourceType=Product`, resourceId, actorUserId, ownerSellerId, action, before/after summary, and traceId. Do not use the existing failure-swallowing `AuditLogger.record(REQUIRES_NEW)` path for these records.
- [ ] Add `app.platform-test-seller.enabled=false` and `app.multi-seller-checkout.enabled=false` by default. Instantiate/bootstrap the dedicated test SELLER only under `@Profile("dev")` plus the first flag. Add a startup failure if the test-seller flag is true outside `dev`; keep multi-seller checkout traffic disabled until Task 12 contract cutover.
- [ ] Run `./gradlew.bat :order-service:test --tests "github.lms.lemuel.product.*"`; expect PASS.
- [ ] Commit Task 4: `git commit -m "feat(product): enforce seller ownership policies"`.

## Task 4B: Legacy product owner backfill and seller-only schema contract

**Files:**
- Create: `order-service/src/main/java/github/lms/lemuel/product/application/service/LegacyProductOwnershipBackfillService.java`
- Create: `order-service/src/main/java/github/lms/lemuel/product/application/port/in/BackfillLegacyProductOwnershipUseCase.java`
- Create: `order-service/src/main/java/github/lms/lemuel/product/adapter/in/web/ProductOwnershipAdminController.java`
- Create: `order-service/src/main/resources/db/migration/V20260727093000__products_seller_ownership_hardening.sql`
- Test: `order-service/src/test/java/github/lms/lemuel/product/application/service/LegacyProductOwnershipBackfillServiceTest.java`
- Test: `order-service/src/test/java/github/lms/lemuel/product/adapter/in/web/ProductOwnershipAdminControllerTest.java`
- Modify: `../../../../order-service/src/test/java/github/lms/lemuel/schema/SchemaIntegrationTest.java`
- Create: `order-service/src/test/java/github/lms/lemuel/checkout/integration/MultiSellerCheckoutPaymentIT.java`

**Interfaces:**
- `BackfillReport assign(Map<Long, Long> productToApprovedSeller)` is ADMIN-only and maps productId to an active, approved SELLER id.
- `BackfillReport verify()` returns nullOwnerCount, nonSellerOwnerCount, inactiveOwnerCount, and managerSeedOwnerCount.

- [ ] Add failing service tests rejecting USER/SELLER/MANAGER actors, unknown products, non-SELLER owners, PENDING/inactive SELLERs, and partial mappings that leave invalid owners.
- [ ] Add a failing schema test proving NULL, MANAGER, ADMIN, and deleted owner references are rejected after contract while approved SELLER ownership succeeds.
- [ ] Run `./gradlew.bat :order-service:test --tests "*LegacyProductOwnership*" --tests "*ProductOwnershipAdmin*"`; expect failures.
- [ ] Implement the audited application backfill. Move every V31 `seed_manager` product and every NULL/non-SELLER owner to an explicit approved SELLER; in dev only, the dedicated platform-test SELLER is allowed. Do not create a production platform test seller.
- [ ] Deploy and run the application backfill before shipping the Flyway contract. Require `verify()` to report all four counts as zero for one full observation window.
- [ ] In a later deployment, add a PostgreSQL trigger/check function requiring the referenced user role SELLER and approved/active status for inserts/owner changes; replace `ON DELETE SET NULL` with `ON DELETE RESTRICT` and set `seller_id NOT NULL`.

```sql
DO $$
BEGIN
  IF EXISTS (
    SELECT 1 FROM products p
    LEFT JOIN users u ON u.id = p.seller_id
    WHERE p.seller_id IS NULL OR u.role <> 'SELLER'
       OR u.membership_status <> 'APPROVED' OR u.is_active = FALSE
  ) THEN
    RAISE EXCEPTION 'product seller ownership contract is not satisfied';
  END IF;
END $$;
```

- [ ] Run product/schema suites; expect PASS. Verify old application pods have drained before applying NOT NULL/RESTRICT.
- [ ] Commit Task 4B: `git commit -m "feat(product): harden approved seller ownership"`.

## Task 5: Buyer-scoped IDOR protection

**Files:**
- Modify: `../../../../order-service/src/main/java/github/lms/lemuel/cart/adapter/in/web/CartController.java`
- Modify: `../../../../order-service/src/main/java/github/lms/lemuel/order/adapter/in/web/OrderController.java`
- Modify: `../../../../order-service/src/main/java/github/lms/lemuel/payment/adapter/in/api/PaymentController.java`
- Modify: `../../../../order-service/src/main/java/github/lms/lemuel/review/adapter/in/web/ReviewController.java`
- Modify: `../../../../order-service/src/main/java/github/lms/lemuel/shipping/adapter/in/web/ShippingController.java`
- Modify: `../../../../order-service/src/main/java/github/lms/lemuel/user/adapter/in/web/UserController.java`
- Modify: `../../../../order-service/src/main/java/github/lms/lemuel/order/application/service/GetOrderService.java`
- Modify: `../../../../order-service/src/main/java/github/lms/lemuel/order/application/service/ChangeOrderStatusService.java`
- Modify: `../../../../order-service/src/main/java/github/lms/lemuel/payment/adapter/out/persistence/OrderAdapter.java`
- Modify: `../../../../order-service/src/main/java/github/lms/lemuel/review/application/ReviewService.java`
- Modify: `../../../../order-service/src/main/java/github/lms/lemuel/shipping/application/service/ShippingService.java`
- Test: `../../../../order-service/src/test/java/github/lms/lemuel/cart/adapter/in/web/CartControllerTest.java`
- Test: `../../../../order-service/src/test/java/github/lms/lemuel/order/adapter/in/web/OrderControllerTest.java`
- Test: `../../../../order-service/src/test/java/github/lms/lemuel/order/application/service/GetOrderServiceTest.java`
- Test: `../../../../order-service/src/test/java/github/lms/lemuel/order/application/service/ChangeOrderStatusServiceTest.java`
- Test: `../../../../order-service/src/test/java/github/lms/lemuel/payment/adapter/in/api/PaymentControllerSecurityTest.java`
- Test: `../../../../order-service/src/test/java/github/lms/lemuel/review/adapter/in/web/ReviewControllerTest.java`
- Test: `../../../../order-service/src/test/java/github/lms/lemuel/shipping/adapter/in/web/ShippingControllerTest.java`
- Test: `../../../../order-service/src/test/java/github/lms/lemuel/user/adapter/in/web/UserControllerTest.java`

**Interfaces:**
- `ActorContext(long uid, UserRole role)` derived from `AuthPrincipal`.
- `OrderInfo` returned to payment must include `buyerId` and `sellerId`.
- Buyer commands no longer accept an authoritative client-supplied userId.

- [ ] Add parameterized failing tests for USER, MANAGER, and ADMIN acting as buyers: each may access only its own cart/order/payment/review/shipping/user data on general purchase APIs. Put cross-user operations behind separate admin endpoints. SELLER is denied all purchase APIs.

```java
assertThatThrownBy(() -> service.getOrder(actorUser(10L), orderOwnedBy(11L)))
    .isInstanceOf(AccessDeniedException.class);
assertThatThrownBy(() -> service.getOrder(actorAdminBuyer(10L), orderOwnedBy(11L)))
    .isInstanceOf(AccessDeniedException.class);
```

- [ ] Run `./gradlew.bat :order-service:test --tests "github.lms.lemuel.cart.*" --tests "github.lms.lemuel.order.*" --tests "github.lms.lemuel.payment.*" --tests "github.lms.lemuel.review.*" --tests "github.lms.lemuel.shipping.*"`; expect cross-user cases to fail.
- [ ] Keep legacy `{userId}` routes temporarily if clients require them, but compare them to principal uid before loading data. Add `/me` aliases where practical.
- [ ] Load payment ownership through payment → order → buyer and check it before confirm/cancel/refund/Toss operations.
- [ ] Run the targeted suites; expect PASS.
- [ ] Commit Task 5: `git commit -m "fix(order): bind buyer resources to authenticated principal"`.

## Task 6: CheckoutGroup, buyer idempotency and deterministic coupon allocation

**Files:**
- Create: `order-service/src/main/resources/db/migration/V20260727100000__checkout_groups_and_seller_orders.sql`
- Create: `order-service/src/main/java/github/lms/lemuel/checkout/domain/CheckoutGroup.java`
- Create: `order-service/src/main/java/github/lms/lemuel/checkout/domain/CheckoutStatus.java`
- Create: `order-service/src/main/java/github/lms/lemuel/checkout/domain/CouponDiscountAllocator.java`
- Create: `order-service/src/main/java/github/lms/lemuel/checkout/adapter/out/persistence/CheckoutGroupJpaEntity.java`
- Create: `order-service/src/main/java/github/lms/lemuel/checkout/adapter/out/persistence/SpringDataCheckoutGroupRepository.java`
- Create: `order-service/src/main/java/github/lms/lemuel/checkout/adapter/out/persistence/CheckoutPersistenceMapper.java`
- Create: `order-service/src/main/java/github/lms/lemuel/checkout/adapter/out/persistence/CheckoutPersistenceAdapter.java`
- Create: `order-service/src/main/java/github/lms/lemuel/checkout/application/port/in/CreateCheckoutUseCase.java`
- Create: `order-service/src/main/java/github/lms/lemuel/checkout/application/port/out/LoadCheckoutGroupPort.java`
- Create: `order-service/src/main/java/github/lms/lemuel/checkout/application/port/out/SaveCheckoutGroupPort.java`
- Modify: `../../../../order-service/src/main/java/github/lms/lemuel/order/domain/Order.java`
- Modify: `../../../../order-service/src/main/java/github/lms/lemuel/order/domain/OrderItem.java`
- Modify: `../../../../order-service/src/main/java/github/lms/lemuel/order/adapter/out/persistence/OrderJpaEntity.java`
- Modify: `../../../../order-service/src/main/java/github/lms/lemuel/order/adapter/out/persistence/OrderItemJpaEntity.java`
- Modify: `../../../../order-service/src/main/java/github/lms/lemuel/order/adapter/out/persistence/OrderPersistenceMapper.java`
- Modify: `../../../../order-service/src/main/java/github/lms/lemuel/order/adapter/out/persistence/OrderPersistenceAdapter.java`
- Test: `order-service/src/test/java/github/lms/lemuel/checkout/domain/CouponDiscountAllocatorTest.java`
- Test: `order-service/src/test/java/github/lms/lemuel/checkout/application/service/CheckoutIdempotencyTest.java`
- Test: `../../../../order-service/src/test/java/github/lms/lemuel/schema/SchemaIntegrationTest.java`

**Interfaces:**
- `CheckoutResult checkout(long buyerId, String idempotencyKey, CheckoutCommand command)`
- `CheckoutCommand(long cartId, long cartVersion, String couponCode, List<ShippingSelection> shippingSelections)`
- `AllocationResult CouponDiscountAllocator.allocate(List<DiscountLine> lines, BigDecimal couponDiscount)`
- Fingerprint is SHA-256 over the immutable request payload `(schemaVersion, cartId, cartVersion, couponCode, shippingSelections sorted by sellerId)`. Product lines are loaded only on first execution after verifying cartVersion; replay compares the stored request fingerprint without rereading a cleared cart.

- [ ] Add failing allocator tests for exact total conservation, deterministic order, rounding remainder, zero discount, and discount not exceeding gross.
- [ ] Add failing validation tests for negative discount, discount above gross, unsupported currency scale, and values above the DB precision limit.

```java
AllocationResult result = allocator.allocate(lines("6000", "4000"), new BigDecimal("1001"));
assertThat(result.totalDiscount()).isEqualByComparingTo("1001");
assertThat(result.lines()).extracting(DiscountAllocation::amount)
    .containsExactly(new BigDecimal("601"), new BigDecimal("400"));
```

- [ ] Add failing idempotency tests: same `(buyer,key,fingerprint)` replays stored result after cart clear; same key/different fingerprint returns conflict; another buyer may reuse the key.
- [ ] Run `./gradlew.bat :order-service:test --tests "github.lms.lemuel.checkout.*"`; expect missing checkout domain failures.
- [ ] Add schema: `checkout_groups`, unique `(buyer_id,idempotency_key)`, `orders.checkout_group_id`, `orders.seller_id`, composite unique `(id,seller_id)`, `order_items.line_discount`, and coupon usage checkout-group source with legacy-order/group XOR.
- [ ] Implement sequential remaining-discount allocation using `setScale(0, HALF_UP)`, clamp each step, and assign the last-line remainder. Persist line discounts.
- [ ] Compute the request-only fingerprint and look up `(buyer,key)` before reading mutable cart state. On first execution, require the current cart version to equal `command.cartVersion`; on replay, compare fingerprints and return the stored result without rereading the cart.
- [ ] Run checkout/schema tests; expect PASS.
- [ ] Commit Task 6: `git commit -m "feat(checkout): add idempotent multi-seller checkout group"`.

## Task 7: Create seller orders atomically from one checkout

**Files:**
- Create: `order-service/src/main/java/github/lms/lemuel/checkout/application/service/CreateCheckoutService.java`
- Modify: `../../../../order-service/src/main/java/github/lms/lemuel/cart/application/port/in/CheckoutCartUseCase.java`
- Modify: `../../../../order-service/src/main/java/github/lms/lemuel/cart/application/service/CheckoutCartService.java`
- Modify: `../../../../order-service/src/main/java/github/lms/lemuel/cart/adapter/in/web/CartController.java`
- Modify: `../../../../order-service/src/main/java/github/lms/lemuel/order/application/service/CreateMultiItemOrderService.java`
- Test: `order-service/src/test/java/github/lms/lemuel/checkout/application/service/CreateCheckoutServiceTest.java`
- Test: `order-service/src/test/java/github/lms/lemuel/checkout/application/service/CreateCheckoutServiceIT.java`
- Modify: `../../../../order-service/src/test/java/github/lms/lemuel/cart/application/service/CheckoutCartServiceTest.java`
- Modify: `../../../../order-service/src/test/java/github/lms/lemuel/order/application/service/CreateMultiItemOrderServiceTest.java`
- Create: `order-service/src/test/java/github/lms/lemuel/order/application/service/CreateMultiItemOrderServiceIT.java`

**Interfaces:**
- `CheckoutResult` contains checkoutGroupId, total amount, and seller order summaries.
- One transaction locks cart/stock/coupon, creates group, creates one order per seller, persists discounts, consumes coupon, and clears cart.

- [ ] Add failing test for a cart containing two sellers: one checkout group, two orders, correct seller ownership, conserved gross/discount/shipping/total, and one coupon usage.
- [ ] Add failing rollback test: insufficient stock for either seller leaves no group/order/coupon usage and does not clear the cart.
- [ ] Run checkout/order tests; expect failures while checkout returns a single `Order`.
- [ ] Change `CheckoutCartUseCase` to return `CheckoutResult`, group lines by persisted product sellerId, and invoke the orchestration in a single `@Transactional` boundary.

```java
Map<Long, List<CartLine>> bySeller = lines.stream()
    .collect(Collectors.groupingBy(CartLine::sellerId, LinkedHashMap::new, Collectors.toList()));
```

- [ ] Ensure the buyer is USER/MANAGER/ADMIN; reject SELLER before inventory mutation.
- [ ] Run checkout/order tests; expect PASS.
- [ ] Commit Task 7: `git commit -m "feat(order): split checkout into seller orders"`.

## Task 8: One Payment with seller PaymentAllocations and capture Outbox

**Files:**
- Create: `order-service/src/main/resources/db/migration/V20260727101000__payment_allocations.sql`
- Create: `order-service/src/main/java/github/lms/lemuel/payment/domain/PaymentAllocation.java`
- Create: `order-service/src/main/java/github/lms/lemuel/payment/domain/PaymentAllocationStatus.java`
- Create: `order-service/src/main/java/github/lms/lemuel/payment/adapter/out/persistence/PaymentAllocationJpaEntity.java`
- Create: `order-service/src/main/java/github/lms/lemuel/payment/adapter/out/persistence/SpringDataPaymentAllocationRepository.java`
- Create: `order-service/src/main/java/github/lms/lemuel/payment/adapter/out/persistence/PaymentAllocationPersistenceAdapter.java`
- Create: `order-service/src/main/java/github/lms/lemuel/payment/application/port/out/LoadPaymentAllocationPort.java`
- Create: `order-service/src/main/java/github/lms/lemuel/payment/application/port/out/SavePaymentAllocationPort.java`
- Modify: `../../../../order-service/src/main/java/github/lms/lemuel/payment/application/port/out/LoadSellerSettlementMetaPort.java`
- Modify: `../../../../order-service/src/main/java/github/lms/lemuel/payment/adapter/out/persistence/SellerSettlementMetaJdbcAdapter.java`
- Create: `order-service/src/main/java/github/lms/lemuel/payment/domain/LegacyAllocationSourceKind.java`
- Create: `order-service/src/main/java/github/lms/lemuel/payment/adapter/out/persistence/LegacyPaymentAllocationQuarantineJpaEntity.java`
- Create: `order-service/src/main/java/github/lms/lemuel/payment/domain/PaymentCaptureRecoveryTask.java`
- Create: `order-service/src/main/java/github/lms/lemuel/payment/adapter/out/persistence/PaymentCaptureRecoveryTaskJpaEntity.java`
- Create: `order-service/src/main/java/github/lms/lemuel/payment/application/service/PaymentCaptureRecoveryService.java`
- Modify: `../../../../order-service/src/main/java/github/lms/lemuel/payment/domain/PaymentDomain.java`
- Modify: `../../../../order-service/src/main/java/github/lms/lemuel/payment/adapter/out/persistence/PaymentJpaEntity.java`
- Modify: `../../../../order-service/src/main/java/github/lms/lemuel/payment/adapter/out/persistence/PaymentMapper.java`
- Modify: `../../../../order-service/src/main/java/github/lms/lemuel/payment/adapter/out/persistence/PaymentPersistenceAdapter.java`
- Modify: `../../../../order-service/src/main/java/github/lms/lemuel/payment/adapter/out/persistence/PaymentJpaRepository.java`
- Modify: `../../../../order-service/src/main/java/github/lms/lemuel/payment/application/CreatePaymentUseCase.java`
- Create: `order-service/src/main/java/github/lms/lemuel/payment/application/port/in/CreateCheckoutPaymentPort.java`
- Create: `order-service/src/main/java/github/lms/lemuel/payment/application/service/CreateCheckoutPaymentService.java`
- Modify: `../../../../order-service/src/main/java/github/lms/lemuel/payment/application/CapturePaymentUseCase.java`
- Modify: `../../../../order-service/src/main/java/github/lms/lemuel/payment/application/port/out/PublishEventPort.java`
- Modify: `../../../../order-service/src/main/java/github/lms/lemuel/payment/adapter/out/event/OutboxBackedEventPublisher.java`
- Test: `order-service/src/test/java/github/lms/lemuel/payment/domain/PaymentAllocationDomainTest.java`
- Test: `order-service/src/test/java/github/lms/lemuel/payment/adapter/out/persistence/PaymentAllocationPersistenceIT.java`
- Test: `order-service/src/test/java/github/lms/lemuel/payment/application/service/PaymentAllocationOutboxContractTest.java`
- Test: `../../../../order-service/src/test/java/github/lms/lemuel/payment/adapter/out/persistence/SellerSettlementMetaJdbcAdapterTest.java`
- Create: `shared-common/src/testFixtures/resources/contracts/events/lemuel.payment.allocation_captured.schema.json`
- Create: `shared-common/src/testFixtures/resources/contracts/events/samples/lemuel.payment.allocation_captured.sample.json`
- Create: `shared-common/src/testFixtures/resources/contracts/events/lemuel.payment.allocation_backfilled.schema.json`
- Create: `shared-common/src/testFixtures/resources/contracts/events/samples/lemuel.payment.allocation_backfilled.sample.json`
- Modify: `../../../../order-service/src/test/java/github/lms/lemuel/payment/adapter/out/event/PaymentEventContractTest.java`
- Modify: `../../../../shared-common/src/test/java/github/lms/lemuel/common/events/contract/EventContractFixtureTest.java`
- Modify: `../../../../order-service/src/main/resources/application.yml`
- Modify: `../../../../order-service/src/test/java/github/lms/lemuel/payment/application/CapturePaymentUseCaseTest.java`
- Modify: `../../../../order-service/src/test/java/github/lms/lemuel/kafka/KafkaOutboxIntegrationTest.java`
- Modify: `../../../../order-service/src/test/java/github/lms/lemuel/schema/SchemaIntegrationTest.java`

**Interfaces:**
- `PaymentDomain CreateCheckoutPaymentPort.createCheckoutPayment(long checkoutGroupId, List<AllocationCommand> allocations, String method)`
- `void PaymentDomain.finalizeAllocations(List<PaymentAllocation> allocations)`
- `publishPaymentAllocationCaptured(long allocationId, long paymentId, long checkoutGroupId, long orderId, long sellerId, BigDecimal amount, LocalDateTime capturedAt, String sellerTier, String settlementCycle)`
- `SellerSettlementMeta LoadSellerSettlementMetaPort.findRequiredBySellerId(long sellerId)` snapshots tier/cycle per allocation and fails when either value is absent.
- Legacy source kinds are `LEGACY_SINGLE_SELLER` and `LEGACY_MIXED_SELLER`; only new `SELLER_ORDER` allocations require non-null sellerId and composite order/seller ownership.

- [ ] Add failing domain/schema tests: positive bounded scale, one allocation per payment/order, seller matches order, finalized sum equals payment amount, legacy single-seller payment remains readable, and mixed-seller legacy orders are represented without rewriting historical order/items.
- [ ] Add failing capture test requiring one PG capture, all seller orders PAID, all allocations CAPTURED, and one `PaymentAllocationCaptured` outbox row per allocation in the same transaction.
- [ ] Create the checkout→single-payment→two-allocation acceptance test now and verify RED before implementation.
- [ ] Add failing snapshot tests for two sellers with different tier/cycle and fail-fast behavior when seller metadata is missing; never infer allocation seller metadata from paymentId.
- [ ] Add failing producer contract tests against the canonical allocation-captured/backfilled schemas and samples, including decimal-string amount, eventId, occurredAt, allocationId, paymentId, orderId, sellerId/sourceKind, tier, and cycle.
- [ ] Run `./gradlew.bat :order-service:test --tests "github.lms.lemuel.payment.*Allocation*"`; expect failures.
- [ ] Add nullable `payments.checkout_group_id`, make legacy `order_id` nullable, require exactly one legacy-order/group source, create `payment_allocations`, and create `legacy_payment_allocation_quarantine`. Backfill a single legacy allocation without changing order history: populate sellerId only when all order items resolve to one seller; otherwise mark `LEGACY_MIXED_SELLER`, keep sellerId null, and quarantine it from seller settlement replay.
- [ ] Add composite FK `(order_id,seller_id) → orders(id,seller_id)`; PostgreSQL permits the nullable mixed-legacy seller to bypass this FK, while a CHECK requires both values for `SELLER_ORDER`. Add amount/scale checks and a deferred finalized-total constraint trigger.
- [ ] Implement allocation finalization and Outbox event type `PaymentAllocationCaptured` mapped to `lemuel.payment.allocation_captured`. Retain legacy `PaymentCaptured` only for projection during cutover.
- [ ] Use one stable PG idempotency key per checkout group. If PG capture succeeds but the internal transaction fails, `PaymentCaptureRecoveryService` records an idempotent `PaymentCaptureRecoveryTask` and Outbox compensation request; retries resolve the already approved PG payment rather than capture again.
- [ ] Run payment, outbox, and schema tests; expect PASS.
- [ ] Commit Task 8: `git commit -m "feat(payment): allocate one checkout payment by seller"`.

## Task 9: Settlement expand phase and allocation event consumer

**Files:**
- Create: `settlement-service/src/main/resources/db/migration/V20260727100000__settlement_payment_allocation_source.sql`
- Modify: `../../../../settlement-service/src/main/java/github/lms/lemuel/settlement/domain/Settlement.java`
- Modify: `../../../../settlement-service/src/main/java/github/lms/lemuel/settlement/adapter/out/persistence/SettlementJpaEntity.java`
- Modify: `../../../../settlement-service/src/main/java/github/lms/lemuel/settlement/adapter/out/persistence/SettlementPersistenceMapper.java`
- Modify: `../../../../settlement-service/src/main/java/github/lms/lemuel/settlement/adapter/out/persistence/SettlementPersistenceAdapter.java`
- Modify: `../../../../settlement-service/src/main/java/github/lms/lemuel/settlement/adapter/out/persistence/SpringDataSettlementJpaRepository.java`
- Modify: `../../../../settlement-service/src/main/java/github/lms/lemuel/settlement/application/port/out/LoadSettlementPort.java`
- Modify: `../../../../settlement-service/src/main/java/github/lms/lemuel/settlement/application/port/in/CreateSettlementFromPaymentUseCase.java`
- Create: `settlement-service/src/main/java/github/lms/lemuel/settlement/adapter/in/kafka/PaymentAllocationCapturedKafkaConsumer.java`
- Create: `settlement-service/src/main/java/github/lms/lemuel/settlement/adapter/in/kafka/PaymentAllocationBackfilledKafkaConsumer.java`
- Create: `settlement-service/src/main/java/github/lms/lemuel/settlement/application/service/AttachLegacySettlementAllocationService.java`
- Create: `settlement-service/src/main/java/github/lms/lemuel/settlement/adapter/out/persistence/LegacySettlementAllocationQuarantineJpaEntity.java`
- Create: `settlement-service/src/main/java/github/lms/lemuel/settlement/adapter/out/persistence/SettlementAllocationSourceJpaEntity.java`
- Create: `settlement-service/src/main/java/github/lms/lemuel/settlement/adapter/out/persistence/SpringDataSettlementAllocationSourceRepository.java`
- Modify: `../../../../settlement-service/src/main/java/github/lms/lemuel/settlement/adapter/in/kafka/PaymentEventKafkaConsumer.java`
- Modify: `../../../../settlement-service/src/main/resources/application.yml`
- Create: `order-service/src/main/java/github/lms/lemuel/payment/application/service/ReplayLegacyPaymentAllocationService.java`
- Create: `order-service/src/main/java/github/lms/lemuel/payment/application/port/in/ReplayLegacyPaymentAllocationUseCase.java`
- Create: `order-service/src/test/java/github/lms/lemuel/payment/application/service/ReplayLegacyPaymentAllocationServiceTest.java`
- Test: `settlement-service/src/test/java/github/lms/lemuel/settlement/adapter/in/kafka/PaymentAllocationCapturedKafkaConsumerTest.java`
- Test: `settlement-service/src/test/java/github/lms/lemuel/settlement/application/service/CreateSettlementFromAllocationServiceTest.java`
- Test: `settlement-service/src/test/java/github/lms/lemuel/settlement/application/service/AttachLegacySettlementAllocationServiceTest.java`
- Test: `settlement-service/src/test/java/github/lms/lemuel/settlement/adapter/in/kafka/PaymentAllocationBackfilledKafkaConsumerTest.java`
- Test: `settlement-service/src/test/java/github/lms/lemuel/settlement/integration/SettlementAllocationIdempotencyIT.java`
- Test: `settlement-service/src/test/java/github/lms/lemuel/settlement/integration/SettlementAllocationSourceConcurrencyIT.java`
- Create: `settlement-service/src/test/java/github/lms/lemuel/settlement/integration/PaymentAllocationSettlementRefundIT.java`
- Create: `settlement-service/src/test/java/github/lms/lemuel/settlement/adapter/in/kafka/PaymentEventKafkaConsumerTest.java`
- Modify: `../../../../settlement-service/src/test/java/github/lms/lemuel/settlement/integration/SettlementDbBootIT.java`
- Modify: `../../../../settlement-service/src/test/java/github/lms/lemuel/settlement/integration/SettlementIdempotencyIntegrationTest.java`

**Interfaces:**
- `Settlement createSettlementFromAllocation(long allocationId, long paymentId, long orderId, long sellerId, BigDecimal amount, String tier, String cycle, LocalDateTime capturedAt)`
- `Optional<Settlement> findByPaymentAllocationIdForUpdate(long allocationId)`
- `BackfillDisposition attachOrCreateLegacyAllocation(long allocationId, long paymentId, Long sellerId, LegacyAllocationSourceKind sourceKind, BigDecimal amount)` returns ATTACHED, CREATED, ALREADY_ATTACHED, or QUARANTINED.

- [ ] Add failing consumer tests for one allocation creating one allocation-keyed settlement, duplicate event replay producing none, and marker rollback on creation failure. Add the two-allocations/one-payment proof only after Task 12 removes the payment-level uniqueness constraint.
- [ ] Create the allocation event→settlement portion of `PaymentAllocationSettlementRefundIT` now and verify RED; extend the same test with refund behavior in Task 11.
- [ ] Add failing backfill tests: an existing payment settlement receives an append-only `settlement_allocation_sources` mapping after exact amount validation; the settlement row is not updated and no second settlement is inserted. A missing single-seller settlement may be created. A missing or ambiguous mixed-seller settlement is quarantined, and no historical order/settlement amount, rate, status, or ledger entry is rewritten.
- [ ] Run `./gradlew.bat :settlement-service:test --tests "*PaymentAllocation*"`; expect failures.
- [ ] Add nullable `payment_allocation_id` and `seller_id` for newly inserted settlements, a partial unique allocation key, append-only `settlement_allocation_sources(settlement_id,payment_allocation_id,seller_id,created_at)` with `UNIQUE(payment_allocation_id)` and `UNIQUE(settlement_id)` for legacy 1:1 mappings, and `legacy_settlement_allocation_quarantine`. Keep existing `payment_id` uniqueness in this expand deployment and keep `app.multi-seller-checkout.enabled=false` so seller #2 cannot reach settlement prematurely.
- [ ] Add a concurrent backfill integration test where distinct eventIds target the same allocation/settlement; one source row succeeds and the other resolves as ALREADY_ATTACHED without leaking a transaction error.
- [ ] Implement the allocation consumer as `IdempotentEventConsumer`; insert processed marker and create settlement in the same transaction.
- [ ] Split `PaymentEventKafkaConsumer`: projection continues, legacy settlement creation is controlled by `app.kafka.consumer.legacy-payment-captured-settlement-enabled`.
- [ ] `ReplayLegacyPaymentAllocationService` emits `PaymentAllocationBackfilled`, not `PaymentAllocationCaptured`. The backfill consumer first locks/finds the settlement by paymentId: insert an immutable source mapping for an existing exact-amount row; never UPDATE the settlement and never INSERT a duplicate settlement. Mixed-seller rows remain legacy/quarantined and are excluded from seller automation.
- [ ] Do not perform cross-database SQL or alter historical amount/rate snapshots. Add consumer contract coverage in `../../../../settlement-service/src/test/java/github/lms/lemuel/settlement/adapter/in/kafka/EventContractConsumerTest.java` and topic bindings in settlement `application.yml`.
- [ ] Run allocation, DB boot, and idempotency tests; expect PASS.
- [ ] Commit Task 9: `git commit -m "feat(settlement): create settlements from payment allocations"`.

## Task 9B: Seller-scoped sales, allocation, settlement, tax and payout queries

**Files:**
- Create: `order-service/src/main/java/github/lms/lemuel/order/adapter/in/web/SellerOrderController.java`
- Create: `order-service/src/main/java/github/lms/lemuel/order/application/port/in/GetSellerOrdersUseCase.java`
- Create: `order-service/src/main/java/github/lms/lemuel/order/application/service/GetSellerOrdersService.java`
- Modify: `../../../../order-service/src/main/java/github/lms/lemuel/order/application/port/out/LoadOrderPort.java`
- Modify: `../../../../order-service/src/main/java/github/lms/lemuel/order/adapter/out/persistence/SpringDataOrderJpaRepository.java`
- Modify: `../../../../order-service/src/main/java/github/lms/lemuel/order/adapter/out/persistence/OrderPersistenceAdapter.java`
- Create: `order-service/src/main/java/github/lms/lemuel/payment/adapter/in/api/SellerPaymentAllocationController.java`
- Create: `order-service/src/main/java/github/lms/lemuel/payment/application/port/in/GetSellerPaymentAllocationsUseCase.java`
- Create: `order-service/src/main/java/github/lms/lemuel/payment/application/service/GetSellerPaymentAllocationsService.java`
- Modify: `order-service/src/main/java/github/lms/lemuel/payment/application/port/out/LoadPaymentAllocationPort.java`
- Modify: `order-service/src/main/java/github/lms/lemuel/payment/adapter/out/persistence/SpringDataPaymentAllocationRepository.java`
- Modify: `order-service/src/main/java/github/lms/lemuel/payment/adapter/out/persistence/PaymentAllocationPersistenceAdapter.java`
- Create: `settlement-service/src/main/java/github/lms/lemuel/settlement/adapter/in/web/SellerSettlementQueryController.java`
- Modify: `../../../../settlement-service/src/main/java/github/lms/lemuel/settlement/application/service/SettlementQueryService.java`
- Modify: `../../../../settlement-service/src/main/java/github/lms/lemuel/settlement/application/port/out/QuerySettlementPort.java`
- Modify: `../../../../settlement-service/src/main/java/github/lms/lemuel/settlement/application/port/out/dto/SettlementSearchCondition.java`
- Modify: `../../../../settlement-service/src/main/java/github/lms/lemuel/settlement/adapter/out/persistence/querydsl/SettlementQueryAdapter.java`
- Modify: `../../../../settlement-service/src/main/java/github/lms/lemuel/settlement/adapter/out/persistence/querydsl/SettlementQueryRepository.java`
- Modify: `../../../../settlement-service/src/main/java/github/lms/lemuel/settlement/adapter/out/persistence/querydsl/SettlementQueryRepositoryImpl.java`
- Create: `settlement-service/src/main/java/github/lms/lemuel/payout/adapter/in/web/PayoutSellerController.java`
- Create: `settlement-service/src/main/java/github/lms/lemuel/payout/application/port/in/GetSellerPayoutsUseCase.java`
- Create: `settlement-service/src/main/java/github/lms/lemuel/payout/application/service/GetSellerPayoutsService.java`
- Modify: `../../../../settlement-service/src/main/java/github/lms/lemuel/payout/application/port/out/LoadPayoutPort.java`
- Modify: `../../../../settlement-service/src/main/java/github/lms/lemuel/payout/adapter/out/persistence/SpringDataPayoutRepository.java`
- Modify: `../../../../settlement-service/src/main/java/github/lms/lemuel/payout/adapter/out/persistence/PayoutPersistenceAdapter.java`
- Modify: `../../../../settlement-service/src/main/java/github/lms/lemuel/tax/adapter/in/web/TaxInvoiceSellerController.java`
- Modify: `../../../../shared-common/src/main/java/github/lms/lemuel/common/config/jwt/SecurityConfig.java`
- Test: `order-service/src/test/java/github/lms/lemuel/order/adapter/in/web/SellerOrderControllerTest.java`
- Test: `order-service/src/test/java/github/lms/lemuel/payment/adapter/in/api/SellerPaymentAllocationControllerTest.java`
- Test: `settlement-service/src/test/java/github/lms/lemuel/settlement/adapter/in/web/SettlementQueryControllerSellerScopeTest.java`
- Test: `settlement-service/src/test/java/github/lms/lemuel/payout/adapter/in/web/PayoutSellerControllerTest.java`
- Test: `../../../../settlement-service/src/test/java/github/lms/lemuel/tax/adapter/in/web/TaxInvoiceSellerControllerTest.java`
- Test: `settlement-service/src/test/java/github/lms/lemuel/settlement/adapter/in/web/SellerEndpointSecurityTest.java`

**Interfaces:**
- SELLER endpoints derive `sellerId` only from `AuthPrincipal.userId()` and do not accept it as a request parameter.
- MANAGER/ADMIN cross-seller searches remain under separate admin endpoints and require an explicit sellerId filter.
- `SettlementSearchCondition` gains `sellerId`; seller APIs set it from the principal before calling the query port.
- `LoadOrderPort.findBySellerId(long sellerId, Long cursorId, int size)`, `LoadPaymentAllocationPort.findBySellerId(long sellerId, Long cursorId, int size)`, and `LoadPayoutPort.findBySellerId(long sellerId, Long cursorId, int size)` must push seller predicates into SQL.

- [ ] Add failing tests proving SELLER sees only own orders, allocations, settlements, tax invoices, and payouts; another seller's ids return the API's consistent 403/404 policy.
- [ ] Add failing tests proving USER cannot access seller APIs and MANAGER/ADMIN use admin APIs for cross-seller search.
- [ ] Run `./gradlew.bat :order-service:test --tests "*SellerOrder*" --tests "*SellerPaymentAllocation*"` and `./gradlew.bat :settlement-service:test --tests "*SellerScope*" --tests "*PayoutSeller*" --tests "*TaxInvoiceSeller*"`; expect failures.
- [ ] Implement repository predicates with mandatory sellerId for SELLER entry points. Never fetch an unscoped collection and filter it in memory.
- [ ] Put principal-self seller reads under `/api/seller/**` and allow SELLER only. Keep `/api/settlements/**`, `/admin/**`, reconciliation, approval, platform aggregations, and explicit cross-seller filters restricted to MANAGER/ADMIN. Add endpoint-ordering security tests proving MANAGER/ADMIN use admin paths and cannot accidentally rely on seller-self paths.
- [ ] Ensure payout responses reuse existing account masking and never expose account number, resident number, card number, or real name in logs.
- [ ] Run the targeted seller query suites; expect PASS.
- [ ] Commit Task 9B: `git commit -m "feat(seller): scope sales and settlement views to principal"`.

## Task 10: RefundAllocation and allocation-scoped refund Outbox

**Files:**
- Create: `order-service/src/main/resources/db/migration/V20260727102000__refund_allocations.sql`
- Create: `order-service/src/main/java/github/lms/lemuel/payment/domain/RefundAllocation.java`
- Create: `order-service/src/main/java/github/lms/lemuel/payment/adapter/out/persistence/RefundAllocationJpaEntity.java`
- Create: `order-service/src/main/java/github/lms/lemuel/payment/adapter/out/persistence/SpringDataRefundAllocationRepository.java`
- Create: `order-service/src/main/java/github/lms/lemuel/payment/adapter/out/persistence/RefundAllocationPersistenceAdapter.java`
- Create: `order-service/src/main/java/github/lms/lemuel/payment/application/service/AllocateRefundService.java`
- Modify: `../../../../order-service/src/main/java/github/lms/lemuel/payment/application/RefundPaymentUseCase.java`
- Modify: `../../../../order-service/src/main/java/github/lms/lemuel/payment/application/service/RefundLifecycle.java`
- Modify: `../../../../order-service/src/main/java/github/lms/lemuel/payment/application/service/RefundSplitPaymentService.java`
- Modify: `../../../../order-service/src/main/java/github/lms/lemuel/payment/application/port/out/PublishEventPort.java`
- Modify: `../../../../order-service/src/main/java/github/lms/lemuel/payment/adapter/out/event/OutboxBackedEventPublisher.java`
- Test: `order-service/src/test/java/github/lms/lemuel/payment/domain/RefundAllocationTest.java`
- Test: `order-service/src/test/java/github/lms/lemuel/payment/application/service/AllocateRefundServiceTest.java`
- Test: `order-service/src/test/java/github/lms/lemuel/payment/integration/RefundAllocationConcurrencyIT.java`
- Create: `shared-common/src/testFixtures/resources/contracts/events/lemuel.payment.allocation_refunded.schema.json`
- Create: `shared-common/src/testFixtures/resources/contracts/events/samples/lemuel.payment.allocation_refunded.sample.json`
- Modify: `../../../../order-service/src/test/java/github/lms/lemuel/payment/adapter/out/event/PaymentEventContractTest.java`
- Modify: `../../../../shared-common/src/test/java/github/lms/lemuel/common/events/contract/EventContractFixtureTest.java`
- Modify: `../../../../order-service/src/test/java/github/lms/lemuel/payment/application/RefundPaymentUseCaseTest.java`
- Modify: `../../../../order-service/src/test/java/github/lms/lemuel/kafka/KafkaOutboxIntegrationTest.java`
- Modify: `../../../../order-service/src/test/java/github/lms/lemuel/schema/SchemaIntegrationTest.java`

**Interfaces:**
- `List<RefundAllocation> allocate(long refundId, long paymentId, List<RequestedAllocation> exactTargets)`
- `publishPaymentAllocationRefunded(long refundAllocationId, long refundId, long paymentAllocationId, long paymentId, long orderId, long sellerId, BigDecimal amount, LocalDateTime refundedAt)`

- [ ] Add failing tests for exact partial targets, deterministic full-payment refund ordered by allocationId, duplicate request replay, and two concurrent refunds that would exceed one allocation. Include `amount > 0`, supported scale/precision, and requested/accumulated amount not exceeding the captured allocation.
- [ ] Add failing event contract tests requiring decimal-string amount and stable refundAllocationId/paymentAllocationId/paymentId/orderId/sellerId fields.
- [ ] Run `./gradlew.bat :order-service:test --tests "*RefundAllocation*"`; expect failures.
- [ ] Add `refund_allocations` with unique `(refund_id,payment_allocation_id)`, positive amount checks, and an atomic/trigger guard that completed refunds never exceed captured allocation amount.
- [ ] Lock target allocations, compute remaining refundable amount with `BigDecimal`, persist completed allocations, and insert `PaymentAllocationRefunded` Outbox rows in one transaction.
- [ ] Run refund allocation, outbox, and schema tests; expect PASS.
- [ ] Commit Task 10: `git commit -m "feat(payment): allocate refunds to seller payments"`.

## Task 11: Append-only settlement adjustment and allocation-scoped ledger reversal

**Files:**
- Create: `settlement-service/src/main/resources/db/migration/V20260727104000__refund_allocation_adjustment_source.sql`
- Modify: `../../../../settlement-service/src/main/java/github/lms/lemuel/settlement/domain/SettlementAdjustment.java`
- Modify: `../../../../settlement-service/src/main/java/github/lms/lemuel/settlement/adapter/out/persistence/SettlementAdjustmentJpaEntity.java`
- Modify: `../../../../settlement-service/src/main/java/github/lms/lemuel/settlement/adapter/out/persistence/SettlementAdjustmentPersistenceAdapter.java`
- Create: `settlement-service/src/main/java/github/lms/lemuel/settlement/application/port/out/LoadSettlementAdjustmentPort.java`
- Modify: `../../../../settlement-service/src/main/java/github/lms/lemuel/settlement/application/port/out/SaveSettlementAdjustmentPort.java`
- Create: `settlement-service/src/main/java/github/lms/lemuel/settlement/adapter/in/kafka/PaymentAllocationRefundedKafkaConsumer.java`
- Create: `settlement-service/src/main/java/github/lms/lemuel/settlement/application/service/AdjustSettlementForRefundAllocationService.java`
- Modify: `../../../../settlement-service/src/main/java/github/lms/lemuel/settlement/application/service/AdjustSettlementForRefundService.java`
- Modify: `../../../../settlement-service/src/main/java/github/lms/lemuel/ledger/domain/ReferenceType.java`
- Modify: `../../../../settlement-service/src/main/java/github/lms/lemuel/ledger/domain/LedgerOutboxTask.java`
- Modify: `../../../../settlement-service/src/main/java/github/lms/lemuel/ledger/adapter/out/persistence/LedgerOutboxJpaEntity.java`
- Modify: `../../../../settlement-service/src/main/java/github/lms/lemuel/ledger/application/service/LedgerOutboxService.java`
- Modify: `../../../../settlement-service/src/main/java/github/lms/lemuel/ledger/application/port/in/EnqueueLedgerTaskPort.java`
- Modify: `../../../../settlement-service/src/main/java/github/lms/lemuel/ledger/application/service/ReverseEntryService.java`
- Test: `settlement-service/src/test/java/github/lms/lemuel/settlement/adapter/in/kafka/PaymentAllocationRefundedKafkaConsumerTest.java`
- Test: `settlement-service/src/test/java/github/lms/lemuel/settlement/integration/RefundAllocationAdjustmentConcurrencyIT.java`
- Test: `settlement-service/src/test/java/github/lms/lemuel/ledger/integration/RefundAllocationLedgerReversalIT.java`
- Modify: `../../../../settlement-service/src/test/java/github/lms/lemuel/settlement/application/service/AdjustSettlementForRefundServiceTest.java`
- Modify: `../../../../settlement-service/src/test/java/github/lms/lemuel/settlement/adapter/in/kafka/PaymentRefundedSettlementAdjustConsumerTest.java`

**Interfaces:**
- `SettlementAdjustment adjustSettlementForRefundAllocation(long paymentAllocationId, long refundAllocationId, BigDecimal amount)`
- `ReferenceType.REFUND_ALLOCATION`, with ledger `referenceId = refundAllocationId`.

- [ ] Add failing tests where two seller allocations share one refund: both adjustment and reversal records must exist; replay of either allocation is ignored.
- [ ] Extend `PaymentAllocationSettlementRefundIT` with allocation refund→adjustment→ledger reversal and verify RED before implementation.
- [ ] Add a failing immutability test asserting settlement amount/status and POSTED ledger rows are unchanged after an allocation refund.
- [ ] Add failing fee symmetry tests using the original settlement `commission_rate` snapshot for every refund allocation: all three seller tiers, 0/1 won boundaries, and `.5` HALF_UP cases. Assert accumulated refunded commission never exceeds the original commission and a full refund returns exactly the original net/commission totals.
- [ ] Run `./gradlew.bat :settlement-service:test --tests "*RefundAllocation*"`; expect failures.
- [ ] Add `source_refund_allocation_id` with partial uniqueness and extend the adjustment source XOR. Do not also populate legacy `refund_id` for allocation adjustments.
- [ ] Consume `PaymentAllocationRefunded` with processed-event idempotency, append the adjustment, enqueue a ledger task with `REFUND_ALLOCATION`, and append positive reversal rows with debit/credit swapped.
- [ ] Keep the legacy refund path isolated behind its cutover flag; remove mutation of historical settlement totals/status from the new path.
- [ ] Run refund adjustment, ledger, and settlement regression tests; expect PASS.
- [ ] Commit Task 11: `git commit -m "feat(settlement): reverse refunds by payment allocation"`.

## Task 12: Cutover validation, contract migration and end-to-end proof

**Files:**
- Create: `settlement-service/src/main/resources/db/migration/V20260727110000__settlement_allocation_cutover.sql`
- Modify: `../../../../settlement-service/src/main/java/github/lms/lemuel/settlement/adapter/out/persistence/SettlementJpaEntity.java`
- Modify: `../../../../order-service/src/main/resources/application.yml`
- Modify: `../../../../settlement-service/src/main/resources/application.yml`
- Create: `docs/runbooks/payment-allocation-cutover.md`

**Interfaces and gates:**
- Expand deployment (Tasks 8–11) must be live before contract deployment.
- Contract gate applies to `SELLER_ORDER` payments created at/after the recorded cutover epoch: every captured payment has allocations; every allocation has exactly one settlement; allocation sums equal payment amounts; allocation refund sums do not exceed captured amounts; both Outboxes have zero unresolved failures; allocation consumers have zero lag. Pre-cutover `LEGACY_MIXED_SELLER` rows remain quarantined and are explicitly excluded from this gate/report.
- Cutover is three operational stages with globally consistent configuration: (1) turn legacy settlement creation OFF on every pod and prove zero legacy-consumer lag/activity, (2) deploy the contract migration and new readers, (3) enable multi-seller checkout on every order pod. No stage shares a rolling deployment with the next.

- [ ] Assemble and run the cross-service acceptance tests created in Tasks 7–11: USER cart with two sellers → one checkout group → two orders → one payment → two captured allocations → two settlements → partial refund of one allocation → one append-only adjustment and one ledger reversal. This is verification, not a new RED step.
- [ ] Run the role matrix E2E tests created in Tasks 1, 4, 5, and 9B: SELLER cannot checkout, SELLER can mutate only own product, MANAGER/ADMIN can register on behalf of approved SELLER, and dev platform seller is unavailable outside `dev`.
- [ ] Run focused verification:

```powershell
./gradlew.bat :shared-common:test --tests "github.lms.lemuel.common.config.jwt.*"
./gradlew.bat :order-service:test --tests "github.lms.lemuel.user.*" --tests "github.lms.lemuel.product.*"
./gradlew.bat :order-service:test --tests "github.lms.lemuel.checkout.*" --tests "github.lms.lemuel.payment.*Allocation*"
./gradlew.bat :settlement-service:test --tests "*PaymentAllocation*" --tests "*RefundAllocation*"
```

- [ ] Implement the runbook checks using service metrics/MCP diagnostics only; include rollback by re-enabling legacy consumer before contract, never by deleting settlement/ledger history.
- [ ] Deploy expand and replay legacy allocations. Attach sources to existing settlements, resolve all non-mixed quarantine items, and verify the gates for a full observation window.
- [ ] Stage 1: use centralized deployment configuration to set `legacy-payment-captured-settlement-enabled=false` on every settlement pod. Wait for the old consumer group to reach zero lag and verify no legacy settlement creation during an observation window; abort if any old pod remains.
- [ ] Stage 2: in a separate deployment, apply the contract migration, drop payment-level settlement uniqueness, enforce allocation source for post-cutover settlements, and keep multi-seller checkout OFF. Re-run allocation consumer/idempotency tests and live gate checks.
- [ ] Stage 3: in a separate order-service deployment, set `app.multi-seller-checkout.enabled=true` on every pod. Start with development, then canary; roll back by turning this flag OFF, never by deleting created settlements or ledger rows.
- [ ] Run complete verification:

```powershell
./gradlew.bat :shared-common:test :order-service:test :settlement-service:test
```

- [ ] Request a separate code-reviewer/verifier pass covering authorization, migration safety, BigDecimal conservation, Outbox/consumer idempotency, and immutable history. Resolve every blocking finding and rerun affected suites.
- [ ] Commit Task 12: `git commit -m "docs(runbook): define payment allocation cutover gates"`.

## Completion Evidence

- Role and endpoint matrix tests pass for USER/SELLER/MANAGER/ADMIN.
- JWT stale/missing tokenVersion tests pass.
- Seller ownership and cross-user IDOR tests pass.
- Checkout/order/payment/refund allocation conservation and concurrency tests pass.
- Outbox contract and processed-event replay tests pass.
- Settlement/ledger append-only tests pass.
- Full `shared-common`, `order-service`, and `settlement-service` test suites pass.
- A separate verifier records approval after reviewing the final diff and test output.
