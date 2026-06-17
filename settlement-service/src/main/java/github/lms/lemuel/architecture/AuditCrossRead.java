package github.lms.lemuel.architecture;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 대사(audit/reconciliation) 도구가 <b>의도적으로</b> order 원천 DB({@code opslab.*})를 직접 읽음을
 * 표시하는 마커. ADR 0020 Phase 5.5 결정.
 *
 * <p><b>왜 예외인가</b>: 정산 대사의 본질은 "settlement 가 order 가 실제로 일으킨 일을 정확히
 * 반영했는가"를 <b>원천(source of truth)</b>과 대조해 검증하는 것이다. 만약 대사가 settlement 의
 * 로컬 프로젝션(같은 이벤트로 만들어진 데이터)을 읽으면 자기 자신과 비교하는 꼴이 되어 프로젝션
 * 누락·드리프트를 잡지 못한다. 따라서 대사 도구만은 order 원천을 직접 읽는 것이 <b>옳다</b>.
 *
 * <p><b>경계는 유지된다</b>: 직독은 order 코드 import 0(raw SQL, read-only)으로만 이뤄진다.
 * 서빙·조회·리포팅 경로는 모두 settlement 소유 로컬 프로젝션(settlement_*_view)만 읽으며,
 * {@code OpslabReferenceGuardTest} 가 이 애너테이션이 없는 클래스의 {@code opslab.} 참조를 차단한다.
 *
 * <p>장기적으로는 ADR 0020 5.2 의 메트릭 기반 cross-DB 대사(양측 게이지 Prometheus 대조)로
 * 대체 가능하나, 그 전까지 본 직독은 가용성·정확성 측면에서 합리적인 감사 경로다.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface AuditCrossRead {

    /** 이 클래스가 원천 직독을 필요로 하는 이유 (대사 종류). */
    String value();
}
