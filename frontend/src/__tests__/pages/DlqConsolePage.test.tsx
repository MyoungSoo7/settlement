import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, waitFor, fireEvent, within } from '@testing-library/react';
import DlqConsolePage from '@/pages/settlement/DlqConsolePage';
import { ToastProvider } from '@/contexts/ToastContext';
import { dlqApi } from '@/api/dlq';

/**
 * DLQ 콘솔이 지켜야 하는 것.
 *   ① 재처리 전에 원인을 보게 한다 — 예외를 안 보고 되돌려 보내면 같은 실패를 반복한다.
 *   ② skipped 는 실패가 아니라 멱등 방어가 작동한 수다. 실패로 읽히면 운영자가 또 재처리한다.
 *   ③ replayCount > 0 은 "되돌렸다가 또 실패한" 메시지다 — 무한 루프 경고가 필요하다.
 *   ④ Kafka 없는 환경의 404 는 장애가 아니다.
 */
vi.mock('@/api/dlq', () => ({
  dlqApi: { topics: vi.fn(), inspect: vi.fn(), replay: vi.fn() },
}));

const mocked = vi.mocked(dlqApi);

const message = (over: Partial<Awaited<ReturnType<typeof dlqApi.inspect>>[number]> = {}) => ({
  topic: 'lemuel.payment.captured.DLT', partition: 0, offset: 12,
  key: 'pay-1', valuePreview: '{"paymentId":1}',
  originalTopic: 'lemuel.payment.captured', originalOffset: 5,
  exceptionFqcn: 'org.springframework.kafka.listener.ListenerExecutionFailedException',
  exceptionCauseFqcn: 'java.lang.IllegalStateException',
  exceptionMessage: 'settlement already exists',
  eventId: 'evt-1', replayCount: 0, ...over,
});

const renderPage = () => render(<ToastProvider><DlqConsolePage /></ToastProvider>);

const selectTopicAndInspect = async () => {
  // 셀렉트 엘리먼트는 마운트부터 있지만 <option> 은 topics 응답으로 채워진다 — 옵션이 오기 전에
  // change 를 쏘면 값이 목록에 없어 조용히 무시되고, 이어지는 조회가 빈 토픽으로 나간다.
  // 그래서 라벨이 아니라 **옵션이 나타날 때까지** 기다린다.
  await screen.findByRole('option', { name: 'lemuel.payment.captured.DLT' });
  fireEvent.change(screen.getByLabelText('토픽'), { target: { value: 'lemuel.payment.captured.DLT' } });
  fireEvent.click(screen.getByRole('button', { name: '조회' }));
};

let confirmSpy: ReturnType<typeof vi.spyOn>;

beforeEach(() => {
  vi.clearAllMocks();
  mocked.topics.mockResolvedValue([
    { sourceTopic: 'lemuel.order.created', dltTopic: 'lemuel.order.created.DLT' },
    { sourceTopic: 'lemuel.payment.captured', dltTopic: 'lemuel.payment.captured.DLT' },
  ]);
  mocked.inspect.mockResolvedValue([message()]);
  mocked.replay.mockResolvedValue({
    sourceTopic: 'lemuel.payment.captured', dltTopic: 'lemuel.payment.captured.DLT',
    sent: 2, skipped: 1,
  });
  confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(true);
});

afterEach(() => confirmSpy.mockRestore());

describe('DlqConsolePage — 진입과 토픽', () => {
  it('토픽 목록을 서버에서 받아 고르게 한다 — 이름을 손으로 치지 않는다', async () => {
    renderPage();

    await waitFor(() => expect(mocked.topics).toHaveBeenCalled());
    expect(await screen.findByRole('option', { name: 'lemuel.payment.captured.DLT' })).toBeInTheDocument();
  });

  it('진입만으로는 DLT 를 읽지 않는다 — 토픽을 고르는 화면이다', async () => {
    renderPage();

    await waitFor(() => expect(mocked.topics).toHaveBeenCalled());
    expect(mocked.inspect).not.toHaveBeenCalled();
  });

  it('Kafka 가 없는 환경(404)은 장애가 아니라 상태로 알린다', async () => {
    mocked.topics.mockRejectedValue({ response: { status: 404 } });
    renderPage();

    await waitFor(() => expect(screen.getByText(/Kafka 가 비활성/)).toBeInTheDocument());
  });

  it('토픽을 고르지 않고 조회하면 서버를 부르지 않는다', async () => {
    renderPage();
    await waitFor(() => expect(mocked.topics).toHaveBeenCalled());
    fireEvent.click(screen.getByRole('button', { name: '조회' }));

    await waitFor(() => expect(mocked.inspect).not.toHaveBeenCalled());
  });
});

