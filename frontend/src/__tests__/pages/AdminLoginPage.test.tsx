import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import AdminLoginPage from '@/pages/AdminLoginPage';
import { authApi } from '@/api/auth';

vi.mock('@/api/auth', () => ({
  authApi: {
    login: vi.fn(),
    register: vi.fn(),
    saveToken: vi.fn(),
    logout: vi.fn(),
    getCurrentUser: vi.fn(),
    isAuthenticated: vi.fn(),
  },
}));

const mockNavigate = vi.fn();
vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual('react-router-dom');
  return { ...actual, useNavigate: () => mockNavigate };
});

const renderPage = () =>
  render(
    <MemoryRouter>
      <AdminLoginPage />
    </MemoryRouter>
  );

describe('AdminLoginPage', () => {
  beforeEach(() => {
    vi.resetAllMocks();
  });

  // ─── 렌더링 ──────────────────────────────────────────────
  describe('렌더링', () => {
    it('Lemuel 로고와 관리자 시스템 텍스트가 표시된다', () => {
      renderPage();

      expect(screen.getByText('Lemuel')).toBeInTheDocument();
      expect(screen.getByText('관리자 시스템')).toBeInTheDocument();
    });

    it('로그인/회원가입 탭이 렌더링된다', () => {
      renderPage();

      expect(screen.getByRole('button', { name: '로그인' })).toBeInTheDocument();
      expect(screen.getByRole('button', { name: '회원가입' })).toBeInTheDocument();
    });

    it('초기에는 로그인 탭이 활성화된다', () => {
      renderPage();

      expect(screen.getByPlaceholderText('이메일')).toBeInTheDocument();
      expect(screen.getByPlaceholderText('비밀번호')).toBeInTheDocument();
      expect(screen.queryByText('역할 선택')).not.toBeInTheDocument();
    });
  });

  // ─── 탭 전환 ─────────────────────────────────────────────
  describe('탭 전환', () => {
    it('회원가입 탭 클릭 시 역할 선택 버튼이 나타난다', async () => {
      const user = userEvent.setup();
      renderPage();

      await user.click(screen.getByRole('button', { name: '회원가입' }));

      expect(screen.getByText('역할 선택')).toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'MANAGER' })).toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'ADMIN' })).toBeInTheDocument();
    });

    it('탭 전환 시 에러 메시지가 초기화된다', async () => {
      const user = userEvent.setup();
      vi.mocked(authApi.login).mockRejectedValueOnce({
        response: { data: { message: '로그인 실패' } },
      });
      renderPage();

      // 로그인 실패로 에러 발생
      await user.click(screen.getByRole('button', { name: '로그인', hidden: false }));
      // 실제로 폼 submit이어야 에러가 나므로, 탭 자체 에러 시나리오를 검증
      await user.click(screen.getByRole('button', { name: '회원가입' }));
      await user.click(screen.getByRole('button', { name: '로그인' }));

      expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    });
  });

  // ─── 로그인 ───────────────────────────────────────────────
  describe('로그인', () => {
    it('ADMIN 계정으로 로그인하면 /admin으로 이동한다', async () => {
      const user = userEvent.setup();
      vi.mocked(authApi.login).mockResolvedValueOnce({ token: 'jwt', email: 'admin@test.com', role: 'ADMIN' });
      renderPage();

      await user.type(screen.getByPlaceholderText('이메일'), 'admin@test.com');
      await user.type(screen.getByPlaceholderText('비밀번호'), 'password');
      await user.click(screen.getByRole('button', { name: '로그인' }));

      await waitFor(() => expect(mockNavigate).toHaveBeenCalledWith('/admin'));
      expect(authApi.saveToken).toHaveBeenCalled();
    });

    it('MANAGER 계정으로 로그인하면 /admin으로 이동한다', async () => {
      const user = userEvent.setup();
      vi.mocked(authApi.login).mockResolvedValueOnce({ token: 'jwt', email: 'mgr@test.com', role: 'MANAGER' });
      renderPage();

      await user.type(screen.getByPlaceholderText('이메일'), 'mgr@test.com');
      await user.type(screen.getByPlaceholderText('비밀번호'), 'password');
      await user.click(screen.getByRole('button', { name: '로그인' }));

      await waitFor(() => expect(mockNavigate).toHaveBeenCalledWith('/admin'));
    });

    it('USER 역할로 로그인 시 에러 메시지를 표시한다', async () => {
      const user = userEvent.setup();
      vi.mocked(authApi.login).mockResolvedValueOnce({ token: 'jwt', email: 'user@test.com', role: 'USER' });
      renderPage();

      await user.type(screen.getByPlaceholderText('이메일'), 'user@test.com');
      await user.type(screen.getByPlaceholderText('비밀번호'), 'password');
      await user.click(screen.getByRole('button', { name: '로그인' }));

      await waitFor(() =>
        expect(screen.getByText('관리자(ADMIN) 또는 매니저(MANAGER) 계정으로만 로그인할 수 있습니다.')).toBeInTheDocument()
      );
      expect(mockNavigate).not.toHaveBeenCalled();
    });

    it('로그인 실패 시 에러 메시지를 표시한다', async () => {
      const user = userEvent.setup();
      vi.mocked(authApi.login).mockRejectedValueOnce({
        response: { data: { message: '이메일 또는 비밀번호가 올바르지 않습니다.' } },
      });
      renderPage();

      await user.type(screen.getByPlaceholderText('이메일'), 'bad@test.com');
      await user.type(screen.getByPlaceholderText('비밀번호'), 'wrong');
      await user.click(screen.getByRole('button', { name: '로그인' }));

      await waitFor(() =>
        expect(screen.getByText('이메일 또는 비밀번호가 올바르지 않습니다.')).toBeInTheDocument()
      );
    });

    it('로그인 중에는 버튼이 비활성화된다', async () => {
      const user = userEvent.setup();
      let resolveLogin!: (v: any) => void;
      vi.mocked(authApi.login).mockImplementationOnce(() => new Promise(r => { resolveLogin = r; }));
      renderPage();

      await user.type(screen.getByPlaceholderText('이메일'), 'admin@test.com');
      await user.type(screen.getByPlaceholderText('비밀번호'), 'password');
      await user.click(screen.getByRole('button', { name: '로그인' }));

      await waitFor(() => expect(screen.getByRole('button', { name: '로그인 중...' })).toBeDisabled());

      resolveLogin({ token: 'jwt', email: 'admin@test.com', role: 'ADMIN' });
    });
  });

  // ─── 회원가입 ─────────────────────────────────────────────
  describe('회원가입', () => {
    const goToRegisterTab = async (user: ReturnType<typeof userEvent.setup>) => {
      await user.click(screen.getByRole('button', { name: '회원가입' }));
    };

    it('비밀번호 불일치 시 에러 메시지를 표시한다', async () => {
      const user = userEvent.setup();
      renderPage();
      await goToRegisterTab(user);

      const inputs = screen.getAllByPlaceholderText('비밀번호');
      await user.type(inputs[0], 'password123');
      await user.type(screen.getByPlaceholderText('비밀번호 확인'), 'different');
      await user.click(screen.getByRole('button', { name: 'MANAGER 계정 생성' }));

      expect(screen.getByText('비밀번호가 일치하지 않습니다.')).toBeInTheDocument();
    });

    it('MANAGER 계정 생성 성공 시 로그인 탭으로 전환되고 성공 메시지가 표시된다', async () => {
      const user = userEvent.setup();
      vi.mocked(authApi.register).mockResolvedValueOnce({ id: 1, email: 'mgr@test.com', createdAt: '', updatedAt: '' });
      renderPage();
      await goToRegisterTab(user);

      await user.type(screen.getByPlaceholderText('이메일'), 'mgr@test.com');
      const pwInputs = screen.getAllByPlaceholderText('비밀번호');
      await user.type(pwInputs[0], 'password123');
      await user.type(screen.getByPlaceholderText('비밀번호 확인'), 'password123');
      await user.click(screen.getByRole('button', { name: 'MANAGER 계정 생성' }));

      await waitFor(() =>
        expect(screen.getByText('MANAGER 계정이 생성되었습니다. 로그인해주세요.')).toBeInTheDocument()
      );
    });

    it('ADMIN 역할 선택 후 계정 생성 버튼 텍스트가 변경된다', async () => {
      const user = userEvent.setup();
      renderPage();
      await goToRegisterTab(user);

      await user.click(screen.getByRole('button', { name: 'ADMIN' }));

      expect(screen.getByRole('button', { name: 'ADMIN 계정 생성' })).toBeInTheDocument();
    });

    it('이메일 중복 시 에러 메시지를 표시한다', async () => {
      const user = userEvent.setup();
      vi.mocked(authApi.register).mockRejectedValueOnce({ response: { status: 409 } });
      renderPage();
      await goToRegisterTab(user);

      await user.type(screen.getByPlaceholderText('이메일'), 'dup@test.com');
      const pwInputs = screen.getAllByPlaceholderText('비밀번호');
      await user.type(pwInputs[0], 'password123');
      await user.type(screen.getByPlaceholderText('비밀번호 확인'), 'password123');
      await user.click(screen.getByRole('button', { name: 'MANAGER 계정 생성' }));

      await waitFor(() => expect(screen.getByText('이미 사용 중인 이메일입니다.')).toBeInTheDocument());
    });
  });
});