import { describe, it, expect, vi, beforeEach } from 'vitest';
import { dlqApi } from '@/api/dlq';
import api from '@/api/axios';

vi.mock('@/api/axios', () => ({
  default: {
    get: vi.fn(),
    post: vi.fn(),
  },
}));

describe('dlqApi', () => {
  beforeEach(() => vi.resetAllMocks());

  it('DLT 후보 토픽은 서버가 파생해 준 목록을 그대로 쓴다', async () => {
    vi.mocked(api.get).mockResolvedValueOnce({
      data: [{ sourceTopic: 'lemuel.payment.captured', dltTopic: 'lemuel.payment.captured.DLT' }],
    });

    const result = await dlqApi.topics();

    expect(api.get).toHaveBeenCalledWith('/admin/dlq/topics');
    expect(result[0].dltTopic).toBe('lemuel.payment.captured.DLT');
  });

  it('인스펙션은 기본 max 20 으로 읽는다', async () => {
    vi.mocked(api.get).mockResolvedValueOnce({ data: [] });

    await dlqApi.inspect('lemuel.payment.captured.DLT');

    expect(api.get).toHaveBeenCalledWith('/admin/dlq/inspect', {
      params: { topic: 'lemuel.payment.captured.DLT', max: 20 },
    });
  });

  it('인스펙션 건수를 지정할 수 있다', async () => {
    vi.mocked(api.get).mockResolvedValueOnce({
      data: [
        {
          topic: 'lemuel.payment.captured.DLT',
          partition: 0,
          offset: 3,
          key: '42',
          valuePreview: '{"paymentId":42',
          originalTopic: 'lemuel.payment.captured',
          originalOffset: 12,
          exceptionFqcn: 'org.springframework.kafka.listener.ListenerExecutionFailedException',
          exceptionCauseFqcn: 'com.fasterxml.jackson.core.JsonParseException',
          exceptionMessage: 'Unexpected character',
          eventId: 'e-1',
          replayCount: 1,
        },
      ],
    });

    const result = await dlqApi.inspect('lemuel.payment.captured.DLT', 5);

    expect(api.get).toHaveBeenCalledWith('/admin/dlq/inspect', {
      params: { topic: 'lemuel.payment.captured.DLT', max: 5 },
    });
    expect(result[0].replayCount).toBe(1);
  });

  it('재처리는 기본 max 10 으로 원본 토픽에 재발행한다', async () => {
    vi.mocked(api.post).mockResolvedValueOnce({
      data: {
        sourceTopic: 'lemuel.payment.captured',
        dltTopic: 'lemuel.payment.captured.DLT',
        sent: 3,
        skipped: 0,
      },
    });

    const result = await dlqApi.replay('lemuel.payment.captured.DLT');

    expect(api.post).toHaveBeenCalledWith('/admin/dlq/replay', null, {
      params: { topic: 'lemuel.payment.captured.DLT', max: 10 },
    });
    expect(result.sent).toBe(3);
  });

  it('skipped 는 실패가 아니라 멱등 방어가 작동한 수다', async () => {
    vi.mocked(api.post).mockResolvedValueOnce({
      data: {
        sourceTopic: 'lemuel.payment.captured',
        dltTopic: 'lemuel.payment.captured.DLT',
        sent: 0,
        skipped: 2,
      },
    });

    const result = await dlqApi.replay('lemuel.payment.captured.DLT', 2);

    expect(result.sent).toBe(0);
    expect(result.skipped).toBe(2);
  });

  it('Kafka 가 없는 환경은 404 — 장애가 아니라 부재다', async () => {
    vi.mocked(api.get).mockRejectedValueOnce({ response: { status: 404 } });

    await expect(dlqApi.topics()).rejects.toMatchObject({ response: { status: 404 } });
  });
});
