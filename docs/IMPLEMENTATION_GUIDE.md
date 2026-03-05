# 정산 시스템 MVP 개선 가이드

## 목표
정산 시스템을 "바이브 코딩" → "실무 수준 포트폴리오"로 업그레이드

---

## 📋 전체 작업 체크리스트

### ✅ Phase 1: 프론트엔드 UX 개선 (TOP 7)

#### 1. 조회 필터 UX ✅
- [x] 정산 상태: Select로 enum 고정
- [x] 환불 여부: Select로 변경
- [x] DateRangePicker 컴포넌트 생성
- [x] 빠른 필터 (최근 7일/30일/이번 달)

**구현 완료:**
- `frontend/src/components/DateRangePicker.tsx`
- `frontend/src/pages/SettlementDashboardImproved.tsx`

#### 2. 테이블 결과 영역 ✅
- [x] 페이지네이션
- [x] 정렬 (클릭 시 ASC/DESC 토글)
- [x] 로딩 스켈레톤
- [x] Empty State

**구현 완료:**
- `frontend/src/components/LoadingSkeleton.tsx`
- `frontend/src/components/EmptyState.tsx`

#### 3. 에러/검증 🔄
- [x] 시작일 > 종료일 검증
- [ ] Axios Interceptor (401/403 공통 처리)

**TODO:**
```typescript
// frontend/src/api/axios.ts
api.interceptors.response.use(
  (response) => response,
  (error) => {
    if (error.response?.status === 401) {
      // Refresh token 시도
      // 실패 시 로그아웃
    }
    if (error.response?.status === 403) {
      showToast('권한이 없습니다.', 'error');
    }
    return Promise.reject(error);
  }
);
```

#### 4. 상태 뱃지 ✅
- [x] StatusBadge 컴포넌트
- [x] 정산 상태별 색상 구분

**구현 완료:**
- `frontend/src/components/StatusBadge.tsx`

#### 5. 검색 버튼 비활성화 ✅
- [x] 날짜 검증 실패 시 disabled
- [x] 로딩 중 disabled
- [x] Tooltip 표시

**구현 완료:**
- `frontend/src/pages/SettlementDashboardImproved.tsx:212-230`

#### 6. 로그아웃/세션 UX 🔄
- [x] Toast 컴포넌트
- [x] ToastContext
- [ ] 토큰 만료 시 자동 로그아웃 + 토스트

**구현 완료:**
- `frontend/src/components/Toast.tsx`
- `frontend/src/contexts/ToastContext.tsx`

**TODO:**
```typescript
// Axios interceptor에서 처리
if (error.response?.status === 401) {
  showToast('세션이 만료되었습니다. 다시 로그인해주세요.', 'warning');
  authApi.logout();
  window.location.href = '/login';
}
```

#### 7. 권한별 메뉴 분리 ⏳
- [ ] Layout 컴포넌트 개선
- [ ] USER/ADMIN 메뉴 분리

**TODO:**
```typescript
// frontend/src/components/Layout.tsx
const user = authApi.getCurrentUser();
const menuItems = user?.role === 'ADMIN'
  ? ['/order', '/dashboard', '/admin']
  : ['/order', '/dashboard'];
```

---

### 🎯 Phase 2: 백엔드 정산 도메인 강화 (5개)

#### 1. 정산 생성 트리거 ⏳
**목표:** 결제 완료(PAID/CAPTURED) 시 자동으로 정산 생성

**구현 방법:**
```java
// PaymentService.java
@Transactional
public Payment capturePayment(Long paymentId) {
    Payment payment = paymentRepository.findById(paymentId)
        .orElseThrow(() -> new PaymentNotFoundException(paymentId));

    payment.capture();
    paymentRepository.save(payment);

    // 정산 생성 트리거
    settlementService.createSettlementFromPayment(payment);
    // 또는 이벤트 발행: applicationEventPublisher.publishEvent(new PaymentCapturedEvent(payment));

    return payment;
}
```

