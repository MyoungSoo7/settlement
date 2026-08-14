import React, { useCallback, useEffect, useState } from 'react';
import { dlqApi, type DltTopic, type DlqMessage, type ReplayResult } from '@/api/dlq';
import { apiErrorMessage, apiErrorStatus } from '@/lib/apiError';
import { useToast } from '@/contexts/useToast';
import Spinner from '@/components/Spinner';

/**
 * DLQ 재처리 콘솔 (ADMIN 전용).
 *
 * <p>컨슈머가 끝내 처리하지 못한 이벤트는 `<원본토픽>.DLT` 에 쌓인다. 그 상태로 두면 정산이 조용히
 * 비는 것이라 되돌려 보내야 하는데, <b>원인을 보지 않고 되돌리면 같은 실패를 반복</b>한다. 그래서
 * 이 화면은 조회 전에는 재처리 버튼을 아예 만들지 않는다 — 순서 자체를 화면 구조로 강제한다.
 *
 * <p>재처리 결과의 {@code skipped} 는 실패가 아니라 멱등 방어(processed_events)가 작동한 수다.
 * 실패로 읽히면 운영자가 같은 재처리를 반복하므로 문구로 못박는다.
 *
 * <p>{@code replayCount > 0} 인 메시지는 이미 되돌렸다가 또 떨어진 것이다. 원인이 그대로면 또
 * 실패하므로 경고한다(서버는 무한 루프를 막지 않는다 — 판단은 운영자 몫이다).
 */

