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
      { id: -24, name: '지급관리', path: '/admin/payouts', icon: '🏦', description: '송금 실행 · 실패 처리', area: 'BACKOFFICE', type: 'ITEM', roles: ['ADMIN'] },
    ],
  },
  {
    id: -3, name: '배송', path: '/admin/shipping', icon: '🚚', description: null,
    area: 'BACKOFFICE', type: 'ITEM', roles: ['ADMIN', 'MANAGER'],
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
      { id: -85, name: '운영관리', path: '/admin/system/operation', icon: '🖥️', description: '인시던트 관제 콘솔', area: 'SYSTEM', type: 'ITEM', roles: ['ADMIN'] },
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
