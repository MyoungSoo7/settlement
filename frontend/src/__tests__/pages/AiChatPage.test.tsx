import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import AiChatPage from '@/pages/AiChatPage';
import { aiChatApi } from '@/api/aichat';

vi.mock('@/api/aichat', () => ({
  aiChatApi: {
    conversations: vi.fn(),
    conversation: vi.fn(),
    deleteConversation: vi.fn(),
    chat: vi.fn(),
    chatStream: vi.fn(),
  },
}));

const mocked = vi.mocked(aiChatApi);

const summary = (over: Record<string, unknown> = {}) =>
  ({
    id: 'c-1',
    title: '정산 문의',
    messageCount: 2,
    lastMessageAt: '2026-08-14T01:00:00Z',
    createdAt: '2026-08-14T00:00:00Z',
    ...over,
  }) as never;

const chatResponse = (over: Record<string, unknown> = {}) =>
  ({
    conversationId: 'c-1',
    reply: '안녕하세요',
    model: 'gemini',
    usage: { inputTokens: 1, outputTokens: 1 },
    ...over,
  }) as never;

beforeEach(() => {
  vi.clearAllMocks();
  mocked.conversations.mockResolvedValue({
    content: [summary()],
    page: 0,
    size: 30,
    totalElements: 1,
  } as never);
  mocked.conversation.mockResolvedValue({
    id: 'c-1',
    title: '정산 문의',
    createdAt: '2026-08-14T00:00:00Z',
    messages: [
      { role: 'USER', content: '정산 언제 되나요', model: null, createdAt: '2026-08-14T00:00:00Z' },
      { role: 'ASSISTANT', content: 'T+7 입니다', model: 'gemini', createdAt: '2026-08-14T00:00:01Z' },
    ],
  } as never);
  Element.prototype.scrollIntoView = vi.fn();
});

describe('AiChatPage — 대화 목록', () => {
  it('진입하면 대화 목록을 읽는다', async () => {
    render(<AiChatPage />);

    expect(await screen.findByText('정산 문의')).toBeInTheDocument();
    expect(mocked.conversations).toHaveBeenCalledWith(0, 30);
  });

  it('대화가 없으면 그 사실을 알린다', async () => {
    mocked.conversations.mockResolvedValue({ content: [], page: 0, size: 30, totalElements: 0 } as never);
    render(<AiChatPage />);

    expect(await screen.findByText('아직 대화가 없습니다.')).toBeInTheDocument();
  });

  it('목록 조회 실패는 채팅 자체를 막지 않는다', async () => {
    mocked.conversations.mockRejectedValue(new Error('down'));
    render(<AiChatPage />);

    expect(await screen.findByPlaceholderText(/메시지를 입력하세요/)).toBeInTheDocument();
  });

  it('대화를 고르면 지난 메시지를 불러온다', async () => {
    render(<AiChatPage />);

    await userEvent.click(await screen.findByText('정산 문의'));

    expect(await screen.findByText('T+7 입니다')).toBeInTheDocument();
    expect(mocked.conversation).toHaveBeenCalledWith('c-1');
  });

  it('대화 조회 실패는 사유를 보여 준다', async () => {
    mocked.conversation.mockRejectedValue(new Error('down'));
    render(<AiChatPage />);

    await userEvent.click(await screen.findByText('정산 문의'));

    expect(await screen.findByText('대화를 불러오지 못했습니다.')).toBeInTheDocument();
  });

  it('새 대화는 화면을 비운다', async () => {
    render(<AiChatPage />);
    await userEvent.click(await screen.findByText('정산 문의'));
    await screen.findByText('T+7 입니다');

    await userEvent.click(screen.getByRole('button', { name: '+ 새 대화' }));

    expect(screen.queryByText('T+7 입니다')).not.toBeInTheDocument();
  });

  it('대화를 삭제하면 목록을 다시 읽는다', async () => {
    mocked.deleteConversation.mockResolvedValue(undefined as never);
    render(<AiChatPage />);
    await screen.findByText('정산 문의');

    await userEvent.click(screen.getByTitle('대화 삭제'));

    await waitFor(() => expect(mocked.deleteConversation).toHaveBeenCalledWith('c-1'));
    expect(mocked.conversations).toHaveBeenCalledTimes(2);
  });

  it('삭제 실패는 사유를 보여 준다', async () => {
    mocked.deleteConversation.mockRejectedValue(new Error('down'));
    render(<AiChatPage />);
    await screen.findByText('정산 문의');

    await userEvent.click(screen.getByTitle('대화 삭제'));

    expect(await screen.findByText('대화 삭제에 실패했습니다.')).toBeInTheDocument();
  });
});

