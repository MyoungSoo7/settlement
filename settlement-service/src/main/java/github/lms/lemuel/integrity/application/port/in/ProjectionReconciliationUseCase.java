package github.lms.lemuel.integrity.application.port.in;

import github.lms.lemuel.integrity.domain.ProjectionDiffReport;

import java.time.LocalDate;

/**
 * INV-12 프로젝션 행 diff 유스케이스 (Integrity Suite Phase C) — 읽기 전용.
 *
 * <p>설계: docs/design/settlement-integrity-suite.md §4 Phase C. order 원천 결제 키 집합과
 * settlement 프로젝션(settlement_*_view) 키 집합을 id 단위로 대사해 누락/고아/금액불일치 행을
 * <b>특정</b>한다. 하이브리드(체크섬 1차 스크리닝 → 불일치 시 키 diff)로 데이터량을 방어한다.
 * 탐지까지만 담당 — 정정은 projectionbackfill 재적재로만 한다.
 */
public interface ProjectionReconciliationUseCase {

    /**
     * 해당 날짜 프로젝션 행 diff. {@code entity} 는 현재 "payment" 만 지원(기본값),
     * {@code limitOverride} 는 보고할 id 상위 N 건 상한(기본 100).
     */
    ProjectionDiffReport reconcileProjection(LocalDate date, String entity, Integer limitOverride);
}
