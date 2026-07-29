package github.lms.lemuel.company.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.YearMonth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WorkplaceKeyTest {

    @Test
    @DisplayName("복합키는 사업장명·사업자번호앞6자리·기준월로 구성된다 — 내부 id 는 계약에 없다")
    void buildsFromCompositeKey() {
        WorkplaceKey key = WorkplaceKey.of("주식회사에고이즘", "866759", "2026-06");

        assertEquals("주식회사에고이즘", key.workplaceName());
        assertEquals("866759", key.bizRegNoPrefix());
        assertEquals(YearMonth.of(2026, 6), key.snapshotMonth());
    }

    @Test
    @DisplayName("따옴표·느낌표가 든 사업장명도 그대로 받는다 — 실제 원본에 존재")
    void acceptsQuotedWorkplaceName() {
        WorkplaceKey key = WorkplaceKey.of("(유)케이비에프에스\"전주밥상 다잡수소!\"", "418851", "2026-06");

        assertEquals("(유)케이비에프에스\"전주밥상 다잡수소!\"", key.workplaceName());
    }

    @Test
    @DisplayName("검증 순서는 누락 → 형식 → 길이 — 필수 누락이 형식 위반보다 먼저 보고된다")
    void validatesMissingBeforeFormat() {
        assertEquals("사업장명(name)은 필수입니다",
                assertThrows(IllegalArgumentException.class, () -> WorkplaceKey.of(null, "abc", "bad")).getMessage());
        assertEquals("사업자등록번호 앞 6자리(bizRegNoPrefix)는 필수입니다",
                assertThrows(IllegalArgumentException.class, () -> WorkplaceKey.of("회사", "  ", "bad")).getMessage());
        assertEquals("기준월(snapshotMonth)은 필수입니다",
                assertThrows(IllegalArgumentException.class, () -> WorkplaceKey.of("회사", "866759", null)).getMessage());
    }

    @Test
    @DisplayName("사업자번호 앞자리는 숫자 6자리만, 기준월은 YYYY-MM 만 허용한다")
    void rejectsMalformedFormats() {
        assertThrows(IllegalArgumentException.class, () -> WorkplaceKey.of("회사", "86675", "2026-06"));
        assertThrows(IllegalArgumentException.class, () -> WorkplaceKey.of("회사", "8667590", "2026-06"));
        assertThrows(IllegalArgumentException.class, () -> WorkplaceKey.of("회사", "86675a", "2026-06"));
        assertThrows(IllegalArgumentException.class, () -> WorkplaceKey.of("회사", "866759", "2026-13"));
        assertThrows(IllegalArgumentException.class, () -> WorkplaceKey.of("회사", "866759", "202606"));
        assertThrows(IllegalArgumentException.class, () -> WorkplaceKey.of("회사", "866759", "2026-06-01"));
    }

    @Test
    @DisplayName("사업장명 길이 상한은 컬럼 폭(200)과 같다")
    void rejectsTooLongWorkplaceName() {
        String maxLength = "가".repeat(200);
        assertEquals(maxLength, WorkplaceKey.of(maxLength, "866759", "2026-06").workplaceName());

        assertThrows(IllegalArgumentException.class, () -> WorkplaceKey.of("가".repeat(201), "866759", "2026-06"));
    }
}