describe('DlqConsolePage — 인스펙션', () => {
  it('실제 원인 예외를 앞세운다 — 래퍼 예외만 보여 주면 판단이 안 된다', async () => {
    renderPage();
    await selectTopicAndInspect();

    await waitFor(() => expect(mocked.inspect).toHaveBeenCalledWith('lemuel.payment.captured.DLT', 20));
    expect(await screen.findByText('java.lang.IllegalStateException')).toBeInTheDocument();
    expect(screen.getByText(/settlement already exists/)).toBeInTheDocument();
  });

  it('원본 토픽·오프셋과 event_id 를 함께 보여 준다 — 추적의 시작점이다', async () => {
    renderPage();
    await selectTopicAndInspect();

    await waitFor(() => expect(screen.getByText(/lemuel.payment.captured #5/)).toBeInTheDocument());
    expect(screen.getByText('evt-1')).toBeInTheDocument();
  });

  it('재처리 이력이 있는 메시지는 반복 실패로 경고한다', async () => {
    mocked.inspect.mockResolvedValue([message({ replayCount: 2 })]);
    renderPage();
    await selectTopicAndInspect();

    await waitFor(() => expect(screen.getByText(/재처리 2회/)).toBeInTheDocument());
    expect(screen.getByText(/같은 원인이면 또 실패/)).toBeInTheDocument();
  });

  it('DLT 가 비어 있으면 빈 상태를 명시한다', async () => {
    mocked.inspect.mockResolvedValue([]);
    renderPage();
    await selectTopicAndInspect();

    await waitFor(() => expect(screen.getByText(/쌓인 메시지가 없습니다/)).toBeInTheDocument());
  });
});

describe('DlqConsolePage — 재처리', () => {
  it('조회 전에는 재처리 버튼을 주지 않는다 — 원인을 보고 누르는 순서다', async () => {
    renderPage();
    await waitFor(() => expect(mocked.topics).toHaveBeenCalled());

    expect(screen.queryByRole('button', { name: '재처리' })).not.toBeInTheDocument();
  });

  it('재처리는 확인을 받고, 확인창에 원본 토픽으로 되돌아간다는 사실을 적는다', async () => {
    confirmSpy.mockReturnValue(false);
    renderPage();
    await selectTopicAndInspect();
    await waitFor(() => expect(screen.getByRole('button', { name: '재처리' })).toBeInTheDocument());
    fireEvent.click(screen.getByRole('button', { name: '재처리' }));

    await waitFor(() => expect(mocked.replay).not.toHaveBeenCalled());
    expect(String(confirmSpy.mock.calls[0][0])).toMatch(/lemuel.payment.captured/);
  });

  it('확인하면 재처리하고 결과를 보여 준다', async () => {
    renderPage();
    await selectTopicAndInspect();
    await waitFor(() => expect(screen.getByRole('button', { name: '재처리' })).toBeInTheDocument());
    fireEvent.click(screen.getByRole('button', { name: '재처리' }));

    await waitFor(() => expect(mocked.replay).toHaveBeenCalledWith('lemuel.payment.captured.DLT', 10));
    // 토스트에도 같은 문구가 떠서 결과 패널로 범위를 좁힌다.
    expect(within(await screen.findByTestId('replay-result')).getByText(/재발행 2건/)).toBeInTheDocument();
  });

  it('skipped 를 실패가 아니라 멱등 차단으로 설명한다 — 아니면 운영자가 또 재처리한다', async () => {
    renderPage();
    await selectTopicAndInspect();
    await waitFor(() => expect(screen.getByRole('button', { name: '재처리' })).toBeInTheDocument());
    fireEvent.click(screen.getByRole('button', { name: '재처리' }));

    await waitFor(() => expect(screen.getByText(/이미 처리된 건이라 건너뜀 1건/)).toBeInTheDocument());
  });

  it('재처리 후 남은 DLT 를 다시 읽는다 — 화면이 옛 목록을 들고 있지 않게', async () => {
    renderPage();
    await selectTopicAndInspect();
    await waitFor(() => expect(mocked.inspect).toHaveBeenCalledTimes(1));
    fireEvent.click(await screen.findByRole('button', { name: '재처리' }));

    await waitFor(() => expect(mocked.inspect).toHaveBeenCalledTimes(2));
  });

  it('재처리 실패는 화면에 남긴다', async () => {
    mocked.replay.mockRejectedValue({ response: { data: { message: '브로커 연결 실패' } } });
    renderPage();
    await selectTopicAndInspect();
    await waitFor(() => expect(screen.getByRole('button', { name: '재처리' })).toBeInTheDocument());
    fireEvent.click(screen.getByRole('button', { name: '재처리' }));

    await waitFor(() => expect(screen.getByText(/브로커 연결 실패/)).toBeInTheDocument());
  });
});
