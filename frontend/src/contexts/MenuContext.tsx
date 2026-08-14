import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { navMenuApi, type NavMenuNode } from '@/api/menu';
import { authApi } from '@/api/auth';
import { resolveFallbackMenus } from '@/data/menuFallback';
import { MenuContext } from './useMenus';

/**
 * 네비게이션 메뉴 공급자.
 *
 * <p>역할이 바뀔 때(로그인/로그아웃/계정 전환)만 다시 조회한다. 메뉴는 화면마다 바뀌는 값이
 * 아니라 세션 단위 값이므로, 라우트 이동마다 호출하면 순수한 낭비다.
 */
export const MenuProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const role = authApi.getCurrentUser()?.role ?? null;

  const [menus, setMenus] = useState<NavMenuNode[]>(() => resolveFallbackMenus(role));
  const [loading, setLoading] = useState(true);
  const [degraded, setDegraded] = useState(false);

  const load = useCallback(async (): Promise<void> => {
    setLoading(true);
    try {
      const data = await navMenuApi.getMine();
      // 로그인했는데 트리가 비어 있으면 서버 데이터가 아직 없는 상태(미마이그레이션 등)다.
      // 그대로 그리면 로그인은 됐는데 아무 데도 못 가는 화면이 되므로 스냅샷으로 버틴다.
      if (role !== null && data.length === 0) {
        setMenus(resolveFallbackMenus(role));
        setDegraded(true);
      } else {
        setMenus(data);
        setDegraded(false);
      }
    } catch {
      setMenus(resolveFallbackMenus(role));
      setDegraded(true);
    } finally {
      setLoading(false);
    }
  }, [role]);

  useEffect(() => {
    let cancelled = false;
    void (async () => {
      await load();
      if (cancelled) return;
    })();
    return () => { cancelled = true; };
  }, [load]);

  const value = useMemo(
    () => ({ menus, loading, degraded, refresh: load }),
    [menus, loading, degraded, load],
  );

  return <MenuContext.Provider value={value}>{children}</MenuContext.Provider>;
};
