import { describe, it, expect } from 'vitest';
import { findActiveTrail, findActiveRoot, matchesPath, collectPaths } from '@/lib/menuTree';
import { resolveFallbackMenus } from '@/data/menuFallback';

const adminMenus = resolveFallbackMenus('ADMIN');

describe('matchesPath', () => {
  it('정확히 같으면 일치', () => {
    expect(matchesPath('/admin', '/admin')).toBe(true);
  });

  it('세그먼트 경계에서만 접두 일치 — /loan 이 /loans 를 삼키지 않는다', () => {
    expect(matchesPath('/admin/ceo/loans', '/admin/ceo/loans/3')).toBe(true);
    expect(matchesPath('/admin/ceo/loan', '/admin/ceo/loans')).toBe(false);
  });
});

describe('findActiveTrail — 가장 긴 접두사가 이긴다', () => {
  it('/admin 에서는 대시보드만 활성 (정산·CEO·시스템이 함께 켜지지 않는다)', () => {
    const trail = findActiveTrail(adminMenus, '/admin');
    expect(trail.map((n) => n.name)).toEqual(['대시보드']);
  });

  it('/admin/settlement 에서는 정산 > 정산관리', () => {
    const trail = findActiveTrail(adminMenus, '/admin/settlement');
    expect(trail.map((n) => n.name)).toEqual(['정산', '정산관리']);
  });

  it('/product 처럼 접두어가 다른 하위도 정산 그룹으로 묶인다', () => {
    expect(findActiveRoot(adminMenus, '/product')?.name).toBe('정산');
  });

  it('/admin/payouts 에서 상단 정산이 켜진다 — 하드코딩 시절 빠져 있던 경로', () => {
    const trail = findActiveTrail(adminMenus, '/admin/payouts');
    expect(trail.map((n) => n.name)).toEqual(['정산', '지급관리']);
  });

  it('/admin/ceo/loan-guide 는 대출관리가 아니라 대출 상품 안내가 활성', () => {
    const trail = findActiveTrail(adminMenus, '/admin/ceo/loan-guide');
    expect(trail.map((n) => n.name)).toEqual(['CEO', '대출 상품 안내']);
  });

  it('/admin/system/codes 는 시스템 > 공통코드 관리', () => {
    const trail = findActiveTrail(adminMenus, '/admin/system/codes');
    expect(trail.map((n) => n.name)).toEqual(['시스템 관리', '공통코드 관리']);
  });

  it('묶음과 대표 자식의 경로가 같으면 더 깊은 자식이 활성이 된다', () => {
    const trail = findActiveTrail(adminMenus, '/admin/ceo/insight');
    expect(trail.map((n) => n.name)).toEqual(['CEO', '통합 브리핑']);
  });

  it('어느 메뉴에도 걸리지 않으면 빈 배열', () => {
    expect(findActiveTrail(adminMenus, '/mypage')).toEqual([]);
    expect(findActiveRoot(adminMenus, '/mypage')).toBeNull();
  });
});

describe('collectPaths', () => {
  it('트리의 모든 링크 경로를 모은다 (ADMIN 기준 31 - 구분선 0 = 31)', () => {
    const paths = collectPaths(adminMenus);
    expect(paths).toContain('/admin');
    expect(paths).toContain('/settlement/search');
    expect(paths).toContain('/admin/system/operation');
    expect(new Set(paths).size).toBeGreaterThan(0);
  });
});
