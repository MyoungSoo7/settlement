import api from './axios';

/**
 * DLQ(Dead Letter Topic) 운영 API — `/admin/dlq/**` (**ADMIN 전용**).
 *
 * <p>컨슈머가 끝내 처리하지 못한 이벤트가 `<원본토픽>.DLT` 로 떨어진다. 그대로 두면 정산이
 * 조용히 비는 것이라, 원인을 보고 고친 뒤 원본 토픽으로 되돌려 보내는 것이 이 API 다.
 *
 * <p>재처리는 <b>멱등</b>이다 — `processed_events` 가 이미 처리된 event_id 를 막으므로 같은 메시지를
 * 두 번 보내도 중복 정산이 생기지 않는다. 응답의 `skipped` 는 실패가 아니라 그 방어가 작동한 수다.
 *
 * <p>`app.kafka.enabled=false` 환경에서는 컨트롤러 빈 자체가 없어 404 가 온다 — 장애가 아니라
 * "이 환경에는 Kafka 가 없다"는 뜻이다.
 */

export interface DltTopic {
  sourceTopic: string;
  dltTopic: string;
}

export interface DlqMessage {
  topic: string;
  partition: number;
  offset: number;
  key: string | null;
  /** 원문 앞부분(최대 500자) — 전체가 아니다 */
  valuePreview: string | null;
  originalTopic: string | null;
  originalOffset: number | null;
  /** 보통 ListenerExecutionFailedException 래퍼 */
  exceptionFqcn: string | null;
  /** 실제 원인 — 운영자가 볼 값 */
  exceptionCauseFqcn: string | null;
  exceptionMessage: string | null;
  eventId: string | null;
  /** 몇 번 재처리됐는지 — 0 보다 크면 되돌려 보냈다가 또 실패한 메시지다 */
  replayCount: number;
}

export interface ReplayResult {
  sourceTopic: string;
  dltTopic: string;
  /** 원본 토픽으로 다시 보낸 수 */
  sent: number;
  /** 멱등 방어에 걸려 건너뛴 수 — 실패가 아니다 */
  skipped: number;
}

export const dlqApi = {
  /** DLT 후보 토픽 — 서버가 구독 목록에서 파생해 준다(이름을 화면이 조립하지 않는다) */
  topics: async (): Promise<DltTopic[]> =>
    (await api.get<DltTopic[]>('/admin/dlq/topics')).data,

  /** 인스펙션 — commit 없이 읽기만 한다 */
  inspect: async (topic: string, max = 20): Promise<DlqMessage[]> =>
    (await api.get<DlqMessage[]>('/admin/dlq/inspect', { params: { topic, max } })).data,

  /** 재처리 — 원본 토픽으로 재발행. 멱등 */
  replay: async (topic: string, max = 10): Promise<ReplayResult> =>
    (await api.post<ReplayResult>('/admin/dlq/replay', null, { params: { topic, max } })).data,
};
