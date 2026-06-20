# Callback Pattern

이 프로젝트에는 프레임워크가 제어권을 가지고 있다가, 필요한 시점에 사용자 코드를 다시 호출하는 콜백 패턴이 사용된 부분이 있다.

## 패턴 개요

콜백 패턴은 어떤 공통 처리 흐름을 가진 객체가 있고, 호출자는 특정 시점에 실행할 작업을 함수나 객체 형태로 전달하는 방식이다.

이 프로젝트에서는 Spring의 `TransactionTemplate`이 공통 흐름을 담당하고, 실제 비즈니스 로직은 콜백으로 넘긴다.

## 사용된 부분

### 1. `DecreaseVariantStockService`

- 경로: `order-service/src/main/java/github/lms/lemuel/product/application/service/DecreaseVariantStockService.java`
- 핵심: `transactionTemplate.execute(status -> { ... })`

```java
ProductVariant result = transactionTemplate.execute(status -> {
    ProductVariant variant = loadPort.loadById(variantId)
            .orElseThrow(() -> new IllegalArgumentException(
                    "ProductVariant not found: " + variantId));
    variant.decreaseStock(quantity);
    return savePort.save(variant);
});
```

설명:
- `TransactionTemplate`가 트랜잭션 시작, 커밋, 롤백 같은 공통 제어 흐름을 관리한다.
- 호출자는 실제로 트랜잭션 안에서 실행할 작업만 람다로 전달한다.
- 전달된 람다는 `TransactionTemplate`가 적절한 시점에 호출하므로 콜백이다.

## 왜 콜백 패턴인가

다음 조건이 충족된다.

- 호출자가 실행할 동작을 외부에서 전달한다.
- 실제 실행 시점의 제어권은 `TransactionTemplate`가 가진다.
- 공통 처리 흐름은 템플릿 객체가 맡고, 가변 로직만 콜백으로 분리한다.

## 구분해서 볼 부분

### `RateLimitPolicy`의 `Function`

- 경로: `shared-common/src/main/java/github/lms/lemuel/common/ratelimit/RateLimitPolicy.java`

`Function<RateLimitKeySource, String>`를 저장해서 적용하는 구조가 있지만, 이것은 일반적인 콜백이라기보다 전략(Strategy) 성격이 더 강하다.

이유:
- 즉시 호출되는 정책 함수를 들고 있는 구조에 가깝다.
- `TransactionTemplate`처럼 실행 흐름 전체를 소유한 객체가 나중에 특정 타이밍에 불러주는 전형적인 콜백 구조와는 결이 다르다.

## 정리

이 프로젝트에서 명확하게 확인되는 콜백 패턴 사용 예시는 다음이다.

- `DecreaseVariantStockService`의 `transactionTemplate.execute(status -> { ... })`

즉, 이 프로젝트는 Spring의 트랜잭션 템플릿 API를 통해 콜백 패턴을 활용하고 있다.