```java
// SettlementService.java
public Settlement createSettlementFromPayment(Payment payment) {
    // 이미 정산이 생성되었는지 확인 (idempotency)
    Optional<Settlement> existing = settlementRepository.findByPaymentId(payment.getId());
    if (existing.isPresent()) {
        return existing.get();
    }

    Settlement settlement = Settlement.builder()
        .paymentId(payment.getId())
        .orderId(payment.getOrderId())
        .paymentAmount(payment.getAmount())
        .netAmount(calculateNetAmount(payment))
        .commission(calculateCommission(payment))
        .status(SettlementStatus.REQUESTED)
        .build();

    return settlementRepository.save(settlement);
}
```

#### 2. 정산 상태 머신 ⏳
**목표:** REQUESTED → PROCESSING → DONE/FAILED

**구현:**
```java
// Settlement.java (도메인)
public enum SettlementStatus {
    REQUESTED,      // 정산 요청됨
    PROCESSING,     // 처리 중
    DONE,           // 완료
    FAILED          // 실패
}

public class Settlement {
    public void startProcessing() {
        if (this.status != SettlementStatus.REQUESTED) {
            throw new IllegalStateException("REQUESTED 상태만 처리 시작 가능");
        }
        this.status = SettlementStatus.PROCESSING;
        this.updatedAt = LocalDateTime.now();
    }

    public void complete() {
        if (this.status != SettlementStatus.PROCESSING) {
            throw new IllegalStateException("PROCESSING 상태만 완료 가능");
        }
        this.status = SettlementStatus.DONE;
        this.confirmedAt = LocalDateTime.now();
    }

    public void fail(String reason) {
        if (this.status != SettlementStatus.PROCESSING) {
            throw new IllegalStateException("PROCESSING 상태만 실패 처리 가능");
        }
        this.status = SettlementStatus.FAILED;
        this.failureReason = reason;
    }

    public boolean canRetry() {
        return this.status == SettlementStatus.FAILED;
    }
}
```

**배치 작업 예시:**
```java
@Scheduled(cron = "0 */10 * * * *") // 10분마다
public void processSettlements() {
    List<Settlement> requested = settlementRepository.findByStatus(SettlementStatus.REQUESTED);

    for (Settlement settlement : requested) {
        try {
            settlement.startProcessing();
            settlementRepository.save(settlement);

            // 실제 정산 처리 로직 (외부 API 호출 등)
            processToExternalSystem(settlement);

            settlement.complete();
            settlementRepository.save(settlement);
        } catch (Exception e) {
            settlement.fail(e.getMessage());
            settlementRepository.save(settlement);
        }
    }
}
```

#### 3. 환불 연동 ⏳
**목표:** 환불 발생 시 정산에 반영

**구현:**
```java
// RefundService.java
@Transactional
public Refund processRefund(Long paymentId, RefundRequest request) {
    Payment payment = paymentRepository.findById(paymentId)
        .orElseThrow(() -> new PaymentNotFoundException(paymentId));

    // 환불 처리
    Refund refund = payment.refund(request.getAmount(), request.getReason());
    refundRepository.save(refund);

    // 정산 조정
    settlementService.adjustForRefund(payment, refund);

    return refund;
}
```

```java
// SettlementService.java
public void adjustForRefund(Payment payment, Refund refund) {
    Settlement settlement = settlementRepository.findByPaymentId(payment.getId())
        .orElseThrow(() -> new SettlementNotFoundException(payment.getId()));

    settlement.adjustForRefund(refund.getAmount());
    settlementRepository.save(settlement);
}
```

```java
// Settlement.java
public void adjustForRefund(BigDecimal refundAmount) {
    this.refundedAmount = this.refundedAmount.add(refundAmount);
    this.netAmount = this.paymentAmount.subtract(this.refundedAmount).subtract(this.commission);

    // 환불로 인해 정산 금액이 0이하가 되면 취소 처리
    if (this.netAmount.compareTo(BigDecimal.ZERO) <= 0) {
        this.status = SettlementStatus.CANCELED;
    }
}
```

