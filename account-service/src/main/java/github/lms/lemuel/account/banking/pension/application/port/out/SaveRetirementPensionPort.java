package github.lms.lemuel.account.banking.pension.application.port.out;

import github.lms.lemuel.account.banking.pension.domain.RetirementPension;

/**
 * 퇴직연금 계약 적재 아웃바운드 포트.
 *
 * <p>거래는 append-only 다 — 구현체는 아직 식별자가 없는(=신규) 거래만 덧붙이고 기존 행은 건드리지
 * 않는다. {@code UNIQUE(pension_id, seq)} 가 그 규약의 최종 방어선이다.
 */
public interface SaveRetirementPensionPort {

    /** 저장 후 식별자가 채워진 계약을 돌려준다(신규 가입 시 id 부여). */
    RetirementPension save(RetirementPension pension);
}
