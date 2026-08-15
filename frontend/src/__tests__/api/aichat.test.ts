import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { aiChatApi, type ChatResponse } from '@/api/aichat';
import api from '@/api/axios';

vi.mock('@/api/axios', () => ({
  default: {
    get: vi.fn(),
    post: vi.fn(),
    delete: vi.fn(),
  },
}));

const doneEvent: ChatResponse = {
  conversationId: 'c-1',
  reply: '안녕하세요',
  model: 'gemini-2.0-flash',
  usage: { inputTokens: 10, outputTokens: 4 },
};

/** SSE 본문을 청크 단위로 흘려주는 fetch 응답 페이크 (jsdom 에는 스트리밍 응답이 없다) */
const sseResponse = (chunks: string[], init: { ok?: boolean; status?: number } = {}) => {
  const encoder = new TextEncoder();
  let i = 0;
  return {
    ok: init.ok ?? true,
    status: init.status ?? 200,
    body: {
      getReader: () => ({
        read: () =>
          Promise.resolve(
            i < chunks.length
              ? { value: encoder.encode(chunks[i++]), done: false }
              : { value: undefined, done: true },
          ),
      }),
    },
    text: () => Promise.resolve(''),
  };
};

beforeEach(() => {
  vi.resetAllMocks();
  localStorage.clear();
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('aiChatApi.chat (동기 폴백)', () => {
  it('메시지를 보내고 응답을 반환한다 — LLM 대기를 위해 타임아웃을 늘려 호출한다', async () => {
    vi.mocked(api.post).mockResolvedValueOnce({ data: doneEvent });

    const result = await aiChatApi.chat('안녕');

    expect(api.post).toHaveBeenCalledWith(
      '/api/ai/chat',
      { message: '안녕', conversationId: null },
      { timeout: 65000 },
    );
    expect(result.reply).toBe('안녕하세요');
  });

  it('대화 이어가기는 conversationId 를 그대로 실어 보낸다', async () => {
    vi.mocked(api.post).mockResolvedValueOnce({ data: doneEvent });

    await aiChatApi.chat('이어서', 'c-1');

    expect(api.post).toHaveBeenCalledWith(
      '/api/ai/chat',
      { message: '이어서', conversationId: 'c-1' },
      { timeout: 65000 },
    );
  });
});

describe('aiChatApi.chatStream (SSE)', () => {
  it('delta 를 순서대로 흘리고 done 페이로드를 반환한다', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() =>
        Promise.resolve(
          sseResponse([
            'event: delta\ndata: 안녕\n\n',
            'event: delta\ndata: 하세요\n\n',
            `event: done\ndata: ${JSON.stringify(doneEvent)}\n\n`,
          ]),
        ),
      ),
    );
    const deltas: string[] = [];

    const result = await aiChatApi.chatStream('안녕', undefined, (d) => deltas.push(d));

    expect(deltas).toEqual(['안녕', '하세요']);
    expect(result.conversationId).toBe('c-1');
  });

  it('토큰이 있으면 Authorization 헤더를 붙인다', async () => {
    localStorage.setItem('access_token', 'jwt-token');
    const spy = vi.fn((_input: RequestInfo | URL, _init?: RequestInit) =>
      Promise.resolve(sseResponse([`event: done\ndata: ${JSON.stringify(doneEvent)}\n\n`])),
    );
    vi.stubGlobal('fetch', spy);

    await aiChatApi.chatStream('안녕', 'c-1', () => undefined);

    const init = spy.mock.calls[0][1] as RequestInit;
    expect((init.headers as Record<string, string>).Authorization).toBe('Bearer jwt-token');
    expect(JSON.parse(init.body as string)).toEqual({ message: '안녕', conversationId: 'c-1' });
  });

  it('토큰이 없으면 Authorization 헤더를 붙이지 않는다', async () => {
    const spy = vi.fn((_input: RequestInfo | URL, _init?: RequestInit) =>
      Promise.resolve(sseResponse([`event: done\ndata: ${JSON.stringify(doneEvent)}\n\n`])),
    );
    vi.stubGlobal('fetch', spy);

    await aiChatApi.chatStream('안녕', undefined, () => undefined);

    const init = spy.mock.calls[0][1] as RequestInit;
    expect((init.headers as Record<string, string>).Authorization).toBeUndefined();
  });

  it('여러 프레임이 한 청크에 몰려 와도 개별 이벤트로 분해한다', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() =>
        Promise.resolve(
          sseResponse([
            `event: delta\ndata: A\n\nevent: delta\ndata: B\n\nevent: done\ndata: ${JSON.stringify(doneEvent)}\n\n`,
          ]),
        ),
      ),
    );
    const deltas: string[] = [];

    await aiChatApi.chatStream('안녕', undefined, (d) => deltas.push(d));

    expect(deltas).toEqual(['A', 'B']);
  });

  it('멀티라인 data 는 개행으로 이어 붙인다', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() =>
        Promise.resolve(
          sseResponse([
            'event: delta\ndata: 첫줄\ndata: 둘째줄\n\n',
            `event: done\ndata: ${JSON.stringify(doneEvent)}\n\n`,
          ]),
        ),
      ),
    );
    const deltas: string[] = [];

    await aiChatApi.chatStream('안녕', undefined, (d) => deltas.push(d));

    expect(deltas).toEqual(['첫줄\n둘째줄']);
  });

  it('서버가 error 이벤트를 보내면 그 메시지로 throw 한다', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() => Promise.resolve(sseResponse(['event: error\ndata: 모델 호출 실패\n\n']))),
    );

    await expect(aiChatApi.chatStream('안녕', undefined, () => undefined)).rejects.toThrow(
      '모델 호출 실패',
    );
  });

  it('done 없이 스트림이 끊기면 중단 메시지로 throw 한다', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() => Promise.resolve(sseResponse(['event: delta\ndata: 안녕\n\n']))),
    );

    await expect(aiChatApi.chatStream('안녕', undefined, () => undefined)).rejects.toThrow(
      'AI 응답이 중단되었습니다. 다시 시도해 주세요.',
    );
  });

  it('429 는 레이트리밋 안내 문구로 바꿔 throw 한다', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() =>
        Promise.resolve({
          ok: false,
          status: 429,
          body: null,
          text: () => Promise.resolve('{"message":"too many"}'),
        }),
      ),
    );

    await expect(aiChatApi.chatStream('안녕', undefined, () => undefined)).rejects.toThrow(
      '요청이 너무 잦습니다. 잠시 후 다시 시도해 주세요.',
    );
  });

  it('그 외 실패는 서버 message 를 꺼내 throw 한다', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() =>
        Promise.resolve({
          ok: false,
          status: 500,
          body: null,
          text: () => Promise.resolve('{"message":"내부 오류"}'),
        }),
      ),
    );

    await expect(aiChatApi.chatStream('안녕', undefined, () => undefined)).rejects.toThrow('내부 오류');
  });

  it('본문이 JSON 이 아니면 기본 메시지로 throw 한다', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() =>
        Promise.resolve({
          ok: false,
          status: 502,
          body: null,
          text: () => Promise.resolve('<html>bad gateway</html>'),
        }),
      ),
    );

    await expect(aiChatApi.chatStream('안녕', undefined, () => undefined)).rejects.toThrow(
      'AI 응답에 실패했습니다.',
    );
  });
});

