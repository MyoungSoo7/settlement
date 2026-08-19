import { describe, it, expect, vi, beforeEach } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import PointConsolePage from '@/pages/system/PointConsolePage';
import { pointApi } from '@/api/point';

vi.mock('@/api/point', () => ({
  pointApi: {
    grant: vi.fn(), runExpiry: vi.fn(), myBalance: vi.fn(),
    summary: vi.fn(), account: vi.fn(), policies: vi.fn(), expiring: vi.fn(),
  },
}));

const mocked = vi.mocked(pointApi);

const balancedSummary = {
  accountCount: 3,
  totalAvailable: 12000,
  totalActiveLotRemaining: 12000,
  totalEntryNet: 12000,
  driftedAccountCount: 0,
  expiringWithinDays: 30,
  expiringAmount: 0,
};

describe('PointConsolePage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    // 화면 진입 시 현황 4종을 함께 부른다 — 조작 테스트도 이 기본값 위에서 돈다.
    mocked.summary.mockResolvedValue(balancedSummary);
    mocked.policies.mockResolvedValue([]);
    mocked.expiring.mockResolvedValue([]);
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

  it('지급에 성공하면 위쪽 현황을 다시 읽는다 — 방금 만든 돈이 안 보이면 안 된다', async () => {
    mocked.grant.mockResolvedValue({
      entryId: 100, lotId: 55, grantedAmount: 5000, remainingBalance: 5000,
    });
    const user = userEvent.setup();
    render(<PointConsolePage />);
    await waitFor(() => expect(mocked.summary).toHaveBeenCalledTimes(1));

    await fillGrantForm(user);
    await user.click(screen.getByRole('button', { name: '포인트 지급' }));

    await waitFor(() => expect(mocked.summary).toHaveBeenCalledTimes(2));
  });

  describe('원장 현황', () => {
    it('3자 대조가 맞으면 균형으로 보고한다', async () => {
      render(<PointConsolePage />);

      expect(await screen.findByTestId('point-ledger-balance')).toHaveTextContent('3자 대조 균형');
      expect(screen.queryByTestId('point-ledger-drift')).not.toBeInTheDocument();
    });

    it('어긋난 계정이 있으면 조사 대상으로 지목한다', async () => {
      mocked.summary.mockResolvedValue({ ...balancedSummary, driftedAccountCount: 2 });
      render(<PointConsolePage />);

      expect(await screen.findByTestId('point-ledger-drift')).toHaveTextContent('계정 2개');
    });

    it('소멸 예정 기준 일수를 바꾸면 그 값으로 다시 읽는다', async () => {
      render(<PointConsolePage />);
      await waitFor(() => expect(mocked.summary).toHaveBeenCalledWith(30));

      // 제어 입력이라 한 글자씩 치면 중간 상태(빈 값 → 기본 30)가 값에 섞인다.
      // 여기서 보려는 것은 타이핑이 아니라 "값이 바뀌면 다시 읽는가"이므로 값을 한 번에 넣는다.
      fireEvent.change(screen.getByLabelText('소멸 예정 기준 일수'), { target: { value: '7' } });

      await waitFor(() => expect(mocked.summary).toHaveBeenLastCalledWith(7));
      expect(mocked.expiring).toHaveBeenLastCalledWith(7, 50);
    });
  });

  describe('계정 조회', () => {
    it('조회하면 계정의 3자 대조와 로트·원장 내역을 보여 준다', async () => {
      mocked.account.mockResolvedValue({
        userId: 3, accountId: 70, status: 'ACTIVE',
        available: 12000, locked: 0, total: 12000,
        health: { accountAvailable: 12000, activeLotRemaining: 12000, entryNet: 12000 },
        lots: [{
          lotId: 1, origin: 'MANUAL_GRANT', originalAmount: 5000, remainingAmount: 5000,
          status: 'ACTIVE', grantedAt: '2026-08-18T14:31:50Z', expiresAt: '2027-08-18T14:31:50Z',
          referenceType: 'MANUAL', referenceId: 'smoke-1',
        }],
        entries: [{
          entryId: 9, entryType: 'GRANT', amount: 5000, referenceType: 'MANUAL',
          referenceId: 'smoke-1', memo: 'CS 보상', createdBy: 'admin:1',
          createdAt: '2026-08-18T14:31:50Z',
        }],
      });
      const user = userEvent.setup();
      render(<PointConsolePage />);

      await user.type(screen.getByLabelText('조회할 회원 ID'), '3');
      await user.click(screen.getByRole('button', { name: '조회' }));

      expect(await screen.findByTestId('account-health-balanced')).toBeInTheDocument();
      expect(screen.getByText(/MANUAL_GRANT · ACTIVE/)).toBeInTheDocument();
      expect(screen.getByTestId('entry-memo')).toHaveTextContent('CS 보상');
    });

    it('계정 잔고와 로트 합계가 어긋나면 불일치로 표시한다', async () => {
      mocked.account.mockResolvedValue({
        userId: 3, accountId: 70, status: 'ACTIVE',
        available: 1000, locked: 0, total: 1000,
        health: { accountAvailable: 1000, activeLotRemaining: 700, entryNet: 1000 },
        lots: [], entries: [],
      });
      const user = userEvent.setup();
      render(<PointConsolePage />);

      await user.type(screen.getByLabelText('조회할 회원 ID'), '3');
      await user.click(screen.getByRole('button', { name: '조회' }));

      expect(await screen.findByTestId('account-health-drift'))
        .toHaveTextContent('로트 합계 700P');
    });

    it('404 는 장애가 아니라 "계정 없음"으로 안내한다 — 잔액 0 인 계정과 다르다', async () => {
      mocked.account.mockRejectedValue({ response: { status: 404 } });
      const user = userEvent.setup();
      render(<PointConsolePage />);

      await user.type(screen.getByLabelText('조회할 회원 ID'), '99');
      await user.click(screen.getByRole('button', { name: '조회' }));

      expect(await screen.findByTestId('point-account-error'))
        .toHaveTextContent('포인트 계정이 아직 없습니다');
    });
  });

  describe('적립률 정책', () => {
    it('정책이 없으면 적립률 0 임을 알린다 — 빈 표는 "설정 안 함"이 아니라 "적립 없음"이다', async () => {
      render(<PointConsolePage />);

      expect(await screen.findByText(/현재 주문 적립은 0P/)).toBeInTheDocument();
    });

    it('종료된 정책도 이력으로 함께 보여 준다', async () => {
      mocked.policies.mockResolvedValue([
        {
          id: 1, scope: 'GLOBAL', scopeKey: '-', earnRate: 0.01, validityDays: 365,
          effectiveFrom: '2026-01-01', effectiveTo: null, reason: '기본 적립률',
          createdBy: 'admin', active: true,
        },
        {
          id: 2, scope: 'GLOBAL', scopeKey: '-', earnRate: 0.005, validityDays: 365,
          effectiveFrom: '2025-01-01', effectiveTo: '2026-01-01', reason: '구 요율',
          createdBy: 'admin', active: false,
        },
      ]);
      render(<PointConsolePage />);

      expect(await screen.findByTestId('policy-active')).toHaveTextContent('적용 중');
      expect(screen.getByTestId('policy-closed')).toHaveTextContent('종료');
    });
  });

  describe('소멸 예정', () => {
    it('소멸 예정 로트를 회원·금액·만료일로 보여 준다', async () => {
      mocked.expiring.mockResolvedValue([{
        userId: 3, lotId: 1, origin: 'MANUAL_GRANT',
        remainingAmount: 5000, expiresAt: '2027-08-18T14:31:50Z',
      }]);
      render(<PointConsolePage />);

      expect(await screen.findByText(/2027-08-18 만료/)).toBeInTheDocument();
    });
  });
});
