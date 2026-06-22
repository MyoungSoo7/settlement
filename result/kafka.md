# Kafka / Outbox 적용 확인

## 결론

이 프로젝트에는 Transactional Outbox 패턴이 적용되어 있다.

특히 `order-service`의 결제 이벤트 발행 경로는 비즈니스 트랜잭션 안에서 이벤트를 직접 Kafka로 보내지 않고, 먼저 `outbox_events` 테이블에 저장한다. 이후 별도 폴러가 PENDING 이벤트를 읽어 Kafka로 발행하고, 성공 시 PUBLISHED 상태로 변경한다.

## 적용된 흐름

### 1. 결제 트랜잭션 안에서 Outbox 저장

`CapturePaymentUseCase`는 클래스 레벨에 `@Transactional`이 붙어 있다.

파일:

- `order-service/src/main/java/github/lms/lemuel/payment/application/CapturePaymentUseCase.java`

핵심 흐름:

```java
PaymentDomain savedPaymentDomain = savePaymentPort.save(paymentDomain);
updateOrderStatusPort.updateOrderStatus(savedPaymentDomain.getOrderId(), "PAID");

publishEventPort.publishPaymentCaptured(
        savedPaymentDomain.getId(),
        savedPaymentDomain.getOrderId(),
        savedPaymentDomain.getAmount());
```

즉, 결제 상태 저장, 주문 상태 변경, `PaymentCaptured` 이벤트 기록이 같은 트랜잭션 경계 안에서 수행된다.

### 2. 이벤트는 Kafka 직접 발행이 아니라 outbox_events에 저장

파일:

- `order-service/src/main/java/github/lms/lemuel/payment/adapter/out/event/OutboxBackedEventPublisher.java`

`OutboxBackedEventPublisher`는 `PublishEventPort` 구현체이며, 다음 이벤트들을 outbox에 저장한다.

- `PaymentCreated`
- `PaymentAuthorized`
- `PaymentCaptured`
- `PaymentRefunded`

핵심 구조:

```java
OutboxEvent event = OutboxEvent.pending(
        "Payment",
        String.valueOf(paymentId),
        eventType,
        json,
        traceParent
);
saveOutboxEventPort.save(event);
```

### 3. outbox_events 테이블

파일:

- `order-service/src/main/resources/db/migration/V28__create_outbox_events.sql`
- `order-service/src/main/resources/db/migration/V40__outbox_traceparent.sql`

주요 컬럼:

- `id`
- `aggregate_type`
- `aggregate_id`
- `event_type`
- `event_id`
- `payload`
- `status`
- `retry_count`
- `last_error`
- `created_at`
- `published_at`
- `trace_parent`

`event_id`에는 unique index가 걸려 있어 이벤트 식별과 중복 방어에 사용된다.

## Kafka 발행

### 1. Outbox 폴러

파일:

- `shared-common/src/main/java/github/lms/lemuel/common/outbox/application/service/OutboxPublisherScheduler.java`

`OutboxPublisherScheduler`가 주기적으로 PENDING 이벤트를 조회한다.

```java
@Scheduled(fixedDelayString = "${app.outbox.polling-delay-ms:2000}")
public void publishPendingEvents() {
    List<OutboxEvent> pending = loadOutboxEventPort.findPending(BATCH_SIZE);
    ...
}
```

ShedLock도 적용되어 있어 여러 인스턴스에서 중복 폴링되는 상황을 줄인다.

### 2. 단일 이벤트 발행 트랜잭션

파일:

- `shared-common/src/main/java/github/lms/lemuel/common/outbox/application/service/OutboxSingleEventPublisher.java`

각 이벤트는 `REQUIRES_NEW` 트랜잭션으로 처리된다.

```java
@Transactional(propagation = Propagation.REQUIRES_NEW)
public void publish(OutboxEvent event) {
    publishExternalEventPort.publish(event);
    event.markPublished();
    saveOutboxEventPort.save(event);
}
```

실패하면 `retryCount`가 증가하고, 10회 이상 실패하면 `FAILED` 상태가 된다. `FAILED` 상태가 되면 DLQ 발행도 수행한다.

### 3. Kafka Publisher

파일:

- `shared-common/src/main/java/github/lms/lemuel/common/outbox/adapter/out/event/KafkaOutboxPublisher.java`

Kafka 활성화 조건:

```java
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
```

토픽 매핑 규칙:

```text
Payment + PaymentCaptured -> lemuel.payment.captured
Payment + PaymentRefunded -> lemuel.payment.refunded
```

메시지 key는 `aggregateId`를 사용한다. 같은 결제 aggregate의 이벤트 순서를 보장하기 위한 구조다.

Kafka header에는 다음 값들이 들어간다.

- `event_id`
- `event_type`
- `aggregate_type`
- `traceparent`

## Consumer 멱등 처리

### 1. settlement-service의 결제 이벤트 소비자

파일:

- `settlement-service/src/main/java/github/lms/lemuel/settlement/adapter/in/kafka/PaymentEventKafkaConsumer.java`

`lemuel.payment.captured` 토픽을 소비한다.

```java
@KafkaListener(
        topics = "${app.kafka.topic.payment-captured}",
        groupId = "lemuel-settlement",
        containerFactory = "kafkaListenerContainerFactory"
)
@Transactional
public void onPaymentCaptured(...)
```

### 2. processed_events 테이블

파일:

- `order-service/src/main/resources/db/migration/V29__create_processed_events.sql`

컨슈머는 `consumer_group + event_id`를 기준으로 이미 처리한 이벤트인지 확인한다.

```sql
PRIMARY KEY (consumer_group, event_id)
```

이를 통해 Kafka의 at-least-once 특성으로 같은 메시지가 재전달되어도 정산 중복 생성 위험을 줄인다.

## 적용 범위

현재 확실히 연결된 흐름:

```text
Payment capture
  -> payment 저장
  -> order PAID 변경
  -> outbox_events INSERT
  -> OutboxPublisherScheduler
  -> Kafka lemuel.payment.captured
  -> PaymentEventKafkaConsumer
  -> settlement 생성
  -> processed_events 기록
```

## 주의할 점

`PaymentRefunded`도 outbox에 저장되고 Kafka topic 설정도 존재한다.

관련 설정:

- `lemuel.payment.refunded`

하지만 현재 코드 기준으로 settlement-service에서 `PaymentRefunded`를 소비하는 `@KafkaListener`는 확인되지 않는다. 따라서 환불 이벤트는 outbox 발행까지는 연결되어 있지만, 정산 조정 소비자까지는 아직 완전히 연결되지 않은 상태로 보인다.

## 추가 Outbox

settlement-service 내부에는 Kafka를 거치지 않는 ledger 전용 로컬 Outbox도 있다.

파일:

- `settlement-service/src/main/java/github/lms/lemuel/ledger/application/service/LedgerOutboxService.java`
- `settlement-service/src/main/java/github/lms/lemuel/ledger/adapter/in/batch/LedgerOutboxPoller.java`

이 흐름은 정산/환불 트랜잭션 안에서 원장 작업을 enqueue하고, 로컬 폴러가 나중에 처리하는 방식이다.

