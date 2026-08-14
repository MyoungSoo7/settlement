package github.lms.lemuel.account.adapter.in.kafka;

import github.lms.lemuel.account.domain.exception.AccountDomainException;
import github.lms.lemuel.common.config.kafka.NonRetryableConsumerExceptions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * account 도메인 예외를 공용 Kafka 에러 핸들러의 "즉시 DLT" 목록에 기여한다.
 *
 * <p>account 도메인은 OO 게이트상 generic {@code IllegalArgumentException} 대신 타입 예외
 * ({@link AccountDomainException}) 를 던진다. 공용 배선의 기본 3종에는 이 타입이 없으므로,
 * 명시 기여가 없으면 입력 계약 위반(양수 금액·소수 자릿수·차대 분리 등)이 재시도 3회를
 * 무의미하게 돌고 나서야 DLT 로 간다. 계약 위반은 재시도로 복구되지 않으므로 즉시 격리한다.
 *
 * <p>account 는 전사 GL 의 유일한 집계자이고 소비 전용이라 재발행 경로가 없다 — 분개 이벤트
 * 유실은 시산표 영구 결손으로 직결되므로 DLT 격리가 특히 중요하다.
 */
@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class AccountNonRetryableExceptions implements NonRetryableConsumerExceptions {

    @Override
    public List<Class<? extends Exception>> exceptions() {
        return List.of(AccountDomainException.class);
    }
}
