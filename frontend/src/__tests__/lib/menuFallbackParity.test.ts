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

describe('상단 네비 — 역할별 항목이 이관 전과 같다', () => {
  it('ADMIN: 대시보드·정산·배송·승인·AI 도우미·CEO·시스템', () => {
    expect(labelsOf('ADMIN')).toEqual([
      '대시보드', '정산', '배송', '승인', 'AI 도우미', 'CEO', '시스템',
    ]);
  });

  it('MANAGER: ADMIN 목록에서 시스템만 빠진다', () => {
    expect(labelsOf('MANAGER')).toEqual([
      '대시보드', '정산', '배송', '승인', 'AI 도우미', 'CEO',
    ]);
  });

  it('USER: 주문하기·추천받기만', () => {
    expect(labelsOf('USER')).toEqual(['주문하기', '추천받기']);
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

  it('CEO: 13개 항목이 순서대로', () => {
    expect(childrenOf('ADMIN', 'CEO')).toEqual([
      '통합 브리핑', '경제지표', '재무제표', '기업조회', '사업장비교',
      '투자하기', '투자 추천', '대출관리', '대출 상품 안내', '대출 심사·상환 안내',
      '대출기관 안내', '자산운용펀드 안내', '계정계 현황',
    ]);
  });

  it('시스템 (ADMIN): 5개 항목', () => {
    expect(childrenOf('ADMIN', '시스템 관리')).toEqual([
      '메뉴 관리', '공통코드 관리', 'RBAC 관리', '이커머스 카테고리', '운영관리',
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
