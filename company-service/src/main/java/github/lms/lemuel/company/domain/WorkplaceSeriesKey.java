package github.lms.lemuel.company.domain;

import java.util.regex.Pattern;

/**
 * 사업장 시계열을 지목하는 업무 키 (사업장명 + 사업자등록번호 앞 6자리).
 *
 * <p>단건 상세 복합키({@link WorkplaceKey})에서 기준월을 뺀 2요소다 — 시계열은 같은 사업장의
 * 여러 월을 묶으므로 월이 키가 아니다. 앞 6자리는 전국 단위로 고유하지 않아 <b>사업장명이 바뀌면
 * 시리즈가 단절되는 것을 수용</b>한다(재연결 휴리스틱은 오탐 위험으로 시드에서 기각).
 *
 * <p>검증 순서는 {@link WorkplaceKey} 와 동일하게 <b>누락 → 형식 → 길이</b>, 파라미터 순
 * name → bizRegNoPrefix 로 고정한다.
 */
public record WorkplaceSeriesKey(String workplaceName, String bizRegNoPrefix) {

    /** company_workforce.workplace_name 컬럼 폭. */
    private static final int MAX_WORKPLACE_NAME_LENGTH = 200;

    private static final Pattern BIZ_REG_NO_PREFIX = Pattern.compile("\\d{6}");

    public WorkplaceSeriesKey {
        if (workplaceName == null || workplaceName.isBlank()) {
            throw new IllegalArgumentException("사업장명(name)은 필수입니다");
        }
        if (bizRegNoPrefix == null || !BIZ_REG_NO_PREFIX.matcher(bizRegNoPrefix).matches()) {
            throw new IllegalArgumentException("사업자등록번호 앞 6자리(bizRegNoPrefix)는 숫자 6자리여야 합니다");
        }
        if (workplaceName.length() > MAX_WORKPLACE_NAME_LENGTH) {
            throw new IllegalArgumentException(
                    "사업장명(name)은 " + MAX_WORKPLACE_NAME_LENGTH + "자를 넘을 수 없습니다");
        }
    }

    public static WorkplaceSeriesKey of(String workplaceName, String bizRegNoPrefix) {
        if (workplaceName == null || workplaceName.isBlank()) {
            throw new IllegalArgumentException("사업장명(name)은 필수입니다");
        }
        if (bizRegNoPrefix == null || bizRegNoPrefix.isBlank()) {
            throw new IllegalArgumentException("사업자등록번호 앞 6자리(bizRegNoPrefix)는 필수입니다");
        }
        return new WorkplaceSeriesKey(workplaceName, bizRegNoPrefix);
    }
}
