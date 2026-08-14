package github.lms.lemuel.account.banking.pension.domain;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 퇴직연금 제도 유형 — 근로자퇴직급여보장법이 정한 세 가지가 전부다.
 *
 * <p>제도가 곧 규칙의 집합이라, 부담금 주체·사업장 필수 여부·중도인출 허용 여부를 <b>상수 속성으로</b>
 * 들고 있다. 이렇게 두면 "DB 인데 가입자 부담금", "IRP 인데 사업장명" 같은 조합이 애그리게이트 곳곳의
 * if 문이 아니라 이 한 곳에서 판정된다.
 *
 * <ul>
 *   <li>{@code DB} (확정급여) — 급여가 사전 확정, 적립·운용 책임이 사용자에게 있다.
 *       그래서 가입자 부담금이 성립하지 않고, <b>중도인출도 제도적으로 불가</b>하다.</li>
 *   <li>{@code DC} (확정기여) — 사용자 부담금이 가입자 계정으로 확정 납입되고 운용 책임은 가입자에게 있다.
 *       가입자 추가납입이 가능하고 법정 사유 중도인출이 가능하다.</li>
 *   <li>{@code IRP} (개인형) — 가입자 본인 계정이라 사업장이 없고 본인 납입만 있다. 법정 사유 중도인출 가능.</li>
 * </ul>
 */
public enum PensionScheme {

    /** 확정급여형 — 사업장 필수, 사용자 부담금만, 중도인출 불가. */
    DB(true, false, ContributionSource.EMPLOYER),

    /** 확정기여형 — 사업장 필수, 사용자 부담금 + 가입자 추가납입, 법정 사유 중도인출 가능. */
    DC(true, true, ContributionSource.EMPLOYER, ContributionSource.EMPLOYEE),

    /** 개인형퇴직연금 — 사업장 없음, 가입자 본인 납입만, 법정 사유 중도인출 가능. */
    IRP(false, true, ContributionSource.EMPLOYEE);

    private final boolean employerNameRequired;
    private final boolean midWithdrawalPermitted;
    private final Set<ContributionSource> allowedContributionSources;

    PensionScheme(boolean employerNameRequired, boolean midWithdrawalPermitted,
                  ContributionSource... allowedContributionSources) {
        this.employerNameRequired = employerNameRequired;
        this.midWithdrawalPermitted = midWithdrawalPermitted;
        this.allowedContributionSources =
                Collections.unmodifiableSet(EnumSet.copyOf(List.of(allowedContributionSources)));
    }

    /** 사업장명이 필수인가(DB·DC) — false 면 사업장명을 <b>받아서도 안 된다</b>(IRP). */
    public boolean requiresEmployerName() {
        return employerNameRequired;
    }

    /** 법정 사유 중도인출이 제도적으로 허용되는가 — DB 는 항상 false. */
    public boolean permitsMidWithdrawal() {
        return midWithdrawalPermitted;
    }

    /** 이 제도에서 해당 주체의 부담금 납입이 성립하는가. */
    public boolean permitsContributionFrom(ContributionSource source) {
        return allowedContributionSources.contains(source);
    }

    /** 허용 납입 주체(불변 집합) — 진단·응답 노출용. */
    public Set<ContributionSource> allowedContributionSources() {
        return allowedContributionSources;
    }
}
