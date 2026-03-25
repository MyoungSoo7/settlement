# 요구사항 추적 매트릭스 (RTM: Requirements Traceability Matrix)

| 항목 | 내용 |
|------|------|
| 문서번호 | LML-RTM-001 |
| 버전 | 1.0 |
| 작성일 | 2026-03-25 |
| 작성자 | AA (Application Architect) |
| 승인자 | PM |
| 문서상태 | 초안 |
| 기밀등급 | 대외비 |

---

## 버전 이력

| 버전 | 작성일 | 작성자 | 변경 내용 |
|------|--------|--------|-----------|
| 0.1 | 2026-03-25 | AA | 초안 작성 |
| 1.0 | 2026-03-25 | AA | 최종 확정 |

---

## 1. 개요

### 1.1 목적
요구사항이 설계, 구현, 테스트 단계까지 누락 없이 추적 가능하도록 매트릭스를 관리한다.

### 1.2 추적 범위
- 요구사항 → 설계(아키텍처/DB/API) → 구현(소스코드) → 테스트(테스트케이스)

---

## 2. 요구사항 추적 매트릭스

### 2.1 사용자 관리 (User)

| 요구사항 ID | 요구사항명 | 설계 문서 | 구현 패키지 | 테스트 ID | 상태 |
|------------|-----------|----------|-----------|----------|------|
| FR-USR-001 | 회원가입 | 아키텍처설계서 3.1 | user.application.service | TC-USR-001 | 구현완료 |
| FR-USR-002 | 로그인 | 아키텍처설계서 3.1 | user.application.service | TC-USR-002 | 구현완료 |
| FR-USR-003 | 로그아웃 | 아키텍처설계서 3.1 | user.application.service | TC-USR-003 | 구현완료 |
| FR-USR-004 | 비밀번호 재설정 | 아키텍처설계서 3.1 | user.application.service | TC-USR-004 | 구현완료 |
| FR-USR-005 | 회원정보 조회 | API명세서 USR-005 | user.adapter.in.web | TC-USR-005 | 구현완료 |
| FR-USR-006 | 회원정보 수정 | API명세서 USR-006 | user.adapter.in.web | TC-USR-006 | 구현완료 |
| FR-USR-007 | 권한 관리 | 아키텍처설계서 3.2 | common.security | TC-USR-007 | 구현완료 |

### 2.2 상품 관리 (Product)

| 요구사항 ID | 요구사항명 | 설계 문서 | 구현 패키지 | 테스트 ID | 상태 |
|------------|-----------|----------|-----------|----------|------|
| FR-PRD-001 | 상품 등록 | API명세서 PRD-001 | product.application.service | TC-PRD-001 | 구현완료 |
| FR-PRD-002 | 상품 수정 | API명세서 PRD-002 | product.application.service | TC-PRD-002 | 구현완료 |
| FR-PRD-003 | 상품 삭제 | API명세서 PRD-003 | product.application.service | TC-PRD-003 | 구현완료 |
| FR-PRD-004 | 상품 목록 조회 | API명세서 PRD-004 | product.adapter.in.web | TC-PRD-004 | 구현완료 |
| FR-PRD-005 | 상품 상세 조회 | API명세서 PRD-005 | product.adapter.in.web | TC-PRD-005 | 구현완료 |
| FR-PRD-006 | 상품 검색 | 아키텍처설계서 4.1 | product.adapter.out.search | TC-PRD-006 | 구현완료 |
| FR-PRD-007 | 이미지 관리 | API명세서 PRD-007 | product.application.service | TC-PRD-007 | 구현완료 |

### 2.3 주문 관리 (Order)

| 요구사항 ID | 요구사항명 | 설계 문서 | 구현 패키지 | 테스트 ID | 상태 |
|------------|-----------|----------|-----------|----------|------|
| FR-ORD-001 | 주문 생성 | API명세서 ORD-001 | order.application.service | TC-ORD-001 | 구현완료 |
| FR-ORD-002 | 주문 상태 변경 | 아키텍처설계서 3.3 | order.domain | TC-ORD-002 | 구현완료 |
| FR-ORD-003 | 주문 취소 | API명세서 ORD-003 | order.application.service | TC-ORD-003 | 구현완료 |
| FR-ORD-004 | 주문 환불 | API명세서 ORD-004 | order.application.service | TC-ORD-004 | 구현완료 |
| FR-ORD-005 | 주문 목록 조회 | API명세서 ORD-005 | order.adapter.in.web | TC-ORD-005 | 구현완료 |
| FR-ORD-006 | 주문 상세 조회 | API명세서 ORD-006 | order.adapter.in.web | TC-ORD-006 | 구현완료 |

### 2.4 결제 관리 (Payment)

| 요구사항 ID | 요구사항명 | 설계 문서 | 구현 패키지 | 테스트 ID | 상태 |
|------------|-----------|----------|-----------|----------|------|
| FR-PAY-001 | 결제 승인 | API명세서 PAY-001 | payment.application.service | TC-PAY-001 | 구현완료 |
| FR-PAY-002 | 결제 매입 | 아키텍처설계서 3.4 | payment.application.service | TC-PAY-002 | 구현완료 |
| FR-PAY-003 | 결제 취소 | API명세서 PAY-003 | payment.application.service | TC-PAY-003 | 구현완료 |
| FR-PAY-004 | 부분 환불 | API명세서 PAY-004 | payment.application.service | TC-PAY-004 | 구현완료 |
| FR-PAY-005 | 결제 내역 조회 | API명세서 PAY-005 | payment.adapter.in.web | TC-PAY-005 | 구현완료 |
| FR-PAY-006 | 멱등성 보장 | 아키텍처설계서 3.4 | payment.application.service | TC-PAY-006 | 구현완료 |
| FR-PAY-007 | 동시성 제어 | 아키텍처설계서 3.4 | payment.adapter.out.persistence | TC-PAY-007 | 구현완료 |

