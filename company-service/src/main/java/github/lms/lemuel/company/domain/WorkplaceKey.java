package github.lms.lemuel.company.domain;

import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;

/**
 * 사업장 스냅샷 1건을 지목하는 업무 복합키 (사업장명 + 사업자등록번호 앞 6자리 + 기준월).
 *
 * <p>내부 시퀀스 id 는 공개 계약에 넣지 않는다. 실제 데이터에 {@code (유)케이비에프에스"전주밥상
 * 다잡수소!"} 처럼 따옴표·느낌표가 든 사업장명이 있어 path variable 로는 안전하지 않으므로
 * query parameter 로 받는다 — 이 타입은 그렇게 받은 원문 문자열을 검증해 키로 굳히는 지점이다.
 *
 * <p>검증 순서는 <b>누락 → 형식 → 길이</b> 로 고정한다. 여러 파라미터가 동시에 잘못됐을 때
 * 어떤 오류가 보고될지 구현마다 달라지지 않게 하려는 것이다(사업장명 → 사업자번호 → 기준월 순).
 */
public record WorkplaceKey(String workplaceName, String bizRegNoPrefix, YearMonth snapshotMonth) {

    /** company_workforce.workplace_name 컬럼 폭. */
    private static final int MAX_WORKPLACE_NAME_LENGTH = 200;

    private static final Pattern BIZ_REG_NO_PREFIX = Pattern.compile("\\d{6}");
    private static final Pattern SNAPSHOT_MONTH = Pattern.compile("\\d{4}-\\d{2}");

    public WorkplaceKey {
        if (workplaceName == null || workplaceName.isBlank()) {
            throw new IllegalArgumentException("사업장명(name)은 필수입니다");
        }
        if (bizRegNoPrefix == null || !BIZ_REG_NO_PREFIX.matcher(bizRegNoPrefix).matches()) {
            throw new IllegalArgumentException("사업자등록번호 앞 6자리(bizRegNoPrefix)는 숫자 6자리여야 합니다");
        }
        if (snapshotMonth == null) {
            throw new IllegalArgumentException("기준월(snapshotMonth)은 필수입니다");
        }
        if (workplaceName.length() > MAX_WORKPLACE_NAME_LENGTH) {
            throw new IllegalArgumentException(
                    "사업장명(name)은 " + MAX_WORKPLACE_NAME_LENGTH + "자를 넘을 수 없습니다");
        }
    }

    public static WorkplaceKey of(String workplaceName, String bizRegNoPrefix, String snapshotMonth) {
        if (workplaceName == null || workplaceName.isBlank()) {
            throw new IllegalArgumentException("사업장명(name)은 필수입니다");
        }
        if (bizRegNoPrefix == null || bizRegNoPrefix.isBlank()) {
            throw new IllegalArgumentException("사업자등록번호 앞 6자리(bizRegNoPrefix)는 필수입니다");
        }
        if (snapshotMonth == null || snapshotMonth.isBlank()) {
            throw new IllegalArgumentException("기준월(snapshotMonth)은 필수입니다");
        }
        return new WorkplaceKey(workplaceName, bizRegNoPrefix, parseMonth(snapshotMonth));
    }

    private static YearMonth parseMonth(String snapshotMonth) {
        // YearMonth.parse 는 '2026-06-01' 을 거부하지만 '+2026-06' 같은 이형은 통과시키므로 형식을 먼저 고정한다.
        if (!SNAPSHOT_MONTH.matcher(snapshotMonth).matches()) {
            throw new IllegalArgumentException("기준월(snapshotMonth)은 YYYY-MM 형식이어야 합니다: " + snapshotMonth);
        }
        try {
            return YearMonth.parse(snapshotMonth);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("기준월(snapshotMonth)이 올바른 연월이 아닙니다: " + snapshotMonth);
        }
    }
}