const DlqConsolePage: React.FC = () => {
  const { showToast } = useToast();

  const [topics, setTopics] = useState<DltTopic[]>([]);
  const [topicError, setTopicError] = useState<string | null>(null);
  const [selected, setSelected] = useState('');
  const [max, setMax] = useState(20);

  const [messages, setMessages] = useState<DlqMessage[] | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [result, setResult] = useState<ReplayResult | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    dlqApi.topics()
      .then(setTopics)
      .catch((err) => {
        setTopics([]);
        // 404 = app.kafka.enabled=false 라 컨트롤러 빈이 없는 환경. 장애가 아니다.
        setTopicError(apiErrorStatus(err) === 404
          ? '이 환경은 Kafka 가 비활성이라 DLQ 기능이 없습니다.'
          : apiErrorMessage(err, '토픽 목록을 불러오지 못했습니다.'));
      });
  }, []);

  const inspect = useCallback(async (topic: string) => {
    setLoading(true);
    setError(null);
    try {
      setMessages(await dlqApi.inspect(topic, max));
    } catch (err) {
      setMessages(null);
      setError(apiErrorMessage(err, 'DLT 를 읽지 못했습니다.'));
    } finally {
      setLoading(false);
    }
  }, [max]);

  const handleInspect = async () => {
    if (!selected) {
      showToast('토픽을 선택하세요.', 'warning');
      return;
    }
    setResult(null);
    await inspect(selected);
  };

  const handleReplay = async () => {
    const source = topics.find((t) => t.dltTopic === selected)?.sourceTopic ?? selected;
    if (!window.confirm(
      `${selected} 의 메시지를 최대 10건 ${source} 로 되돌려 보냅니다.\n`
      + '컨슈머가 처음부터 다시 처리합니다. 이미 처리된 건은 멱등 방어로 건너뜁니다.\n'
      + '원인을 고치지 않았다면 같은 메시지가 다시 DLT 로 떨어집니다. 진행할까요?')) return;

    setBusy(true);
    try {
      const replayed = await dlqApi.replay(selected, 10);
      setResult(replayed);
      showToast(`재발행 ${replayed.sent}건 · 건너뜀 ${replayed.skipped}건`, 'success');
      // 되돌려 보낸 뒤 남은 것을 다시 읽는다 — 옛 목록을 들고 있으면 두 번 재처리한다.
      await inspect(selected);
    } catch (err) {
      setError(apiErrorMessage(err, '재처리에 실패했습니다.'));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-bold text-gray-900">DLQ 재처리</h1>
        <p className="text-sm text-gray-500 mt-1">
          컨슈머가 처리하지 못해 <code>.DLT</code> 로 떨어진 이벤트를 확인하고 원본 토픽으로 되돌려
          보냅니다. 재처리는 멱등이라 이미 처리된 건은 건너뜁니다.
        </p>
      </div>

      {topicError && (
        <p className="text-sm text-amber-700 bg-amber-50 border border-amber-200 rounded-lg px-3 py-2">
          {topicError}
        </p>
      )}

      <div className="bg-white rounded-xl border border-gray-200 p-4 flex flex-wrap items-end gap-3">
        <div>
          <label htmlFor="dlt-topic" className="block text-xs font-medium text-gray-600 mb-1">토픽</label>
          <select id="dlt-topic" value={selected} className="input w-96"
            onChange={(e) => setSelected(e.target.value)}>
            <option value="">선택하세요</option>
            {topics.map((t) => (
              <option key={t.dltTopic} value={t.dltTopic}>{t.dltTopic}</option>
            ))}
          </select>
        </div>
        <div>
          <label htmlFor="dlt-max" className="block text-xs font-medium text-gray-600 mb-1">조회 건수</label>
          <input id="dlt-max" type="number" min={1} max={100} value={max}
            onChange={(e) => setMax(Number(e.target.value) || 20)} className="input w-28" />
        </div>
        <button onClick={handleInspect}
          className="px-4 py-2 bg-blue-600 text-white text-sm font-semibold rounded-lg hover:bg-blue-700">
          조회
        </button>
      </div>

      {result && (
        <div data-testid="replay-result" className="bg-white rounded-xl border border-gray-200 px-4 py-3 text-sm">
          <span className="font-semibold text-gray-900">재발행 {result.sent}건</span>
          <span className="mx-2 text-gray-300">·</span>
          <span className="text-gray-600">이미 처리된 건이라 건너뜀 {result.skipped}건</span>
          <p className="text-xs text-gray-500 mt-1">
            건너뛴 건은 실패가 아닙니다 — <code>processed_events</code> 가 중복 처리를 막은 수입니다.
          </p>
        </div>
      )}

      {error && <p className="py-6 text-center text-red-600">{error}</p>}
      {loading && <div className="py-12 flex justify-center"><Spinner size="lg" message="DLT 읽는 중..." /></div>}

      {messages && !loading && messages.length === 0 && (
        <p className="py-10 text-center text-gray-400">이 토픽에 쌓인 메시지가 없습니다.</p>
      )}

      {messages && !loading && messages.length > 0 && (
        <div className="space-y-3">
          <div className="flex items-center justify-between">
            <h3 className="font-bold text-gray-900">DLT 메시지 {messages.length}건</h3>
            <button onClick={handleReplay} disabled={busy}
              className="px-4 py-2 bg-gray-800 text-white text-sm font-semibold rounded-lg hover:bg-gray-900 disabled:opacity-50">
              재처리
            </button>
          </div>

          {messages.map((m) => (
            <div key={`${m.partition}-${m.offset}`} className="bg-white rounded-xl border border-gray-200 p-4">
              <div className="flex flex-wrap items-center gap-2">
                <span className="text-xs font-mono px-2 py-0.5 rounded bg-gray-100 text-gray-600">
                  {m.partition}/{m.offset}
                </span>
                <span className="font-semibold text-red-700">{m.exceptionCauseFqcn ?? m.exceptionFqcn ?? '원인 미상'}</span>
                {m.replayCount > 0 && (
                  <span className="text-xs font-semibold px-2 py-0.5 rounded-full bg-amber-100 text-amber-800">
                    재처리 {m.replayCount}회 — 같은 원인이면 또 실패합니다
                  </span>
                )}
              </div>

              {m.exceptionMessage && (
                <p className="mt-1 text-sm text-gray-700 break-all">{m.exceptionMessage}</p>
              )}

              <div className="mt-2 grid grid-cols-1 md:grid-cols-3 gap-x-6 gap-y-1 text-sm text-gray-600">
                <span>원본 {m.originalTopic ?? '-'} #{m.originalOffset ?? '-'}</span>
                <span>event_id <b className="font-mono text-xs">{m.eventId ?? '-'}</b></span>
                <span>key <span className="font-mono text-xs">{m.key ?? '-'}</span></span>
              </div>

              {m.valuePreview && (
                <pre className="mt-2 text-xs bg-gray-50 rounded-lg p-2 overflow-x-auto text-gray-600">
                  {m.valuePreview}
                </pre>
              )}
            </div>
          ))}
        </div>
      )}
    </div>
  );
};

export default DlqConsolePage;
