import { test as base, expect } from '@playwright/test';

/**
 * 크로스 브라우저 E2E 공용 픽스처 — 장기 연결(SSE) 차단.
 *
 * 로그인 직후 앱이 `/api/notifications/stream` 을 EventSource 로 구독한다(끝나지 않는 스트림).
 * Chromium 은 컨텍스트 종료 시 이 연결을 강제로 끊지만 **WebKit/Firefox 는 연결이 남아 워커
 * 프로세스가 종료되지 않는다** → Playwright 가 300초 뒤 force-kill 하고
 * "worker process did not exit" 를 테스트에 속하지 않는 에러로 보고한다.
 * 이때 **모든 테스트가 통과해도 종료 코드가 1** 이라 CI 가 빨갛게 뜬다
 * (실측 2026-08-11: webkit 단일 통과 케이스에서 `1 passed` + EXIT=1, 소요 5.1분).
 *
 * 차단은 라우트 abort 가 아니라 **`EventSource` 생성자 자체를 스텁**해서 한다 —
 * abort 는 EventSource 의 자동 재연결을 깨워 3초 주기 재요청 루프를 만들 뿐 연결을 없애지 못한다.
 * E2E 의 검증 대상은 라우팅·인증·화면이지 SSE 전송 자체가 아니다. SSE 를 직접 검증해야 하면
 * 해당 테스트에서 이 스텁을 걷어내고 명시적으로 다룰 것.
 */
export const test = base.extend<{ blockLongLivedStreams: void; releaseAppPages: void }>({
  blockLongLivedStreams: [
    async ({ context }, use) => {
      await context.addInitScript(() => {
        class InertEventSource {
          static readonly CONNECTING = 0;
          static readonly OPEN = 1;
          static readonly CLOSED = 2;
          readonly url: string;
          readonly withCredentials = false;
          readonly readyState = 2; // CLOSED — 앱이 상태를 읽어도 "끊김"으로 일관되게 보인다.
          onopen: unknown = null;
          onmessage: unknown = null;
          onerror: unknown = null;
          constructor(url: string) {
            this.url = String(url);
          }
          addEventListener() {}
          removeEventListener() {}
          dispatchEvent() {
            return false;
          }
          close() {}
        }
        Object.defineProperty(window, 'EventSource', {
          configurable: true,
          writable: true,
          value: InertEventSource,
        });
      });
      await use();
    },
    { auto: true },
  ],

  /**
   * 테스트를 앱 페이지 위에서 끝내지 않는다 — 종료 직전 모든 페이지를 about:blank 로 보낸다.
   *
   * EventSource 스텁(위)만으로는 WebKit 워커가 끝까지 종료되지 않는 경우가 남았다. 앱 페이지가
   * 살아 있는 한 서비스워커(`/sw.js`)와 잔여 요청이 컨텍스트를 붙잡고, Playwright 는 300초 뒤
   * 워커를 force-kill 하며 **모든 테스트가 통과해도 종료 코드를 1** 로 만든다.
   * about:blank 로 빠지면 그 참조가 함께 끊긴다.
   *
   * 실측(mobile-chrome + mobile-safari, mobile-layout.spec.ts 6케이스):
   *   적용 전 5.2분 + `worker did not exit` 3건 → 적용 후 10.1초 + 종료 코드 0.
   *
   * `page` 가 아니라 `context` 에 의존하는 이유: `page` 를 의존하면 페이지가 필요 없는 API 전용
   * 테스트(smoke 의 request 케이스)에도 빈 페이지가 강제로 생성된다. 컨텍스트는 위 픽스처가
   * 이미 만들고 있으므로 추가 비용이 없다.
   */
  releaseAppPages: [
    async ({ context }, use) => {
      await use();
      await Promise.all(
        // 실패로 페이지가 이상 상태여도 정리는 계속돼야 한다 — 에러는 삼키고 타임아웃을 둔다.
        context.pages().map((page) => page.goto('about:blank', { timeout: 5_000 }).catch(() => {})),
      );
    },
    { auto: true },
  ],
});

export { expect };
