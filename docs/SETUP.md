# Lemuel Frontend 설치 및 실행 가이드

## 📦 설치

### 1. 의존성 설치

```bash
cd frontend
npm install
```

### 2. 환경 변수 설정

`.env` 파일 생성:

```bash
VITE_API_BASE_URL=http://localhost:8080
```

## 🚀 실행

### 개발 서버 실행

```bash
npm run dev
```

기본 포트: `http://localhost:5173` (Vite 기본 포트)

### 프로덕션 빌드

```bash
npm run build
npm run preview
```

## 📄 페이지 구성

### 1. 로그인 페이지 (`/login`)
- JWT 기반 인증
- 이메일/비밀번호 입력
- 자동 토큰 관리

### 2. 주문/결제 페이지 (`/order`) - 사용자용
- 주문 생성
- 결제 수단 선택 (신용카드, 계좌이체, 가상계좌)
- 결제 프로세스 (생성 → 승인 → 확정)
- 실시간 상태 업데이트

**시나리오**:
1. 금액 입력 및 결제 수단 선택
2. "주문하기" → 주문 생성 (OrderController)
3. "결제 진행하기" → 결제 생성 (PaymentController)
4. "결제하기" → 결제 승인 → 자동 확정

### 3. 정산 현황 대시보드 (`/dashboard`) - 사용자/관리자
- 정산 내역 검색 (주문자명, 상품명, 기간, 상태, 환불 여부)
- 실시간 집계 (총 정산액, 환불액, 최종액)
- 페이지네이션
- 상세 필터링

### 4. 정산 관리 페이지 (`/admin`) - 관리자 전용
- 정산 승인/반려 기능
- 상태별 필터링 (계산완료, 승인대기, 승인됨, 반려됨)
- 상세 정보 모달
- 반려 사유 입력

**승인 프로세스**:
1. 배치 Job이 정산 데이터 생성 (`WAITING_APPROVAL` 상태)
2. 관리자가 정산 내역 확인
3. "승인" 버튼 → 정산 승인 (`APPROVED` 상태)
4. "반려" 버튼 → 반려 사유 입력 후 반려 (`REJECTED` 상태)

## 🎨 디자인 시스템

### 컬러 팔레트
- **Primary**: 블루 계열 (`blue-50` ~ `blue-700`) - 신뢰감, 안정성
- **Success**: 그린 계열 (`green-600`) - 승인, 완료
- **Warning**: 옐로우 계열 (`yellow-100`) - 대기, 주의
- **Danger**: 레드 계열 (`red-600`) - 반려, 에러

### 상태 뱃지
- `CALCULATED` - 노란색 (계산완료)
- `WAITING_APPROVAL` - 파란색 (승인대기)
- `APPROVED` - 초록색 (승인됨)
- `REJECTED` - 빨간색 (반려됨)

### UI 컴포넌트
- **Spinner**: 로딩 상태 표시
- **Card**: 콘텐츠 카드 레이아웃
- **Layout**: 네비게이션 바 + 푸터

## 🔐 권한 관리

### Protected Route
- 인증되지 않은 사용자 → 로그인 페이지로 리다이렉트

### Admin Route
- 일반 사용자가 `/admin` 접근 시 → `/dashboard`로 리다이렉트
- `role === 'ADMIN'`인 사용자만 접근 가능

## 🌐 API 연동

### JWT 자동 관리
모든 API 요청에 자동으로 JWT 토큰이 포함됩니다:

```typescript
// src/api/axios.ts
api.interceptors.request.use((config) => {
  const token = localStorage.getItem('access_token');
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});
```

### 401 에러 처리
토큰 만료 시 자동 로그아웃:

```typescript
api.interceptors.response.use(
  (response) => response,
  (error) => {
    if (error.response?.status === 401) {
      localStorage.clear();
      window.location.href = '/login';
    }
    return Promise.reject(error);
  }
);
```

## 📡 API 엔드포인트