### 2.5 정산 관리 (Settlement)

| 요구사항 ID | 요구사항명 | 설계 문서 | 구현 패키지 | 테스트 ID | 상태 |
|------------|-----------|----------|-----------|----------|------|
| FR-STL-001 | 정산 자동 생성 | 아키텍처설계서 3.5 | settlement.application.service | TC-STL-001 | 구현완료 |
| FR-STL-002 | 정산 확정 | 아키텍처설계서 3.5 | settlement.application.service | TC-STL-002 | 구현완료 |
| FR-STL-003 | 정산 조정 확정 | 아키텍처설계서 3.5 | settlement.application.service | TC-STL-003 | 구현완료 |
| FR-STL-004 | 정산 내역 조회 | API명세서 STL-004 | settlement.adapter.in.web | TC-STL-004 | 구현완료 |
| FR-STL-005 | 정산 검색 | 아키텍처설계서 4.2 | settlement.adapter.out.search | TC-STL-005 | 구현완료 |
| FR-STL-006 | 정산 리포트 | API명세서 STL-006 | settlement.adapter.in.web | TC-STL-006 | 미착수 |
| FR-STL-007 | 배치 중복 방지 | 아키텍처설계서 3.5 | common.batch | TC-STL-007 | 구현완료 |

### 2.6 카테고리 (Category)

| 요구사항 ID | 요구사항명 | 설계 문서 | 구현 패키지 | 테스트 ID | 상태 |
|------------|-----------|----------|-----------|----------|------|
| FR-CAT-001 | 카테고리 등록 | API명세서 CAT-001 | category.application.service | TC-CAT-001 | 구현완료 |
| FR-CAT-002 | 카테고리 수정 | API명세서 CAT-002 | category.application.service | TC-CAT-002 | 구현완료 |
| FR-CAT-003 | 카테고리 삭제 | API명세서 CAT-003 | category.application.service | TC-CAT-003 | 구현완료 |
| FR-CAT-004 | 카테고리 트리 조회 | API명세서 CAT-004 | category.adapter.in.web | TC-CAT-004 | 구현완료 |

### 2.7 쿠폰 (Coupon)

| 요구사항 ID | 요구사항명 | 설계 문서 | 구현 패키지 | 테스트 ID | 상태 |
|------------|-----------|----------|-----------|----------|------|
| FR-CPN-001 | 쿠폰 발급 | API명세서 CPN-001 | coupon.application.service | TC-CPN-001 | 구현완료 |
| FR-CPN-002 | 쿠폰 사용 | API명세서 CPN-002 | coupon.application.service | TC-CPN-002 | 구현완료 |
| FR-CPN-003 | 쿠폰 만료 | 아키텍처설계서 3.7 | coupon.application.service | TC-CPN-003 | 구현완료 |
| FR-CPN-004 | 쿠폰 조회 | API명세서 CPN-004 | coupon.adapter.in.web | TC-CPN-004 | 구현완료 |

### 2.8 리뷰 (Review)

| 요구사항 ID | 요구사항명 | 설계 문서 | 구현 패키지 | 테스트 ID | 상태 |
|------------|-----------|----------|-----------|----------|------|
| FR-REV-001 | 리뷰 등록 | API명세서 REV-001 | review.application.service | TC-REV-001 | 구현완료 |
| FR-REV-002 | 리뷰 수정 | API명세서 REV-002 | review.application.service | TC-REV-002 | 구현완료 |
| FR-REV-003 | 리뷰 삭제 | API명세서 REV-003 | review.application.service | TC-REV-003 | 구현완료 |
| FR-REV-004 | 리뷰 조회 | API명세서 REV-004 | review.adapter.in.web | TC-REV-004 | 구현완료 |

---

## 3. 비기능 요구사항 추적

| 요구사항 ID | 요구사항명 | 검증 방법 | 담당 | 상태 |
|------------|-----------|----------|------|------|
| NFR-PRF-001 | API 응답시간 500ms | 성능 테스트 | QA | 미착수 |
| NFR-PRF-002 | 동시 사용자 1,000명 | 부하 테스트 | QA | 미착수 |
| NFR-SEC-001 | JWT 인증 | 보안 점검 | 보안담당 | 구현완료 |
| NFR-SEC-003 | OWASP 대응 | Snyk 스캔 | QA | 진행 중 |
| NFR-MNT-002 | 커버리지 70% | JaCoCo | PL | 진행 중 |

---

## 4. 추적 현황 요약

| 도메인 | 총 요구사항 | 구현완료 | 진행 중 | 미착수 | 달성률 |
|--------|-----------|---------|--------|--------|--------|
| 사용자 | 7 | 7 | 0 | 0 | 100% |
| 상품 | 7 | 7 | 0 | 0 | 100% |
| 주문 | 6 | 6 | 0 | 0 | 100% |
| 결제 | 7 | 7 | 0 | 0 | 100% |
| 정산 | 7 | 6 | 0 | 1 | 86% |
| 카테고리 | 4 | 4 | 0 | 0 | 100% |
| 쿠폰 | 4 | 4 | 0 | 0 | 100% |
| 리뷰 | 4 | 4 | 0 | 0 | 100% |
| **합계** | **46** | **45** | **0** | **1** | **98%** |

---

> **본 문서는 Lemuel 전자상거래 주문·결제·정산 통합 시스템의 요구사항 추적 매트릭스입니다.**
