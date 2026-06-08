import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import api, { setGlobalToast } from '@/api/axios';

const requestFulfilled = () => (api.interceptors.request as any).handlers[0].fulfilled;
const requestRejected = () => (api.interceptors.request as any).handlers[0].rejected;
const responseFulfilled = () => (api.interceptors.response as any).handlers[0].fulfilled;
const responseRejected = () => (api.interceptors.response as any).handlers[0].rejected;

describe('axios interceptors', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    localStorage.clear();
    window.history.pushState({}, '', '/order');
  });

  afterEach(() => {
    vi.useRealTimers();
    localStorage.clear();
  });

  it('요청에 access token이 있으면 Authorization 헤더를 추가한다', () => {
    localStorage.setItem('access_token', 'jwt');

    const config = requestFulfilled()({ headers: {} });

    expect(config.headers.Authorization).toBe('Bearer jwt');
  });

  it('요청 에러는 그대로 reject한다', async () => {
    const error = new Error('request');

    await expect(requestRejected()(error)).rejects.toBe(error);
  });

  it('정상 응답은 그대로 반환한다', () => {
    const response = { data: { ok: true } };

    expect(responseFulfilled()(response)).toBe(response);
  });

  it('401 응답은 세션을 제거하고 로그인으로 이동시킨다', async () => {
    const showToast = vi.fn();
    setGlobalToast(showToast);
    localStorage.setItem('access_token', 'jwt');
    localStorage.setItem('user_email', 'user@test.com');
    localStorage.setItem('user_role', 'USER');

    await expect(responseRejected()({ response: { status: 401 } })).rejects.toMatchObject({
      response: { status: 401 },
    });

    expect(showToast).toHaveBeenCalledWith('세션이 만료되었습니다. 다시 로그인해주세요.', 'warning');
    expect(localStorage.getItem('access_token')).toBeNull();

    vi.advanceTimersByTime(1000);
  });

  it('403, 500, network error 토스트를 처리한다', async () => {
    const showToast = vi.fn();
    const consoleSpy = vi.spyOn(console, 'error').mockImplementation(() => {});
    setGlobalToast(showToast);

    await expect(responseRejected()({
      response: { status: 403, data: 'denied' },
      config: { url: '/password-reset/request' },
    })).rejects.toMatchObject({ response: { status: 403 } });
    await expect(responseRejected()({ response: { status: 500 } })).rejects.toMatchObject({
      response: { status: 500 },
    });
    await expect(responseRejected()({ message: 'Network Error' })).rejects.toMatchObject({
      message: 'Network Error',
    });

    expect(showToast).toHaveBeenCalledWith('접근 권한이 없습니다.', 'error');
    expect(showToast).toHaveBeenCalledWith('서버 오류가 발생했습니다. 잠시 후 다시 시도해주세요.', 'error');
    expect(showToast).toHaveBeenCalledWith('네트워크 오류가 발생했습니다. 인터넷 연결을 확인해주세요.', 'error');
    expect(consoleSpy).toHaveBeenCalled();
  });
});
