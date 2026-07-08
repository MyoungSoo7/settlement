import React, { useCallback, useEffect, useRef, useState } from 'react';
import {
  aiChatApi,
  type ConversationSummary,
} from '@/api/aichat';
import Card from '@/components/Card';
import Spinner from '@/components/Spinner';

interface ChatBubble {
  role: 'USER' | 'ASSISTANT';
  content: string;
  pending?: boolean;   // 스트리밍 수신 중
}

const AiChatPage: React.FC = () => {
  const [conversations, setConversations] = useState<ConversationSummary[]>([]);
  const [activeId, setActiveId] = useState<string | null>(null);
  const [messages, setMessages] = useState<ChatBubble[]>([]);
  const [input, setInput] = useState('');
  const [sending, setSending] = useState(false);
  const [loadingHistory, setLoadingHistory] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const bottomRef = useRef<HTMLDivElement>(null);

  const refreshConversations = useCallback(async () => {
    try {
      const list = await aiChatApi.conversations(0, 30);
      setConversations(list.content);
    } catch {
      // 목록 실패는 채팅 자체를 막지 않는다
    }
  }, []);

  useEffect(() => {
    refreshConversations();
  }, [refreshConversations]);

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages]);

  const openConversation = async (id: string) => {
    setActiveId(id);
    setError(null);
    setLoadingHistory(true);
    try {
      const detail = await aiChatApi.conversation(id);
      setMessages(detail.messages.map((m) => ({ role: m.role, content: m.content })));
    } catch (err: any) {
      setError(err.response?.data?.message || '대화를 불러오지 못했습니다.');
    } finally {
      setLoadingHistory(false);
    }
  };

  const startNewConversation = () => {
    setActiveId(null);
    setMessages([]);
    setError(null);
  };

  const removeConversation = async (id: string) => {
    try {
      await aiChatApi.deleteConversation(id);
      if (activeId === id) startNewConversation();
      await refreshConversations();
    } catch (err: any) {
      setError(err.response?.data?.message || '대화 삭제에 실패했습니다.');
    }
  };

  const send = async (e: React.FormEvent) => {
    e.preventDefault();
    const message = input.trim();
    if (!message || sending) return;

    setInput('');
    setError(null);
    setSending(true);
    setMessages((prev) => [
      ...prev,
      { role: 'USER', content: message },
      { role: 'ASSISTANT', content: '', pending: true },
    ]);

    const appendDelta = (delta: string) =>
      setMessages((prev) => {
        const next = [...prev];
        const last = next[next.length - 1];
        next[next.length - 1] = { ...last, content: last.content + delta };
        return next;
      });

    try {
      let result;
      try {
        result = await aiChatApi.chatStream(message, activeId ?? undefined, appendDelta);
      } catch (streamErr: any) {
        // 스트리밍 경로 실패 시(프록시가 SSE 를 못 넘기는 환경 등) 동기 API 로 1회 폴백
        if (streamErr?.message?.includes('잦습니다')) throw streamErr;   // 429 는 폴백해도 같은 결과
        const fallback = await aiChatApi.chat(message, activeId ?? undefined);
        appendDelta(fallback.reply);
        result = fallback;
      }
      setMessages((prev) => {
        const next = [...prev];
        const last = next[next.length - 1];
        next[next.length - 1] = { role: 'ASSISTANT', content: last.content || result.reply };
        return next;
      });
      setActiveId(result.conversationId);
      await refreshConversations();
    } catch (err: any) {
      setMessages((prev) => prev.slice(0, -2));   // 실패한 왕복은 서버에도 저장되지 않는다 — 화면도 원복
      setInput(message);                           // 재전송 유도
      setError(err.response?.data?.message || err.message || 'AI 응답에 실패했습니다.');
    } finally {
      setSending(false);
    }
  };

  return (
    <div className="min-h-screen bg-slate-50 py-8 px-4 sm:px-6 lg:px-8">
      <div className="max-w-6xl mx-auto space-y-6">
        <div>
          <h1 className="text-3xl font-bold text-slate-950">🤖 AI 도우미</h1>
          <p className="mt-2 text-sm text-slate-600">
            Lemuel 플랫폼 사용법과 일반 지식을 답하는 대화형 AI 입니다. 대화 맥락이 유지됩니다.
            (개인 주문·정산 데이터 조회는 곧 연동 예정)
          </p>
        </div>

        {error && (
          <div className="rounded-md border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700">{error}</div>
        )}

        <div className="grid grid-cols-1 gap-6 lg:grid-cols-[300px_1fr]">
          {/* 대화 목록 */}
          <Card title="대화 목록">
            <button
              onClick={startNewConversation}
              className="mb-3 w-full rounded-md bg-slate-900 px-3 py-2 text-sm font-semibold text-white hover:bg-slate-700"
            >
              + 새 대화
            </button>
            <div className="space-y-1">
              {conversations.map((c) => (
                <div
                  key={c.id}
                  className={`group flex items-center justify-between gap-1 rounded-md border px-3 py-2 ${
                    activeId === c.id ? 'border-slate-900 bg-slate-100' : 'border-slate-200 hover:bg-slate-50'
                  }`}
                >
                  <button onClick={() => openConversation(c.id)} className="min-w-0 flex-1 text-left">
                    <p className="truncate text-sm font-medium text-black">{c.title}</p>
                    <p className="text-xs text-slate-500">{c.messageCount}개 메시지</p>
                  </button>
                  <button
                    onClick={() => removeConversation(c.id)}
                    title="대화 삭제"
                    className="invisible rounded px-1.5 py-0.5 text-xs text-slate-400 hover:bg-red-50 hover:text-red-600 group-hover:visible"
                  >
                    ✕
                  </button>
                </div>
              ))}
              {conversations.length === 0 && (
                <p className="py-4 text-center text-sm text-slate-400">아직 대화가 없습니다.</p>
              )}
            </div>
          </Card>

          {/* 채팅창 */}
          <Card>
            <div className="flex h-[560px] flex-col">
              <div className="flex-1 space-y-3 overflow-y-auto pr-1">
                {loadingHistory ? (
                  <div className="flex justify-center py-16"><Spinner /></div>
                ) : messages.length === 0 ? (
                  <div className="py-16 text-center text-sm text-slate-400">
                    <p className="text-4xl">💬</p>
                    <p className="mt-3">무엇이든 물어보세요. 예: &ldquo;정산 주기가 어떻게 되나요?&rdquo;</p>
                  </div>
                ) : (
                  messages.map((m, i) => (
                    <div key={i} className={`flex ${m.role === 'USER' ? 'justify-end' : 'justify-start'}`}>
                      <div
                        className={`max-w-[80%] whitespace-pre-wrap rounded-2xl px-4 py-2.5 text-sm leading-relaxed ${
                          m.role === 'USER'
                            ? 'bg-slate-900 text-white'
                            : 'border border-slate-200 bg-white text-black'
                        }`}
                      >
                        {m.content}
                        {m.pending && m.content === '' && <span className="animate-pulse">답변 생성 중…</span>}
                        {m.pending && m.content !== '' && <span className="animate-pulse">▌</span>}
                      </div>
                    </div>
                  ))
                )}
                <div ref={bottomRef} />
              </div>

              <form onSubmit={send} className="mt-4 flex gap-2 border-t border-slate-100 pt-4">
                <input
                  type="text"
                  value={input}
                  onChange={(e) => setInput(e.target.value)}
                  maxLength={4000}
                  placeholder="메시지를 입력하세요 (분당 5회 제한)"
                  disabled={sending}
                  className="min-w-0 flex-1 rounded-md border border-slate-300 px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-slate-500 disabled:bg-slate-50"
                />
                <button
                  type="submit"
                  disabled={sending || !input.trim()}
                  className="rounded-md bg-slate-900 px-5 py-2 text-sm font-semibold text-white hover:bg-slate-700 disabled:opacity-40"
                >
                  {sending ? '응답 중…' : '보내기'}
                </button>
              </form>
            </div>
          </Card>
        </div>
      </div>
    </div>
  );
};

export default AiChatPage;
