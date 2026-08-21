import type { MenuArea, MenuNodeType, NavMenuNode } from '@/api/menu';

/**
 * 메뉴 트리 빌드타임 스냅샷 — `GET /api/menus/me` 가 실패했을 때만 쓰는 폴백이다.
 *
 * <p><b>왜 필요한가</b>: 네비게이션을 서버 응답으로 그리게 되면, 그 호출 하나가 실패할 때
 * 앱 전체가 "링크 없는 화면"이 된다. 사용자는 로그인은 됐는데 아무 데도 못 가는 상태가 된다.
 * 그래서 서버가 답하지 못할 때 최소한 이동은 가능하도록 마지막으로 알려진 트리를 들고 있는다.
 *
 * <p><b>정본은 여기가 아니다</b>: 정본은
 * `order-service/src/main/resources/db/migration/V20260813100000__menu_area_permission.sql` 이고,
 * 이 파일은 그 시드의 사본이다. 둘이 어긋나면
 * `scripts/harness/test/menu-route-gate.test.mjs` 가 CI 에서 빌드를 깬다.
 *
 * <p><b>서버 필터링과의 차이</b>: 서버는 역할 + 권한(permission)으로 거른다. 폴백은 역할까지만
 * 본다 — 권한을 가진 역할은 현재 ADMIN 뿐이고 ADMIN 은 전 권한 보유자라, 역할만으로 같은
 * 결과가 나온다. 어차피 폴백은 "서버가 죽었을 때의 근사치"이고, 실제 인가는 각 API 가 한다.
 */
export interface FallbackMenuNode {
  id: number;
  name: string;
  shortName?: string;
  path: string | null;
  icon: string | null;
  description: string | null;
  area: MenuArea;
  type: MenuNodeType;
  /** 접근 허용 역할. null 이면 제한 없음 */
  roles: string[] | null;
  children?: FallbackMenuNode[];
}

