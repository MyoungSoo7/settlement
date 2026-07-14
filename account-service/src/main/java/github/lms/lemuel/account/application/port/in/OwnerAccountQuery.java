package github.lms.lemuel.account.application.port.in;

import github.lms.lemuel.account.domain.AccountEntry;
import github.lms.lemuel.account.domain.AccountSummary;
import github.lms.lemuel.account.domain.OwnerType;

import java.util.List;

/**
 * owner 단위 계정 조회 역할 — 잔액 요약 + 분개 페이지 (화면 축).
 *
 * <p>{@link AccountQueryUseCase} 의 응집 축 중 하나. owner 상세 화면만 그리는 소비처는 이 역할만
 * 의존하면 된다(ISP).
 */
public interface OwnerAccountQuery {

    /** owner 별 계정 잔액 요약. */
    AccountSummary accountSummary(OwnerType ownerType, String ownerId);

    /** owner 별 분개 페이지. */
    EntryPage entries(OwnerType ownerType, String ownerId, int page, int size);

    /** 분개 페이지 결과. */
    record EntryPage(List<AccountEntry> content, long totalElements, int page, int size) { }
}