#### 4. 조회 성능 개선 ⏳
**목표:** 인덱스 추가, 페이징 최적화

**데이터베이스 인덱스:**
```sql
-- settlement 테이블
CREATE INDEX idx_settlement_status ON settlement(status);
CREATE INDEX idx_settlement_date ON settlement(settlement_date);
CREATE INDEX idx_settlement_created_at ON settlement(created_at);
CREATE INDEX idx_settlement_payment_id ON settlement(payment_id);
CREATE INDEX idx_settlement_order_id ON settlement(order_id);

-- 복합 인덱스 (자주 함께 조회되는 컬럼)
CREATE INDEX idx_settlement_status_date ON settlement(status, settlement_date);
```

**Querydsl 페이징:**
```java
// SettlementQueryRepository.java
public Page<SettlementSearchItem> search(SettlementSearchRequest request, Pageable pageable) {
    QueryResults<SettlementSearchItem> results = queryFactory
        .select(Projections.constructor(SettlementSearchItem.class,
            settlement.id,
            order.id,
            payment.id,
            order.ordererName,
            order.productName,
            settlement.paymentAmount,
            settlement.refundedAmount,
            settlement.netAmount,
            settlement.status,
            payment.refundedAmount.gt(0),
            settlement.settlementDate,
            settlement.createdAt
        ))
        .from(settlement)
        .join(settlement.payment, payment)
        .join(payment.order, order)
        .where(
            statusEq(request.getStatus()),
            ordererNameContains(request.getOrdererName()),
            productNameContains(request.getProductName()),
            isRefunded(request.getIsRefunded()),
            settlementDateBetween(request.getStartDate(), request.getEndDate())
        )
        .offset(pageable.getOffset())
        .limit(pageable.getPageSize())
        .orderBy(getOrderSpecifier(pageable.getSort()))
        .fetchResults();

    return new PageImpl<>(results.getResults(), pageable, results.getTotal());
}
```

#### 5. Idempotency Key ⏳
**목표:** 중복 요청 방지

**구현:**
```java
// IdempotencyKey 엔티티
@Entity
@Table(name = "idempotency_keys")
public class IdempotencyKey {
    @Id
    private String key;

    private String resourceType;  // ORDER, PAYMENT, REFUND
    private Long resourceId;
    private LocalDateTime createdAt;
    private LocalDateTime expiresAt;
}
```

```java
// OrderController.java
@PostMapping
public ResponseEntity<OrderResponse> createOrder(
    @RequestHeader("Idempotency-Key") String idempotencyKey,
    @Valid @RequestBody OrderCreateRequest request
) {
    // Idempotency 체크
    Optional<IdempotencyKey> existing = idempotencyRepository.findById(idempotencyKey);
    if (existing.isPresent()) {
        Long orderId = existing.get().getResourceId();
        Order order = orderService.getOrder(orderId);
        return ResponseEntity.ok(OrderResponse.from(order));
    }

    // 주문 생성
    Order order = orderService.createOrder(request);

    // Idempotency Key 저장
    idempotencyRepository.save(new IdempotencyKey(
        idempotencyKey,
        "ORDER",
        order.getId(),
        LocalDateTime.now().plusHours(24)
    ));

    return ResponseEntity.status(HttpStatus.CREATED).body(OrderResponse.from(order));
}
```

---

### 🔐 Phase 3: 인증/보안

#### 1. Refresh Token ⏳
**구현:**
```java
// JwtService.java
public String generateAccessToken(String email) {
    return Jwts.builder()
        .setSubject(email)
        .setIssuedAt(new Date())
        .setExpiration(new Date(System.currentTimeMillis() + ACCESS_TOKEN_VALIDITY))
        .signWith(SignatureAlgorithm.HS512, secret)
        .compact();
}

public String generateRefreshToken(String email) {
    return Jwts.builder()
        .setSubject(email)
        .setIssuedAt(new Date())
        .setExpiration(new Date(System.currentTimeMillis() + REFRESH_TOKEN_VALIDITY))
        .signWith(SignatureAlgorithm.HS512, secret)
        .compact();
}
```

