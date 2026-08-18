import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import PointConsolePage from '@/pages/system/PointConsolePage';
import { pointApi } from '@/api/point';

vi.mock('@/api/point', () => ({
  pointApi: { grant: vi.fn(), runExpiry: vi.fn(), myBalance: vi.fn() },
}));

const mocked = vi.mocked(pointApi);

describe('PointConsolePage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  const fillGrantForm = async (user: ReturnType<typeof userEvent.setup>) => {
    await user.type(screen.getByLabelText('회원 ID'), '42');
    await user.type(screen.getByLabelText('지급 포인트'), '5000');
    await user.type(screen.getByLabelText('참조 ID'), 'cs-1');
    await user.type(screen.getByLabelText('지급 사유'), '배송 지연 보상');
  };

  it('사유를 입력하기 전에는 지급 버튼이 잠겨 있다 — 근거 없는 지급을 화면이 먼저 막는다', async () => {
    const user = userEvent.setup();
    render(<PointConsolePage />);

    await user.type(screen.getByLabelText('회원 ID'), '42');
    await user.type(screen.getByLabelText('지급 포인트'), '5000');
    await user.type(screen.getByLabelText('참조 ID'), 'cs-1');

    expect(screen.getByRole('button', { name: '포인트 지급' })).toBeDisabled();

    await user.type(screen.getByLabelText('지급 사유'), '보상');
    expect(screen.getByRole('button', { name: '포인트 지급' })).toBeEnabled();
  });

  it('지급하면 입력한 참조 ID 를 멱등 키로 그대로 보낸다', async () => {
    mocked.grant.mockResolvedValue({
      entryId: 100, lotId: 55, grantedAmount: 5000, remainingBalance: 5000,
    });
    const user = userEvent.setup();
    render(<PointConsolePage />);

    await fillGrantForm(user);
    await user.click(screen.getByRole('button', { name: '포인트 지급' }));

    await waitFor(() => expect(mocked.grant).toHaveBeenCalledWith(expect.objectContaining({
      userId: 42, amount: 5000, referenceId: 'cs-1', reason: '배송 지연 보상',
    })));
  });

  it('멱등 단축 반환(entryId=null)은 중복 지급이 아니었음을 알린다', async () => {
    mocked.grant.mockResolvedValue({
      entryId: null, lotId: null, grantedAmount: 5000, remainingBalance: 5000,
    });
    const user = userEvent.setup();
    render(<PointConsolePage />);

    await fillGrantForm(user);
    await user.click(screen.getByRole('button', { name: '포인트 지급' }));

    expect(await screen.findByRole('status'))
      .toHaveTextContent('이미 지급된 참조 ID');
  });

  it('미리보기를 돌리기 전에는 소멸 실행 버튼이 잠겨 있다', async () => {
    mocked.runExpiry.mockResolvedValue({
      lotCount: 3, accountCount: 2, forfeitedTotal: 1500, dryRun: true,
    });
    const user = userEvent.setup();
    render(<PointConsolePage />);

    expect(screen.getByRole('button', { name: '소멸 실행' })).toBeDisabled();

    await user.click(screen.getByRole('button', { name: '미리보기' }));

    await waitFor(() => expect(screen.getByRole('button', { name: '소멸 실행' })).toBeEnabled());
    expect(mocked.runExpiry).toHaveBeenCalledWith(true);
  });

  it('소멸 실행은 dryRun=false 로 부르고 낡은 미리보기를 지운다', async () => {
    mocked.runExpiry
      .mockResolvedValueOnce({ lotCount: 3, accountCount: 2, forfeitedTotal: 1500, dryRun: true })
      .mockResolvedValueOnce({ lotCount: 3, accountCount: 2, forfeitedTotal: 1500, dryRun: false });
    const user = userEvent.setup();
    render(<PointConsolePage />);

    await user.click(screen.getByRole('button', { name: '미리보기' }));
    await waitFor(() => expect(screen.getByRole('button', { name: '소멸 실행' })).toBeEnabled());
    await user.click(screen.getByRole('button', { name: '소멸 실행' }));

    await waitFor(() => expect(mocked.runExpiry).toHaveBeenLastCalledWith(false));
    expect(await screen.findByText(/소멸 완료/)).toBeInTheDocument();
    // 실행 뒤 미리보기 버튼은 다시 잠긴다 — 낡은 수치로 두 번 실행하지 않게.
    await waitFor(() => expect(screen.getByRole('button', { name: '소멸 실행' })).toBeDisabled());
  });

  it('지급 실패는 서버 문구를 그대로 보여 준다', async () => {
    mocked.grant.mockRejectedValue({ response: { data: { message: '계정이 해지되었습니다' } } });
    const user = userEvent.setup();
    render(<PointConsolePage />);

    await fillGrantForm(user);
    await user.click(screen.getByRole('button', { name: '포인트 지급' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('계정이 해지되었습니다');
  });
});
