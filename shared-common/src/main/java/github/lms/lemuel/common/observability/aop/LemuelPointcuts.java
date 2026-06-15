package github.lms.lemuel.common.observability.aop;

import org.aspectj.lang.annotation.Pointcut;

/**
 * 헥사고날 레이어를 가리키는 공용 {@link Pointcut} 모음.
 *
 * <p>여러 Aspect 가 같은 포인트컷을 재사용하도록 한 곳에 정의한다.
 * 패키지 컨벤션({@code adapter.in.web}, {@code application.service})에 의존하므로
 * 컨벤션이 바뀌면 이 파일만 수정하면 된다.
 */
public final class LemuelPointcuts {

    private LemuelPointcuts() {}

    /** REST 컨트롤러 (인바운드 웹 어댑터) — 요청 단위 추적 진입점. */
    @Pointcut("execution(public * github.lms.lemuel..adapter.in.web..*.*(..))")
    public void webAdapter() {}

    /** 유스케이스 구현 (애플리케이션 서비스) — 비즈니스 로직 경계. */
    @Pointcut("execution(public * github.lms.lemuel..application.service..*.*(..))")
    public void applicationService() {}

    /** Kafka 인바운드 컨슈머 (settlement-service 이벤트 수신). */
    @Pointcut("execution(public * github.lms.lemuel..adapter.in.kafka..*.*(..))")
    public void kafkaConsumer() {}

    /** 추적 대상 전체: 웹 + 서비스 + 카프카 컨슈머. */
    @Pointcut("webAdapter() || applicationService() || kafkaConsumer()")
    public void traceable() {}

    /** Spring {@code @Transactional} 이 메서드 또는 타입에 선언된 지점. */
    @Pointcut("@annotation(org.springframework.transaction.annotation.Transactional) "
            + "|| @within(org.springframework.transaction.annotation.Transactional)")
    public void transactional() {}
}
