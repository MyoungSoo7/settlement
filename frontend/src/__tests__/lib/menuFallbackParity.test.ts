import { describe, it, expect } from 'vitest';
import { resolveFallbackMenus } from '@/data/menuFallback';

/**
 * P-1 회귀 고정 — 트리 기반 네비게이션이 <b>이관 전 하드코딩 배열과 정확히 같은 것</b>을 그리는지.
 *
 * <p>아래 기대값은 삭제된 `Layout`/`SettlementLayout`/`CeoLayout`/`SystemLayout` 의 항목
 * 배열을 그대로 옮긴 것이다. 이 단계의 성공 기준은 "새 기능 0, 화면 변화 0" 이므로,
 * 이 파일이 깨진다는 것은 이관 과정에서 메뉴가 늘거나 줄었다는 뜻이다.
 */

const labelsOf = (role: string) => resolveFallbackMenus(role).map((m) => m.label);
const childrenOf = (role: string, groupName: string) =>
  resolveFallbackMenus(role).find((m) => m.name === groupName)?.children.map((c) => c.name) ?? [];

describe('상단 네비 — 역할별 항목·순서 고정', () => {
  it('ADMIN: 대시보드·정산·정산운영·배송·승인·AI 도우미·CEO·시스템', () => {
    expect(labelsOf('ADMIN')).toEqual([
      '대시보드', '정산', '정산운영', '배송', '승인', 'AI 도우미', 'CEO', '시스템',
    ]);
  });

  it('MANAGER: ADMIN 목록에서 시스템만 빠진다', () => {
    expect(labelsOf('MANAGER')).toEqual([
      '대시보드', '정산', '정산운영', '배송', '승인', 'AI 도우미', 'CEO',
    ]);
  });

  it('USER: 주문하기·추천받기·내 포인트·상품권', () => {
    // 잔액 화면은 결제 직전에 "얼마까지 낼 수 있나"를 확인하러 오는 경로다.
    // 대량주문은 관리자 기능이 아니라 구매자가 자기 주문을 올리는 경로다 — SHOP 최상위.
    expect(labelsOf('USER')).toEqual(['주문하기', '추천받기', '대량주문', '내 포인트·상품권']);
  });

  it('미인증: 아무 것도 없다', () => {
    expect(labelsOf('')).toEqual([]);
    expect(resolveFallbackMenus(null)).toEqual([]);
  });
});

