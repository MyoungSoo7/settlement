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

  it('정산운영: 정산 그룹과 섞이지 않고 운영 화면만 담는다', () => {
    // "깨졌나(정합성) → 어디가(대사) → 무슨 분개(원장)" 순으로 읽히게 배치한다
    expect(childrenOf('ADMIN', '정산운영')).toEqual([
      '정합성 검증', '일일 대사', 'PG 대사', '차지백', '회수 채권', '월마감', '세무', '수수료율', '원장·시산표',
    ]);
    // 차지백·월마감이 빠진다 — 서버가 두 표면을 ADMIN 전용으로 막는다(결정·확정 경로).
    // 원장 조회는 MANAGER 도 되는 표면이라 메뉴에 남기고, 시산표만 화면 안에서 가린다.
    // 세무는 MANAGER 에게도 열린다 — 서버가 /admin/tax/** 를 ADMIN·MANAGER 로 막는다(스캔 리뷰가 MANAGER 몫).
    expect(childrenOf('MANAGER', '정산운영')).toEqual([
      '정합성 검증', '일일 대사', 'PG 대사', '회수 채권', '세무', '원장·시산표',
    ]);
  });

  it('CEO: 13개 항목이 순서대로', () => {
    expect(childrenOf('ADMIN', 'CEO')).toEqual([
      '통합 브리핑', '경제지표', '재무제표', '기업조회', '사업장비교',
      '투자하기', '투자 추천', '대출관리', '대출 상품 안내', '대출 심사·상환 안내',
      '대출기관 안내', '자산운용펀드 안내', '계정계 현황',
    ]);
  });

  it('시스템 (ADMIN): 8개 항목', () => {
    // 카탈로그 3종이 붙어 있다 — 무엇으로 묶이나(분류) → 언제 앞에 세우나(진열) → 무엇으로 고르나(옵션)
    // 마지막은 사후 판정 콘솔 — 관제(운영관리) 다음의 증빙 리뷰 큐(ADR 0036)
    expect(childrenOf('ADMIN', '시스템 관리')).toEqual([
      '메뉴 관리', '공통코드 관리', 'RBAC 관리', '이커머스 카테고리', '진열 편성', '옵션 카탈로그', '운영관리',
      '증빙 리뷰 큐',
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
