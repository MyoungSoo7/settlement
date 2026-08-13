import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import SideNavLayout from '@/components/SideNavLayout';
import { MenuContext } from '@/contexts/useMenus';
import { resolveFallbackMenus } from '@/data/menuFallback';
import type { NavMenuNode } from '@/api/menu';

const renderAt = (pathname: string, menus: NavMenuNode[]) =>
  render(
    <MemoryRouter initialEntries={[pathname]}>
      <MenuContext.Provider value={{ menus, loading: false, degraded: false, refresh: async () => {} }}>
        <SideNavLayout><div>본문</div></SideNavLayout>
      </MenuContext.Provider>
    </MemoryRouter>,
  );

describe('SideNavLayout — 세 벌이던 사이드바 셸을 트리 하나로', () => {
  it('정산 경로에서는 정산 하위 4개를 그린다 (ADMIN)', () => {
    renderAt('/admin/settlement', resolveFallbackMenus('ADMIN'));

    expect(screen.getByText('정산')).toBeInTheDocument();
    expect(screen.getByText('Settlement')).toBeInTheDocument();
    ['상품관리', '정산관리', '정산조회', '지급관리'].forEach((label) => {
      expect(screen.getByText(label)).toBeInTheDocument();
    });
  });

  it('MANAGER 에게는 지급관리가 보이지 않는다', () => {
    renderAt('/admin/settlement', resolveFallbackMenus('MANAGER'));

    expect(screen.getByText('정산조회')).toBeInTheDocument();
    expect(screen.queryByText('지급관리')).not.toBeInTheDocument();
  });

  it('CEO 경로에서는 CEO 하위 13개와 머리글을 그린다', () => {
    renderAt('/admin/ceo/invest', resolveFallbackMenus('ADMIN'));

    expect(screen.getByText('Executive Insights')).toBeInTheDocument();
    expect(screen.getByText('투자하기')).toBeInTheDocument();
    expect(screen.getByText('계정계 현황')).toBeInTheDocument();
  });

  it('시스템 경로에서는 사이드바 제목이 "시스템 관리" 다', () => {
    renderAt('/admin/system/rbac', resolveFallbackMenus('ADMIN'));

    expect(screen.getByText('시스템 관리')).toBeInTheDocument();
    expect(screen.getByText('System Administration')).toBeInTheDocument();
  });

  it('현재 항목에 aria-current=page 가 하나만 붙는다', () => {
    const { container } = renderAt('/admin/payouts', resolveFallbackMenus('ADMIN'));

    const current = container.querySelectorAll('[aria-current="page"]');
    expect(current).toHaveLength(1);
    expect(current[0].textContent).toContain('지급관리');
  });

  it('트리가 비어 있으면 사이드바 없이 본문만 그린다', () => {
    renderAt('/admin/settlement', []);

    expect(screen.getByText('본문')).toBeInTheDocument();
    expect(screen.queryByText('정산조회')).not.toBeInTheDocument();
  });
});