describe('사이드바 — 그룹별 항목이 이관 전과 같다', () => {
  it('정산 (ADMIN): 상품관리·정산관리·정산조회·지급관리', () => {
    expect(childrenOf('ADMIN', '정산')).toEqual([
      '상품관리', '정산관리', '정산조회', '지급관리',
    ]);
  });

  it('정산 (MANAGER): 지급관리는 빠진다 — 서버가 ADMIN 으로 막는 실자금 경로', () => {
    expect(childrenOf('MANAGER', '정산')).toEqual(['상품관리', '정산관리', '정산조회']);
  });

  it('정산운영: 정산 그룹과 섞이지 않고 운영 화면만 담는다', () => {
    // "얼마 팔렸나(매출) → 깨졌나(정합성) → 어디가(대사) → 무슨 분개(원장)" 순으로 읽히게 배치한다.
    // 매출 통계는 읽기 전용 집계라 정합성 바로 뒤에 둔다 — 현황을 먼저 보고 검증으로 넘어간다.
    // 셀러 등급은 수수료율 바로 뒤다 — 등급(입력) → 요율(적용) 순으로 읽힌다.
    // 예치금 운영은 회수 채권 바로 뒤다 — 둘 다 셀러에게서 재원을 끌어오는 축이고,
    // 상계 부족분이 회수 채권과 같은 종류의 미결 잔여물이라 나란히 읽힌다.
    // 환불·차지백·회수 채권은 "나간 돈을 되돌리는" 축이라 붙어 있다.
    expect(childrenOf('ADMIN', '정산운영')).toEqual([
      '정합성 검증', '매출 통계', '일일 대사', 'PG 대사', '차지백', '회수 채권', '환불 운영',
      '예치금 운영', '월마감', '세무', '수수료율', '셀러 등급', 'DLQ 재처리', '원장·시산표',
    ]);
    // 차지백·월마감이 빠진다 — 서버가 두 표면을 ADMIN 전용으로 막는다(결정·확정 경로).
    // 원장 조회는 MANAGER 도 되는 표면이라 메뉴에 남기고, 시산표만 화면 안에서 가린다.
    // 세무는 MANAGER 에게도 열린다 — 서버가 /admin/tax/** 를 ADMIN·MANAGER 로 막는다(스캔 리뷰가 MANAGER 몫).
    // 매출 통계도 MANAGER 에게 열린다 — 서버가 /api/reports/** 를 ADMIN·MANAGER 로 막는다.
    // 환불 운영은 MANAGER 에게도 열린다 — 서버가 /admin/refunds/** 를 ADMIN·MANAGER 로 막는다.
    // 예치금은 ADMIN 전용이라 여기서 빠진다(잔고를 움직이는 표면).
    expect(childrenOf('MANAGER', '정산운영')).toEqual([
      '정합성 검증', '매출 통계', '일일 대사', 'PG 대사', '회수 채권', '환불 운영',
      '세무', '원장·시산표',
    ]);
  });

  it('CEO: 16개 항목이 순서대로', () => {
    // 담보 감시는 대출관리 바로 뒤 — 같은 서비스의 다른 상품군(담보대출)이고,
    // 대출을 보러 온 자리에서 담보 상태로 이어지는 순서다.
    // 수신 상품은 계정계 현황 뒤 — 집계를 본 다음 그 집계를 만드는 개별 계약으로 내려간다.
    expect(childrenOf('ADMIN', 'CEO')).toEqual([
      '통합 브리핑', '경제지표', '재무제표', '기업조회', '사업장비교',
      '투자하기', '투자 추천', '대출관리', '담보 감시', '대출 상품 안내', '대출 심사·상환 안내',
      '대출기관 안내', '자산운용펀드 안내', '계정계 현황', '수신 상품', '법인카드',
    ]);
  });

  it('시스템 (ADMIN): 18개 항목', () => {
    // 카탈로그 3종이 붙어 있다 — 무엇으로 묶이나(분류) → 언제 앞에 세우나(진열) → 무엇으로 고르나(옵션)
    // 마지막은 사후 판정 콘솔 — 관제(운영관리) 다음의 증빙 리뷰 큐(ADR 0036)
    // 게시판 관리는 board-service(8114) 콘솔 — 게시판 하나가 곧 화면 하나라 시스템 관리에 둔다
    expect(childrenOf('ADMIN', '시스템 관리')).toEqual([
      '메뉴 관리', '공통코드 관리', 'RBAC 관리', '이커머스 카테고리', '진열 편성', '옵션 카탈로그', '운영관리',
      '증빙 리뷰 큐', '게시판 관리', '교육 관리',
      // 상품설명서 교부는 리뷰 큐 옆이 아니라 교육 뒤다 — 리뷰 큐는 올라온 서류를 '판정'하고,
      // 이쪽은 문서를 '발급'한다. 성격이 달라 섞으면 무엇을 누르는지 헷갈린다.
      '상품설명서 교부',
      // 내부잔액 원장 2종의 운영 콘솔 — 수기 지급·발행은 없던 재산을 만들고 소멸은 지운다.
      '포인트 운영', '기프트카드 운영',
      // 운영 콘솔 4종 — 그동안 백엔드만 있고 화면이 없던(또는 조작 경로 자체가 DB 였던) 축이다.
      // 감사 로그는 적재만 하고 아무도 못 보던 표면, 회원 관리는 승인 API 는 있는데 대상을 못 찾던
      // 표면, 리뷰·쿠폰은 내리고 멈추는 방법이 DB 직접 수정뿐이던 표면.
      // 조직·멤버십은 회원 관리 바로 뒤 — 개인(회원)을 본 다음 그 사람이 속한 조직으로 이어진다.
      '감사 로그', '회원 관리', '조직 · 멤버십', '리뷰 관리', '쿠폰 운영',
    ]);
  });
});

describe('사이드바 머리글', () => {
  it('시스템은 상단 네비 라벨과 사이드바 제목이 다르다 (시스템 / 시스템 관리)', () => {
    const system = resolveFallbackMenus('ADMIN').find((m) => m.name === '시스템 관리');

    expect(system?.label).toBe('시스템');
    expect(system?.name).toBe('시스템 관리');
    expect(system?.description).toBe('System Administration');
  });

  it('정산·CEO 의 영문 부제가 보존된다', () => {
    const menus = resolveFallbackMenus('ADMIN');
    expect(menus.find((m) => m.name === '정산')?.description).toBe('Settlement');
    expect(menus.find((m) => m.name === 'CEO')?.description).toBe('Executive Insights');
  });
});