export const FALLBACK_MENUS: FallbackMenuNode[] = [
  {
    id: -1, name: '대시보드', path: '/admin', icon: '📊', description: null,
    area: 'BACKOFFICE', type: 'ITEM', roles: ['ADMIN', 'MANAGER'],
  },
  {
    id: -2, name: '정산', path: '/admin/settlement', icon: '💰', description: 'Settlement',
    area: 'BACKOFFICE', type: 'GROUP', roles: ['ADMIN', 'MANAGER'],
    children: [
      { id: -21, name: '상품관리', path: '/product', icon: '📦', description: '상품 · 재고 관리', area: 'BACKOFFICE', type: 'ITEM', roles: ['ADMIN', 'MANAGER'] },
      { id: -22, name: '정산관리', path: '/admin/settlement', icon: '💰', description: '정산 생성 · 확정', area: 'BACKOFFICE', type: 'ITEM', roles: ['ADMIN', 'MANAGER'] },
      { id: -23, name: '정산조회', path: '/settlement/search', icon: '🔍', description: '정산 내역 조회', area: 'BACKOFFICE', type: 'ITEM', roles: ['ADMIN', 'MANAGER'] },
      { id: -24, name: '지급관리', path: '/admin/settlement/payouts', icon: '🏦', description: '송금 실행 · 실패 처리', area: 'BACKOFFICE', type: 'ITEM', roles: ['ADMIN'] },
    ],
  },
  {
    id: -10, name: '정산운영', path: '/admin/settlement/integrity', icon: '🧪',
    description: 'Settlement Operations', area: 'BACKOFFICE', type: 'GROUP', roles: ['ADMIN', 'MANAGER'],
    children: [
      { id: -101, name: '정합성 검증', path: '/admin/settlement/integrity', icon: '🧪', description: '원장·지급·홀드백·체류 8종', area: 'BACKOFFICE', type: 'ITEM', roles: ['ADMIN', 'MANAGER'] },
      { id: -111, name: '매출 통계', path: '/admin/settlement/sales-stats', icon: '📊', description: '기간 매출 · 전기 대비 · 결제수단/셀러/상품 구성', area: 'BACKOFFICE', type: 'ITEM', roles: ['ADMIN', 'MANAGER'] },
      { id: -103, name: '일일 대사', path: '/admin/settlement/reconciliation', icon: '🔀', description: 'order ↔ settlement 이중장부', area: 'BACKOFFICE', type: 'ITEM', roles: ['ADMIN', 'MANAGER'] },
      { id: -104, name: 'PG 대사', path: '/admin/settlement/pg-reconciliation', icon: '🧾', description: 'PG 정산파일 업로드 · 차이 승인 · 마감', area: 'BACKOFFICE', type: 'ITEM', roles: ['ADMIN', 'MANAGER'] },
      { id: -105, name: '차지백', path: '/admin/settlement/chargebacks', icon: '⚖️', description: '카드사 분쟁 수락 · 기각', area: 'BACKOFFICE', type: 'ITEM', roles: ['ADMIN'] },
      { id: -106, name: '회수 채권', path: '/admin/settlement/recoveries', icon: '🧲', description: '셀러별 미상계 잔액 · 상계 이력', area: 'BACKOFFICE', type: 'ITEM', roles: ['ADMIN', 'MANAGER'] },
      // 예치금은 회수 채권 바로 뒤다 — 둘 다 셀러에게서 재원을 끌어오는 축이고, 상계 부족분이
      // 회수 채권과 같은 종류의 미결 잔여물이라 나란히 읽힌다.
      { id: -113, name: '예치금 운영', path: '/admin/settlement/deposits', icon: '🏧', description: '수기 입출금 · 선점 · 상계 · 부족분 해소', area: 'BACKOFFICE', type: 'ITEM', roles: ['ADMIN'] },
      { id: -107, name: '월마감', path: '/admin/settlement/monthly-closing', icon: '📆', description: '셀러 월 정산 마트 집계 · 재실행', area: 'BACKOFFICE', type: 'ITEM', roles: ['ADMIN'] },
      { id: -108, name: '세무', path: '/admin/settlement/tax', icon: '🧾', description: '스캔 리뷰 · 전표 전기 · 세금계산서', area: 'BACKOFFICE', type: 'ITEM', roles: ['ADMIN', 'MANAGER'] },
      { id: -109, name: '수수료율', path: '/admin/settlement/commission-rates', icon: '⚖️', description: '셀러·등급 요율 정책 · 이력', area: 'BACKOFFICE', type: 'ITEM', roles: ['ADMIN'] },
      // 등급이 요율·주기·홀드백을 동시에 정하므로 '수수료율' 바로 옆이다. 서버가
      // /admin/seller-tiers/** 를 ADMIN 으로 막는다 — MANAGER 에게 보이면 죽은 링크다.
      { id: -112, name: '셀러 등급', path: '/admin/settlement/seller-tiers', icon: '🏅', description: '등급 재산정 · 관리자 지정 · 캐시 정합', area: 'BACKOFFICE', type: 'ITEM', roles: ['ADMIN'] },
      { id: -110, name: 'DLQ 재처리', path: '/admin/settlement/dlq', icon: '📮', description: '처리 실패 이벤트 확인 · 재발행', area: 'BACKOFFICE', type: 'ITEM', roles: ['ADMIN'] },
      { id: -102, name: '원장·시산표', path: '/admin/settlement/ledger', icon: '📒', description: '분개 조회 · 월 시산표 · 기간 마감', area: 'BACKOFFICE', type: 'ITEM', roles: ['ADMIN', 'MANAGER'] },
    ],
  },
  {
    id: -3, name: '배송', path: '/admin/shipping', icon: '🚚', description: null,
    area: 'BACKOFFICE', type: 'GROUP', roles: ['ADMIN', 'MANAGER'],
    children: [
      { id: -31, name: '배송 관리', path: '/admin/shipping', icon: '🚚', description: '주문별 배송 생성 · 출고 · 상태 전이', area: 'BACKOFFICE', type: 'ITEM', roles: ['ADMIN', 'MANAGER'] },
      // 서버가 /admin/shipping-policies/** 를 ADMIN 으로 막는다 — MANAGER 에게 보이면 죽은 링크다.
      { id: -32, name: '배송비 정책', path: '/admin/shipping/policies', icon: '💵', description: '셀러 기본배송비 · 무료배송 임계', area: 'BACKOFFICE', type: 'ITEM', roles: ['ADMIN'] },
    ],
  },
  {
    id: -4, name: '승인', path: '/admin/approvals', icon: '✅', description: null,
    area: 'BACKOFFICE', type: 'ITEM', roles: ['ADMIN', 'MANAGER'],
  },
  {
    id: -5, name: 'AI 도우미', path: '/ai/chat', icon: '🤖', description: null,
    area: 'BACKOFFICE', type: 'ITEM', roles: ['ADMIN', 'MANAGER'],
  },
  {
    id: -6, name: 'CEO', path: '/admin/ceo/insight', icon: '👔', description: 'Executive Insights',
    area: 'CEO', type: 'GROUP', roles: ['ADMIN', 'MANAGER'],
    children: [
      { id: -61, name: '통합 브리핑', path: '/admin/ceo/insight', icon: 'CEO', description: '재무·평판·경제 통합', area: 'CEO', type: 'ITEM', roles: ['ADMIN', 'MANAGER'] },
      { id: -62, name: '경제지표', path: '/admin/ceo/economics', icon: '📈', description: '한국은행 ECOS 지표', area: 'CEO', type: 'ITEM', roles: ['ADMIN', 'MANAGER'] },
      { id: -63, name: '재무제표', path: '/admin/ceo/financials', icon: '📊', description: '코스피 상장사 재무제표', area: 'CEO', type: 'ITEM', roles: ['ADMIN', 'MANAGER'] },
      { id: -64, name: '기업조회', path: '/admin/ceo/companies', icon: '📰', description: '기업 뉴스 · 평판', area: 'CEO', type: 'ITEM', roles: ['ADMIN', 'MANAGER'] },
      { id: -65, name: '사업장비교', path: '/admin/ceo/workforce', icon: '🏢', description: '국민연금 인원 · 연봉', area: 'CEO', type: 'ITEM', roles: ['ADMIN', 'MANAGER'] },
      { id: -66, name: '투자하기', path: '/admin/ceo/invest', icon: '💹', description: '재무점수 기반 투자', area: 'CEO', type: 'ITEM', roles: ['ADMIN', 'MANAGER'] },
      { id: -67, name: '투자 추천', path: '/admin/ceo/invest-recommend', icon: '🧭', description: '규칙 스크리닝 추천 종목', area: 'CEO', type: 'ITEM', roles: ['ADMIN', 'MANAGER'] },
      { id: -68, name: '대출관리', path: '/admin/ceo/loans', icon: '💸', description: '선정산 · 기업대출', area: 'CEO', type: 'ITEM', roles: ['ADMIN', 'MANAGER'] },
      { id: -69, name: '대출 상품 안내', path: '/admin/ceo/loan-guide', icon: '📖', description: '개인신용 · 주택담보', area: 'CEO', type: 'ITEM', roles: ['ADMIN', 'MANAGER'] },
      { id: -70, name: '대출 심사·상환 안내', path: '/admin/ceo/loan-process', icon: '🧾', description: '심사 · 신용평가 · 상환', area: 'CEO', type: 'ITEM', roles: ['ADMIN', 'MANAGER'] },
      { id: -71, name: '대출기관 안내', path: '/admin/ceo/lender-guide', icon: '🏦', description: '은행 · 저축은행 · 캐피탈 · 대부업', area: 'CEO', type: 'ITEM', roles: ['ADMIN', 'MANAGER'] },
      { id: -72, name: '자산운용펀드 안내', path: '/admin/ceo/fund-guide', icon: '🧺', description: '부동산 · 주식 · 채권 펀드', area: 'CEO', type: 'ITEM', roles: ['ADMIN', 'MANAGER'] },
      { id: -73, name: '계정계 현황', path: '/admin/ceo/accounts', icon: '🧮', description: '집계 · 시산표 · 잔액', area: 'CEO', type: 'ITEM', roles: ['ADMIN', 'MANAGER'] },
      { id: -74, name: '법인카드', path: '/admin/ceo/cards', icon: '💳', description: '카드계정 · 임직원 카드 한도', area: 'CEO', type: 'ITEM', roles: ['ADMIN', 'MANAGER'] },
    ],
  },
  {
    id: -7, name: '시스템 관리', shortName: '시스템', path: '/admin/system/menus', icon: '⚙️',
    description: 'System Administration', area: 'SYSTEM', type: 'GROUP', roles: ['ADMIN'],
    children: [
      { id: -81, name: '메뉴 관리', path: '/admin/system/menus', icon: '🗂️', description: '네비게이션 메뉴 트리', area: 'SYSTEM', type: 'ITEM', roles: ['ADMIN'] },
      { id: -82, name: '공통코드 관리', path: '/admin/system/codes', icon: '🏷️', description: '코드 그룹 / 항목', area: 'SYSTEM', type: 'ITEM', roles: ['ADMIN'] },
      { id: -83, name: 'RBAC 관리', path: '/admin/system/rbac', icon: '🔐', description: '역할 · 권한 매트릭스', area: 'SYSTEM', type: 'ITEM', roles: ['ADMIN'] },
      { id: -84, name: '이커머스 카테고리', path: '/admin/system/ecommerce-categories', icon: '📁', description: '상품 카테고리 트리', area: 'SYSTEM', type: 'ITEM', roles: ['ADMIN'] },
      { id: -86, name: '진열 편성', path: '/admin/system/display-sections', icon: '🎪', description: '기획전 · 메인 진열 편성', area: 'SYSTEM', type: 'ITEM', roles: ['ADMIN'] },
      { id: -87, name: '옵션 카탈로그', path: '/admin/system/option-catalog', icon: '🎛️', description: '표준 옵션 축 · 값 관리', area: 'SYSTEM', type: 'ITEM', roles: ['ADMIN'] },
      { id: -85, name: '운영관리', path: '/admin/system/operation', icon: '🖥️', description: '인시던트 관제 콘솔', area: 'SYSTEM', type: 'ITEM', roles: ['ADMIN'] },
      { id: -88, name: '증빙 리뷰 큐', path: '/admin/system/proof-review', icon: '🧾', description: '증빙 OCR 리뷰 큐 (영수증·청약·담보·예치금)', area: 'SYSTEM', type: 'ITEM', roles: ['ADMIN'] },
      { id: -89, name: '게시판 관리', path: '/admin/system/boards', icon: '📋', description: '게시판 생성 · 스킨 · 권한 정책', area: 'SYSTEM', type: 'ITEM', roles: ['ADMIN'] },
      { id: -90, name: '교육 관리', path: '/admin/system/education', icon: '🎓', description: '교육 과정 · 강의 콘텐츠', area: 'SYSTEM', type: 'ITEM', roles: ['ADMIN'] },
      // 증빙 리뷰 큐 바로 뒤가 아니라 교육 뒤에 둔다 — 리뷰 큐는 '판정', 이쪽은 '발급'이라 성격이 다르다.
      { id: -114, name: '상품설명서 교부', path: '/admin/system/insurance-disclosures', icon: '📜', description: '보험 상품설명서 미리보기 · 교부 증빙', area: 'SYSTEM', type: 'ITEM', roles: ['ADMIN'] },
      { id: -91, name: '포인트 운영', path: '/admin/system/points', icon: '🪙', description: '수기 지급 · 유효기간 소멸', area: 'SYSTEM', type: 'ITEM', roles: ['ADMIN'] },
      { id: -92, name: '기프트카드 운영', path: '/admin/system/gift-cards', icon: '🎁', description: '상품권 발행 · 유효기간 소멸', area: 'SYSTEM', type: 'ITEM', roles: ['ADMIN'] },
      { id: -93, name: '감사 로그', path: '/admin/system/audit-logs', icon: '🔎', description: '조작 이력 조회 (커머스 · 정산)', area: 'SYSTEM', type: 'ITEM', roles: ['ADMIN'] },
      { id: -94, name: '회원 관리', path: '/admin/system/members', icon: '👤', description: '회원 검색 · 승인 · 역할 변경', area: 'SYSTEM', type: 'ITEM', roles: ['ADMIN'] },
      { id: -95, name: '리뷰 관리', path: '/admin/system/reviews', icon: '⭐', description: '신고 리뷰 블라인드 · 해제', area: 'SYSTEM', type: 'ITEM', roles: ['ADMIN'] },
      { id: -96, name: '쿠폰 운영', path: '/admin/system/coupons', icon: '🎟️', description: '쿠폰 검색 · 중단/재개 · 사용 내역', area: 'SYSTEM', type: 'ITEM', roles: ['ADMIN'] },
    ],
  },
  {
    id: -8, name: '주문하기', path: '/order', icon: '🛒', description: null,
    area: 'SHOP', type: 'ITEM', roles: ['USER'],
  },
  {
    id: -9, name: '추천받기', path: '/recommend', icon: '✨', description: null,
    area: 'SHOP', type: 'ITEM', roles: ['USER'],
  },
  {
    // 대량주문 — 관리자 기능이 아니라 구매자가 자기 주문을 올리는 경로다(초안은 올린 사람만 본다).
    id: -11, name: '대량주문', path: '/order/bulk', icon: '📦', description: 'CSV 업로드 → 검증 → 실주문 전환',
    area: 'SHOP', type: 'ITEM', roles: ['USER'],
  },
  {
    id: -10, name: '내 포인트·상품권', path: '/my/balances', icon: '🪙', description: null,
    area: 'SHOP', type: 'ITEM', roles: ['USER'],
  },
];

const accessible = (node: FallbackMenuNode, role: string | null): boolean =>
  node.roles === null || (role !== null && node.roles.includes(role.toUpperCase()));

const toNavNode = (node: FallbackMenuNode, children: NavMenuNode[]): NavMenuNode => ({
  id: node.id,
  name: node.name,
  label: node.shortName ?? node.name,
  path: node.path,
  icon: node.icon,
  description: node.description,
  area: node.area,
  type: node.type,
  children,
});

/**
 * 폴백 트리를 역할로 걸러 서버 응답과 같은 모양으로 만든다.
 * 서버와 같은 규칙으로 자식이 전부 사라진 묶음(GROUP)은 걷어낸다.
 */
export const resolveFallbackMenus = (role: string | null): NavMenuNode[] =>
  FALLBACK_MENUS.filter((node) => accessible(node, role))
    .map((node) => toNavNode(node, (node.children ?? []).filter((c) => accessible(c, role)).map((c) => toNavNode(c, []))))
    .filter((node) => node.type !== 'GROUP' || node.children.length > 0);
