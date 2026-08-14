import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import App from '@/App';

vi.mock('@/pages/WorkforcePage', () => ({
  default: () => <div>사업장 인력 화면</div>,
}));

/**
 * `<App/>` 를 통째로 렌더하면 AuthProvider 가 `/users/me` 를 부른다. jsdom 의 기본 오리진은
 * http://localhost:3000 이라, 개발자 PC 에 compose 프론트가 떠 있으면 그 요청이 **실제 서버로 나가서**
 * 401 을 받고 전역 인터셉터가 토큰을 지운다 → 라우트 가드가 로그인으로 되돌려 이 테스트가 깨진다
 * (CI 는 3000 포트가 비어 있어 우연히 통과한다). 이 테스트의 관심사는 localStorage 기반 라우트
 * 가드이므로, 네트워크 의존을 끊어 판정이 환경에 흔들리지 않게 한다.
 */
vi.mock('@/api/user', () => ({
  userApi: { me: () => Promise.reject(new Error('network disabled in test')) },
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