### 주문
- `POST /orders` - 주문 생성

### 결제
- `POST /payments` - 결제 생성
- `PATCH /payments/{id}/authorize` - 결제 승인
- `PATCH /payments/{id}/capture` - 결제 확정

### 정산
- `GET /api/settlements/search` - 정산 검색
- `GET /api/settlements/{id}` - 정산 상세
- `POST /api/settlements/{id}/approve` - 정산 승인
- `POST /api/settlements/{id}/reject` - 정산 반려

## 🐛 트러블슈팅

### CORS 에러
백엔드 `SecurityConfig.java`에서 CORS 설정 확인:
```java
configuration.setAllowedOrigins(Arrays.asList(
    "http://localhost:5173"  // Vite 포트
));
```

### 로컬 스토리지 초기화
브라우저 콘솔에서:
```javascript
localStorage.clear();
```

### 개발 서버 포트 변경
`vite.config.ts`:
```typescript
server: {
  port: 3000, // 원하는 포트로 변경
}
```

## 📂 프로젝트 구조

```
frontend/
├── src/
│   ├── api/                    # API 서비스 레이어
│   │   ├── axios.ts           # Axios 인스턴스 + JWT 인터셉터
│   │   ├── auth.ts            # 인증 API
│   │   ├── order.ts           # 주문 API
│   │   ├── paymentDomain.ts         # 결제 API
│   │   ├── refund.ts          # 환불 API
│   │   └── settlement.ts      # 정산 API
│   ├── components/            # 재사용 컴포넌트
│   │   ├── Card.tsx           # 카드 레이아웃
│   │   ├── Layout.tsx         # 페이지 레이아웃
│   │   └── Spinner.tsx        # 로딩 스피너
│   ├── pages/                 # 페이지 컴포넌트
│   │   ├── Login.tsx          # 로그인
│   │   ├── OrderPage.tsx      # 주문/결제
│   │   ├── SettlementDashboard.tsx  # 정산 조회
│   │   └── SettlementAdmin.tsx      # 정산 관리 (관리자)
│   ├── types/                 # TypeScript 타입 정의
│   │   └── index.ts
│   ├── App.tsx                # 메인 앱 (라우팅)
│   ├── main.tsx               # 엔트리 포인트
│   └── index.css              # 글로벌 스타일
├── package.json
├── vite.config.ts
├── tailwind.config.js
└── tsconfig.json
```

## 🎯 테스트 시나리오

### 1. 주문 & 결제 플로우
1. 로그인 (`test@example.com` / `password`)
2. `/order` 페이지 이동
3. 금액 입력 (예: 50000원)
4. 결제 수단 선택
5. 주문 생성 → 결제 생성 → 결제 완료 확인

### 2. 정산 조회
1. `/dashboard` 페이지 이동
2. 필터 설정 (기간, 상태, 주문자명 등)
3. 검색 결과 확인
4. 페이지네이션 테스트

### 3. 정산 승인 (관리자)
1. ADMIN 계정 로그인
2. `/admin` 페이지 이동
3. `WAITING_APPROVAL` 상태 정산 건 확인
4. "상세보기" 클릭 → 정보 확인
5. "승인" 또는 "반려" 처리

## 🔧 개발 팁

### Hot Module Replacement (HMR)
Vite는 자동으로 HMR을 지원합니다. 파일 저장 시 즉시 반영됩니다.

### TypeScript 타입 체크
```bash
npm run build  # 빌드 시 자동으로 타입 체크
```

### Tailwind CSS IntelliSense
VS Code에서 Tailwind CSS IntelliSense 확장 설치 권장:
```
bradlc.vscode-tailwindcss
```

## 📚 참고 자료
- [React Documentation](https://react.dev/)
- [Vite Documentation](https://vitejs.dev/)
- [Tailwind CSS](https://tailwindcss.com/)
- [React Router](https://reactrouter.com/)
- [Axios](https://axios-http.com/)