```java
// AuthController.java
@PostMapping("/refresh")
public ResponseEntity<TokenResponse> refresh(@RequestBody RefreshTokenRequest request) {
    String refreshToken = request.getRefreshToken();

    if (!jwtService.validateToken(refreshToken)) {
        throw new InvalidTokenException();
    }

    String email = jwtService.getEmailFromToken(refreshToken);
    String newAccessToken = jwtService.generateAccessToken(email);

    return ResponseEntity.ok(new TokenResponse(newAccessToken));
}
```

#### 2. Axios Interceptor ⏳
```typescript
// frontend/src/api/axios.ts
let isRefreshing = false;
let failedQueue: any[] = [];

api.interceptors.response.use(
  (response) => response,
  async (error) => {
    const originalRequest = error.config;

    if (error.response?.status === 401 && !originalRequest._retry) {
      if (isRefreshing) {
        return new Promise((resolve, reject) => {
          failedQueue.push({ resolve, reject });
        })
          .then((token) => {
            originalRequest.headers['Authorization'] = 'Bearer ' + token;
            return api(originalRequest);
          })
          .catch((err) => Promise.reject(err));
      }

      originalRequest._retry = true;
      isRefreshing = true;

      const refreshToken = localStorage.getItem('refresh_token');

      try {
        const response = await api.post('/auth/refresh', { refreshToken });
        const { accessToken } = response.data;

        localStorage.setItem('access_token', accessToken);
        api.defaults.headers.common['Authorization'] = 'Bearer ' + accessToken;

        failedQueue.forEach((prom) => prom.resolve(accessToken));
        failedQueue = [];

        return api(originalRequest);
      } catch (err) {
        failedQueue.forEach((prom) => prom.reject(err));
        failedQueue = [];

        authApi.logout();
        window.location.href = '/login';
        return Promise.reject(err);
      } finally {
        isRefreshing = false;
      }
    }

    return Promise.reject(error);
  }
);
```

#### 3. @PreAuthorize ⏳
```java
// SecurityConfig.java
@EnableGlobalMethodSecurity(prePostEnabled = true)
public class SecurityConfig {
    // ...
}

// SettlementController.java
@PreAuthorize("hasRole('ADMIN')")
@PostMapping("/{id}/approve")
public ResponseEntity<SettlementResponse> approveSettlement(@PathVariable Long id) {
    Settlement settlement = settlementService.approve(id);
    return ResponseEntity.ok(SettlementResponse.from(settlement));
}

@PreAuthorize("hasAnyRole('USER', 'ADMIN')")
@GetMapping
public ResponseEntity<Page<SettlementResponse>> getSettlements(Pageable pageable) {
    // ...
}
```

---

### 🧪 Phase 4: 테스트

#### 1. 도메인 단위 테스트 ⏳
```java
// SettlementTest.java
class SettlementTest {
    @Test
    @DisplayName("정산 상태 머신: REQUESTED -> PROCESSING")
    void testSettlementStateMachine() {
        Settlement settlement = Settlement.builder()
            .status(SettlementStatus.REQUESTED)
            .build();

        settlement.startProcessing();

        assertThat(settlement.getStatus()).isEqualTo(SettlementStatus.PROCESSING);
    }

    @Test
    @DisplayName("환불 시 정산 금액 조정")
    void testAdjustForRefund() {
        Settlement settlement = Settlement.builder()
            .paymentAmount(new BigDecimal("10000"))
            .commission(new BigDecimal("1000"))
            .refundedAmount(BigDecimal.ZERO)
            .netAmount(new BigDecimal("9000"))
            .build();

        settlement.adjustForRefund(new BigDecimal("3000"));

        assertThat(settlement.getRefundedAmount()).isEqualByComparingTo("3000");
        assertThat(settlement.getNetAmount()).isEqualByComparingTo("6000");
    }
}
```

