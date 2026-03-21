import api from './axios';

const BASE = '/api/rag';

export interface RagMessage {
  role: string;
  content: string;
  createdAt: string;
}

export interface RagConversation {
  sessionId: string;
  messages: RagMessage[];
}

export const ragApi = {
  createSession: async (): Promise<{ sessionId: string }> => {
    const res = await api.post(`${BASE}/sessions`);
    return res.data;
  },

  getConversation: async (sessionId: string): Promise<RagConversation> => {
    const res = await api.get(`${BASE}/sessions/${sessionId}`);
    return res.data;
  },

  queryStream: (
    sessionId: string,
    question: string,
    onToken: (token: string) => void,
    onDone: () => void,
    onError: (error: string) => void
  ) => {
    const token = localStorage.getItem('access_token');
    const baseURL = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8088';

    fetch(`${baseURL}${BASE}/query`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${token}`,
      },
      body: JSON.stringify({ sessionId, question }),
    }).then(async (response) => {
      if (!response.ok) {
        onError(`서버 오류: ${response.status}`);
        return;
      }
      const reader = response.body?.getReader();
      if (!reader) {
        onError('스트림을 읽을 수 없습니다.');
        return;
      }

      const decoder = new TextDecoder();
      let buffer = '';

      while (true) {
        const { done, value } = await reader.read();
        if (done) break;

        buffer += decoder.decode(value, { stream: true });
        const lines = buffer.split('\n');
        buffer = lines.pop() || '';

        for (const line of lines) {
          if (line.startsWith('data:')) {
            const jsonStr = line.slice(5).trim();
            if (!jsonStr) continue;
            try {
              const event = JSON.parse(jsonStr);
              if (event.done) {
                onDone();
              } else if (event.token) {
                onToken(event.token);
              }
            } catch {
              // 파싱 실패 무시
            }
          }
        }
      }
      onDone();
    }).catch((err) => {
      onError(err.message || '네트워크 오류');
    });
  },

  indexAll: async (): Promise<{ indexed: number; skipped: number; failed: number }> => {
    const res = await api.post(`${BASE}/index`);
    return res.data;
  },
};
