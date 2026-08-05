import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import App from '@/App';

vi.mock('@/pages/WorkforcePage', () => ({
  default: () => <div>사업장 인력 화면</div>,
}));

const visitWorkforce = () => {
  window.history.pushState({}, '', '/workforce');
  render(<App />);
};

describe('/workforce 접근 제어', () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it('인증되지 않은 사용자를 관리자 로그인으로 보낸다', async () => {
    visitWorkforce();

    expect(await screen.findByText('관리자 시스템')).toBeInTheDocument();
    expect(screen.queryByText('사업장 인력 화면')).not.toBeInTheDocument();
  });

  it('일반 사용자를 사용자 로그인으로 보낸다', async () => {
    localStorage.setItem('access_token', 'user-token');
    localStorage.setItem('user_email', 'user@example.com');
    localStorage.setItem('user_role', 'USER');

    visitWorkforce();

    expect(await screen.findByRole('heading', { name: '사용자 로그인' })).toBeInTheDocument();
    expect(screen.queryByText('사업장 인력 화면')).not.toBeInTheDocument();
  });

  it.each(['ADMIN', 'MANAGER'])('%s에게 사업장 인력 화면을 허용한다', async (role) => {
    localStorage.setItem('access_token', `${role.toLowerCase()}-token`);
    localStorage.setItem('user_email', `${role.toLowerCase()}@example.com`);
    localStorage.setItem('user_role', role);

    visitWorkforce();

    expect(await screen.findByText('사업장 인력 화면')).toBeInTheDocument();
  });
});
