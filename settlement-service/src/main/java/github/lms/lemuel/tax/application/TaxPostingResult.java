package github.lms.lemuel.tax.application;

import github.lms.lemuel.tax.domain.TaxCalculation;

/**
 * 세무 전기 결과 — 배치/관리자 경로가 결과를 구별해 후속 처리(로그·응답)를 결정한다.
 *
 * <p>미등록 셀러는 예외 대신 {@link Outcome#PENDING_NO_PROFILE} 로 <b>보류</b>를 표현한다(배치가 청크
 * 트랜잭션을 깨지 않도록). 관리자 단건 트리거는 이 값을 그대로 응답에 노출해 등록을 유도한다.
 *
 * @param outcome    전기 결과 구분
 * @param entriesPosted 전기된 세무 전표 row 수
 * @param calculation 산출된 세무 계산(보류/미확정 시 null)
 */
public record TaxPostingResult(Outcome outcome, int entriesPosted, TaxCalculation calculation) {

    public enum Outcome {
        /** 세무 전표 신규 전기 완료. */
        POSTED,
        /** 이미 전기됨(멱등 skip). */
        ALREADY_POSTED,
        /** 셀러 세무 프로필 미등록 — 산출 보류. */
        PENDING_NO_PROFILE,
        /** 정산이 DONE 이 아니어서 전기 보류. */
        SKIPPED_NOT_DONE
    }

    public static TaxPostingResult posted(int rows, TaxCalculation calc) {
        return new TaxPostingResult(Outcome.POSTED, rows, calc);
    }

    public static TaxPostingResult alreadyPosted() {
        return new TaxPostingResult(Outcome.ALREADY_POSTED, 0, null);
    }

    public static TaxPostingResult pendingNoProfile() {
        return new TaxPostingResult(Outcome.PENDING_NO_PROFILE, 0, null);
    }

    public static TaxPostingResult skippedNotDone() {
        return new TaxPostingResult(Outcome.SKIPPED_NOT_DONE, 0, null);
    }
}
