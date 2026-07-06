package github.lms.lemuel.economics.domain;

/**
 * 존재하지 않는 지표 코드로 조회할 때 발생 — 어댑터에서 404 로 매핑한다.
 *
 * <p>도메인 패키지에 두어(프레임워크 무의존 RuntimeException) 애플리케이션·어댑터가 모두
 * 안쪽으로만 의존하게 한다(ArchUnit 헥사고날 규칙 위반 없음).
 */
public class IndicatorNotFoundException extends RuntimeException {

    public IndicatorNotFoundException(String code) {
        super("지표를 찾을 수 없습니다: " + code);
    }
}