#### 2. 컨트롤러 인수 테스트 ⏳
```java
// SettlementControllerTest.java
@SpringBootTest
@AutoConfigureMockMvc
class SettlementControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("정산 조회 API 테스트")
    @WithMockUser(roles = "ADMIN")
    void testSearchSettlements() throws Exception {
        mockMvc.perform(get("/settlements")
                .param("status", "REQUESTED")
                .param("page", "0")
                .param("size", "20"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.settlements").isArray())
            .andExpect(jsonPath("$.totalElements").exists());
    }
}
```

---

### 📚 Phase 5: 문서화

#### 1. Swagger/OpenAPI ⏳
```java
// SwaggerConfig.java
@Configuration
@EnableSwagger2
public class SwaggerConfig {
    @Bean
    public Docket api() {
        return new Docket(DocumentationType.OAS_30)
            .select()
            .apis(RequestHandlerSelectors.basePackage("github.lms.lemuel"))
            .paths(PathSelectors.any())
            .build()
            .apiInfo(apiInfo());
    }

    private ApiInfo apiInfo() {
        return new ApiInfoBuilder()
            .title("Lemuel Settlement API")
            .description("정산 시스템 API 문서")
            .version("1.0.0")
            .build();
    }
}
```

```java
// SettlementController.java
@Tag(name = "Settlement", description = "정산 API")
@RestController
@RequestMapping("/settlements")
public class SettlementController {

    @Operation(summary = "정산 조회", description = "검색 조건에 따라 정산 내역을 조회합니다")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "조회 성공"),
        @ApiResponse(responseCode = "400", description = "잘못된 요청"),
        @ApiResponse(responseCode = "401", description = "인증 실패")
    })
    @GetMapping
    public ResponseEntity<SettlementSearchResponse> search(
        @Parameter(description = "검색 조건") SettlementSearchRequest request,
        Pageable pageable
    ) {
        // ...
    }
}
```

#### 2. README ⏳
작성 필요:
- 아키텍처 다이어그램 (헥사고날/클린 아키텍처)
- ERD
- API 시나리오 (주문 → 결제 → 정산 → 환불)
- 로컬 실행 방법
- 기술 스택

---

## 🚀 구현 우선순위

### 즉시 적용 (High Priority)
1. ✅ 프론트엔드 UX 개선 (DatePicker, StatusBadge, EmptyState, Skeleton)
2. ⏳ Axios Interceptor (401/403 처리)
3. ⏳ 권한별 메뉴 분리
4. ⏳ 백엔드: 결제 완료 → 정산 생성 트리거
5. ⏳ 백엔드: 정산 상태 머신

### 중요 (Medium Priority)
6. ⏳ 환불 연동 및 정산 반영
7. ⏳ Idempotency Key
8. ⏳ 조회 성능 개선 (인덱스)
9. ⏳ Refresh Token

### 추가 개선 (Low Priority)
10. ⏳ 도메인 단위 테스트
11. ⏳ 컨트롤러 인수 테스트
12. ⏳ Swagger 문서
13. ⏳ README 작성

---

## 📝 다음 단계

1. **App.tsx에 ToastProvider 적용**
2. **SettlementDashboard 교체**
3. **Axios Interceptor 구현**
4. **백엔드: 정산 생성 트리거 구현**
5. **백엔드: 정산 상태 머신 구현**

---

## 💡 포트폴리오 차별화 포인트

1. **헥사고날 아키텍처** - 포트/어댑터 패턴 명확히 구분
2. **도메인 중심 설계** - 상태 머신, 비즈니스 규칙이 도메인에 응집
3. **Idempotency** - 실무 수준의 중복 방지
4. **성능 최적화** - 인덱스, 페이징, Read 모델 분리
5. **테스트 커버리지** - 도메인 테스트 + 통합 테스트
6. **API 문서화** - Swagger로 자동 생성
7. **UX 디테일** - 로딩 상태, 에러 처리, Empty State

이 프로젝트를 완성하면 "주니어 개발자가 만든 CRUD"가 아닌 **"실무 경험이 있는 개발자의 정산 시스템"**으로 보입니다.
