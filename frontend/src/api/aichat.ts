import api from './axios';

/** AI 챗봇 (ai-service, /api/ai/**) — JWT 필수(USER 이상) */

export interface ChatUsage {
  inputTokens: number | null;
  outputTokens: number | null;
}

export interface ChatResponse {
  conversationId: string;
  reply: string;
  model: string;
  usage: ChatUsage;
}

export interface ConversationSummary {
  id: string;
  title: string;
  messageCount: number;
  lastMessageAt: string;
  createdAt: string;
}

export interface ConversationListResponse {
  content: ConversationSummary[];
  page: number;
  size: number;
  totalElements: number;
}

export interface ChatMessageView {
  role: 'USER' | 'ASSISTANT';
  content: string;
  model: string | null;
  createdAt: string;
}

export interface ConversationDetail {
  id: string;
  title: string;
  createdAt: string;
  messages: ChatMessageView[];
}

export const aiChatApi = {
  /** 동기 채팅 (스트리밍 실패 시 폴백). POST /api/ai/chat */
  chat: async (message: string, conversationId?: string): Promise<ChatResponse> => {
    const res = await api.post<ChatResponse>(
      '/api/ai/chat',
      { message, conversationId: conversationId ?? null },
      { timeout: 65000 },   // LLM 응답은 기본 10s 타임아웃보다 오래 걸린다
    );
    return res.data;
  },

  /**
   * SSE 스트리밍 채팅. POST /api/ai/chat/stream
   * delta 이벤트마다 onDelta, 완료 시 done payload 반환. 서버가 error 이벤트를 보내면 throw.
   * (axios 는 브라우저에서 응답 스트리밍이 안 되므로 fetch 로 직접 파싱)
   */
  chatStream: async (
    message: string,
    conversationId: string | undefined,
    onDelta: (delta: string) => void,
  ): Promise<ChatResponse> => {
    const token = localStorage.getItem('access_token');
    const res = await fetch('/api/ai/chat/stream', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
      },
      body: JSON.stringify({ message, conversationId: conversationId ?? null }),
    });
    if (!res.ok || !res.body) {
      const body = await res.text().catch(() => '');
      let msg = 'AI 응답에 실패했습니다.';
      try {
        msg = JSON.parse(body).message ?? msg;
      } catch { /* 본문이 JSON 이 아니면 기본 메시지 */ }
      throw new Error(res.status === 429 ? '요청이 너무 잦습니다. 잠시 후 다시 시도해 주세요.' : msg);
    }

    const reader = res.body.getReader();
    const decoder = new TextDecoder();
    let buffer = '';
    let done: ChatResponse | null = null;

    const handleEvent = (raw: string) => {
      let event = 'message';
      const dataLines: string[] = [];
      for (const line of raw.split('\n')) {
        if (line.startsWith('event:')) event = line.slice(6).trim();
        else if (line.startsWith('data:')) dataLines.push(line.slice(5).replace(/^ /, ''));
      }
      const data = dataLines.join('\n');
      if (event === 'delta') onDelta(data);
      else if (event === 'done') done = JSON.parse(data) as ChatResponse;
      else if (event === 'error') throw new Error(data || 'AI 응답에 실패했습니다.');
    };

    for (;;) {
      const { value, done: eof } = await reader.read();
      if (eof) break;
      buffer += decoder.decode(value, { stream: true });
      let idx;
      while ((idx = buffer.indexOf('\n\n')) >= 0) {
        const raw = buffer.slice(0, idx);
        buffer = buffer.slice(idx + 2);
        if (raw.trim()) handleEvent(raw);
      }
    }
    if (!done) throw new Error('AI 응답이 중단되었습니다. 다시 시도해 주세요.');
    return done;
  },

  /** 내 대화 목록. GET /api/ai/conversations */
  conversations: async (page = 0, size = 20): Promise<ConversationListResponse> => {
    const res = await api.get<ConversationListResponse>(`/api/ai/conversations?page=${page}&size=${size}`);
    return res.data;
  },

  /** 대화 상세(메시지 전체). GET /api/ai/conversations/{id} */
  conversation: async (id: string): Promise<ConversationDetail> => {
    const res = await api.get<ConversationDetail>(`/api/ai/conversations/${id}`);
    return res.data;
  },

  /** 대화 삭제. DELETE /api/ai/conversations/{id} */
  deleteConversation: async (id: string): Promise<void> => {
    await api.delete(`/api/ai/conversations/${id}`);
  },
};
