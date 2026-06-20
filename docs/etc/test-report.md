# 테스트 결과 보고서

## 개요

| 항목 | 수치 |
|------|------|
| 총 테스트 클래스 | 35개 |
| 총 테스트 케이스 | 159개 이상 |
| 추정 라인 커버리지 | ~85-90% |
| JaCoCo 최소 기준 | 70% |

---

## 도메인 테스트

도메인 모델의 비즈니스 로직을 검증하는 단위 테스트.

| 테스트 클래스 | 케이스 수 | 검증 대상 |
|--------------|----------|----------|
| PaymentDomainTest | 13 | 결제 상태 전이, 금액 계산, 환불 로직 |
| SettlementFullTest | 20 | 정산 생성, 수수료 계산, 상태 전이, 환불 조정 |
| ProductTest | 21 | 상품 생성, 수정, 유효성 검증 |
| CouponTest | 17 | 정액/정률 할인, 최대 할인 상한, 유효기간 |
| ReviewTest | 3 | 리뷰 생성, 수정, 삭제 |
| OrderTest | - | 주문 생성, 상태 전이 |
| UserTest | - | 사용자 생성, 정보 변경 |
| UserRoleTest | - | 역할 관리, 권한 검증 |
| CategoryTest | - | 카테고리 생성, 계층 구조 |

---

## 서비스 테스트

유스케이스 구현체의 동작을 검증하는 테스트.

### 결제 서비스

| 테스트 클래스 | 케이스 수 | 검증 대상 |
|--------------|----------|----------|
| RefundPaymentUseCaseTest | 3 | 환불 처리, PG 연동 |
| CreatePaymentUseCaseTest | 1 | 결제 생성 |
| CapturePaymentUseCaseTest | 2 | 결제 캡처 |
| AuthorizePaymentUseCaseTest | 2 | 결제 승인 |
| GetPaymentUseCaseTest | 2 | 결제 조회 |

### 주문 서비스

| 테스트 클래스 | 케이스 수 | 검증 대상 |
|--------------|----------|----------|
| CreateOrderServiceTest | - | 주문 생성 |
| GetOrderServiceTest | 4 | 주문 조회, 목록 조회 |
| ChangeOrderStatusServiceTest | 2 | 주문 상태 변경 |

### 정산 서비스

| 테스트 클래스 | 케이스 수 | 검증 대상 |
|--------------|----------|----------|
| CreateDailySettlementsServiceTest | - | 일일 배치 정산 생성 |
| AdjustSettlementForRefundServiceTest | - | 환불 시 정산 조정 |
| ConfirmDailySettlementsServiceTest | - | 정산 확정 처리 |
| GetSettlementServiceTest | 4 | 정산 조회 |
| CreateSettlementFromPaymentServiceTest | 2 | 결제 기반 정산 생성 |
| IndexSettlementServiceTest | 7 | Elasticsearch 인덱싱 |
| GenerateSettlementPdfServiceTest | 1 | PDF 생성 |

### 회원 서비스

| 테스트 클래스 | 케이스 수 | 검증 대상 |
|--------------|----------|----------|
| LoginServiceTest | - | 로그인 처리 |
| CreateUserServiceTest | - | 회원가입 |
| GetUserServiceTest | 5 | 사용자 조회 |
| PasswordResetServiceTest | 1 | 비밀번호 재설정 |

### 쿠폰 서비스

| 테스트 클래스 | 케이스 수 | 검증 대상 |
|--------------|----------|----------|
| CouponServiceTest | - | 쿠폰 생성, 적용, 유효성 검증 |

---

## 보안 테스트

| 테스트 클래스 | 케이스 수 | 검증 대상 |
|--------------|----------|----------|
| JwtUtilTest | 5 | JWT 토큰 생성, 검증, 만료 처리 |

---

## 컨트롤러 테스트

REST API 엔드포인트 동작을 검증하는 테스트.

| 테스트 클래스 | 케이스 수 | 검증 대상 |
|--------------|----------|----------|
| OrderControllerTest | 5 | 주문 API (생성, 조회, 취소) |
| SettlementControllerTest | 5 | 정산 API (조회, 확정) |
| SettlementSearchControllerTest | - | 정산 검색 API (Elasticsearch) |

---

## 통합 테스트

| 테스트 클래스 | 검증 대상 |
|--------------|----------|
| ShoppingFlowIntegrationTest | 전체 쇼핑 흐름 (상품 조회 → 주문 → 결제 → 정산) |

---

## 커버리지 요약

- **추정 라인 커버리지**: ~85-90%
- **JaCoCo 최소 기준**: 70% (충족)
- 도메인 모델에 대한 테스트 밀도가 가장 높으며, 핵심 비즈니스 로직(결제 상태 전이, 정산 계산, 쿠폰 할인)에 대해 집중적으로 검증
- 통합 테스트를 통해 전체 쇼핑 플로우의 End-to-End 동작 검증 완료
