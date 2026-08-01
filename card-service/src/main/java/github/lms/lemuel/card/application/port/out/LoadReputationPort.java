package github.lms.lemuel.card.application.port.out;

import github.lms.lemuel.card.domain.ReputationGrade;

/**
 * 평판 프로젝션 조회 포트. Task 9 의 한도 산정 산식이 이 등급의 haircut 계수를 그대로 곱한다.
 */
public interface LoadReputationPort {

    /**
     * 셀러의 현재 평판 등급. 프로젝션에 행이 없으면(아직 평판 이벤트 미도착)
     * {@link ReputationGrade#unknownDefault()}(=D) — <b>절대 null 을 반환하지 않는다.</b>
     */
    ReputationGrade gradeOf(String sellerId);
}