describe('AiChatPage — 전송', () => {
  const type = async (text: string) => {
    await userEvent.type(await screen.findByPlaceholderText(/메시지를 입력하세요/), text);
  };

  it('빈 입력은 보낼 수 없다', async () => {
    render(<AiChatPage />);
    await screen.findByPlaceholderText(/메시지를 입력하세요/);

    expect(screen.getByRole('button', { name: '보내기' })).toBeDisabled();
  });

  it('스트리밍 델타를 이어 붙여 답변을 만든다', async () => {
    mocked.chatStream.mockImplementation(async (_m, _c, onDelta) => {
      onDelta('안녕');
      onDelta('하세요');
      return chatResponse();
    });
    render(<AiChatPage />);
    await type('안녕');

    await userEvent.click(screen.getByRole('button', { name: '보내기' }));

    expect(await screen.findByText('안녕하세요')).toBeInTheDocument();
    expect(mocked.chat).not.toHaveBeenCalled();
  });

  it('스트리밍이 안 되는 환경이면 동기 API 로 한 번 폴백한다', async () => {
    mocked.chatStream.mockRejectedValue(new Error('SSE 불가'));
    mocked.chat.mockResolvedValue(chatResponse({ reply: '폴백 응답' }));
    render(<AiChatPage />);
    await type('안녕');

    await userEvent.click(screen.getByRole('button', { name: '보내기' }));

    expect(await screen.findByText('폴백 응답')).toBeInTheDocument();
    expect(mocked.chat).toHaveBeenCalledWith('안녕', undefined);
  });

  it('레이트리밋(429)은 폴백하지 않고 그대로 알린다', async () => {
    mocked.chatStream.mockRejectedValue(new Error('요청이 너무 잦습니다. 잠시 후 다시 시도해 주세요.'));
    render(<AiChatPage />);
    await type('안녕');

    await userEvent.click(screen.getByRole('button', { name: '보내기' }));

    expect(
      await screen.findByText('요청이 너무 잦습니다. 잠시 후 다시 시도해 주세요.'),
    ).toBeInTheDocument();
    expect(mocked.chat).not.toHaveBeenCalled();
  });

  it('실패한 왕복은 화면에서도 되돌리고 입력을 복원한다 (서버에도 저장되지 않는다)', async () => {
    mocked.chatStream.mockRejectedValue(new Error('SSE 불가'));
    mocked.chat.mockRejectedValue(new Error('모델 장애'));
    render(<AiChatPage />);
    await type('정산 문의합니다');

    await userEvent.click(screen.getByRole('button', { name: '보내기' }));

    expect(await screen.findByText('모델 장애')).toBeInTheDocument();
    expect(screen.queryByText('정산 문의합니다')).not.toBeInTheDocument();
    expect(await screen.findByDisplayValue('정산 문의합니다')).toBeInTheDocument();
  });

  it('응답 후에는 그 대화 ID 로 이어 가고 목록을 갱신한다', async () => {
    mocked.chatStream.mockResolvedValue(chatResponse({ conversationId: 'c-9' }));
    render(<AiChatPage />);
    await type('안녕');
    await userEvent.click(screen.getByRole('button', { name: '보내기' }));
    await waitFor(() => expect(mocked.conversations).toHaveBeenCalledTimes(2));

    await type('이어서');
    await userEvent.click(await screen.findByRole('button', { name: '보내기' }));

    await waitFor(() =>
      expect(mocked.chatStream).toHaveBeenLastCalledWith('이어서', 'c-9', expect.any(Function)),
    );
  });
});