describe('aiChatApi 대화 목록·상세·삭제', () => {
  it('대화 목록은 기본 페이지·사이즈로 조회한다', async () => {
    vi.mocked(api.get).mockResolvedValueOnce({
      data: { content: [], page: 0, size: 20, totalElements: 0 },
    });

    const result = await aiChatApi.conversations();

    expect(api.get).toHaveBeenCalledWith('/api/ai/conversations?page=0&size=20');
    expect(result.totalElements).toBe(0);
  });

  it('페이지·사이즈를 지정할 수 있다', async () => {
    vi.mocked(api.get).mockResolvedValueOnce({
      data: { content: [], page: 2, size: 5, totalElements: 12 },
    });

    await aiChatApi.conversations(2, 5);

    expect(api.get).toHaveBeenCalledWith('/api/ai/conversations?page=2&size=5');
  });

  it('대화 상세는 메시지 전체를 반환한다', async () => {
    vi.mocked(api.get).mockResolvedValueOnce({
      data: {
        id: 'c-1',
        title: '정산 문의',
        createdAt: '2026-08-01T00:00:00Z',
        messages: [
          { role: 'USER', content: '안녕', model: null, createdAt: '2026-08-01T00:00:00Z' },
          { role: 'ASSISTANT', content: '반갑습니다', model: 'gemini', createdAt: '2026-08-01T00:00:01Z' },
        ],
      },
    });

    const result = await aiChatApi.conversation('c-1');

    expect(api.get).toHaveBeenCalledWith('/api/ai/conversations/c-1');
    expect(result.messages).toHaveLength(2);
  });

  it('대화를 삭제한다', async () => {
    vi.mocked(api.delete).mockResolvedValueOnce({ data: undefined });

    await aiChatApi.deleteConversation('c-1');

    expect(api.delete).toHaveBeenCalledWith('/api/ai/conversations/c-1');
  });
});
