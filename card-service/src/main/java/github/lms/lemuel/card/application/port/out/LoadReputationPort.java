package github.lms.lemuel.card.application.port.out;

import github.lms.lemuel.card.domain.ReputationGrade;

/**
 * 셀러 평판 등급 조회 포트 — 한도 산정({@code CardLimitPolicy})의 haircut 입력.
 */
public interface LoadReputationPort {

    /** 프로젝션에 없으면 {@link ReputationGrade#unknownDefault()} 를 돌려준다(보수적 기본). */
    ReputationGrade gradeOf(String sellerId);
}
