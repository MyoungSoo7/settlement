import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import TagManagementPage from '@/pages/TagManagementPage';
import SettlementDashboard from '@/pages/SettlementDashboard';
import { ToastProvider } from '@/contexts/ToastContext';
import { tagApi } from '@/api/tag';
import { settlementApi } from '@/api/settlement';

/**
 * useEffect 의존성을 채우면서(exhaustive-deps) 재조회 루프가 생기지 않는지 지키는 회귀 테스트.
 *
 * <p>의존성에 매 렌더 새로 만들어지는 함수를 넣으면 effect → setState → 렌더 → effect 로 무한 루프가
 * 돈다. 두 전환 패턴을 각각 대표 페이지로 고정한다:
 * <ul>
 *   <li>마운트 1회형 — 로더를 useCallback([showToast]) 로 고정 (TagManagementPage)</li>
 *   <li>필터 구동형 — fetch 를 useCallback([filters]) 로 고정 (SettlementDashboard)</li>
 * </ul>
 */

vi.mock('@/api/tag', () => ({
  tagApi: { getAllTags: vi.fn(), createTag: vi.fn(), updateTag: vi.fn(), deleteTag: vi.fn() },
}));

vi.mock('@/api/settlement', () => ({
  settlementApi: { search: vi.fn(), getSettlement: vi.fn() },
}));

describe('useEffect 의존성 — 재조회 루프 방지', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('마운트 1회형: 로더가 정확히 한 번만 호출된다 (TagManagementPage)', async () => {
    vi.mocked(tagApi.getAllTags).mockResolvedValue([]);

    render(
      <ToastProvider>
        <TagManagementPage />
      </ToastProvider>,
    );

    await waitFor(() => expect(tagApi.getAllTags).toHaveBeenCalled());
    // 응답 반영 후 리렌더가 끝난 뒤에도 추가 호출이 없어야 한다.
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(tagApi.getAllTags).toHaveBeenCalledTimes(1);
  });

  it('필터 구동형: 마운트 시 1회, 필터를 바꾸면 1회만 더 조회한다 (SettlementDashboard)', async () => {
    vi.mocked(settlementApi.search).mockResolvedValue({
      settlements: [],
      totalElements: 0,
      totalPages: 0,
      currentPage: 0,
      pageSize: 20,
      aggregations: {
        totalAmount: 0,
        totalRefundedAmount: 0,
        totalFinalAmount: 0,
        statusCounts: {},
      },
    });

    render(<SettlementDashboard />);

    await waitFor(() => expect(settlementApi.search).toHaveBeenCalledTimes(1));

    await userEvent.type(await screen.findByPlaceholderText('주문자명 입력'), 'A');

    await waitFor(() => expect(settlementApi.search).toHaveBeenCalledTimes(2));
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(settlementApi.search).toHaveBeenCalledTimes(2);
  });
});
