package github.lms.lemuel.commondata.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DataRecordTest {

    @Test
    @DisplayName("정상 생성")
    void createsWithValues() {
        Instant now = Instant.now();
        DataRecord record = new DataRecord(1L, "kasi-rest-days", "20260101|1",
                "{\"dateName\":\"1월1일\"}", now);

        assertEquals("kasi-rest-days", record.sourceCode());
        assertEquals("20260101|1", record.recordKey());
        assertEquals(now, record.collectedAt());
    }

    @Test
    @DisplayName("sourceCode/recordKey/payload 누락 거부")
    void rejectsMissingRequiredFields() {
        assertThrows(IllegalArgumentException.class,
                () -> new DataRecord(null, " ", "key", "{}", null));
        assertThrows(IllegalArgumentException.class,
                () -> new DataRecord(null, "src", null, "{}", null));
        assertThrows(IllegalArgumentException.class,
                () -> new DataRecord(null, "src", "key", "", null));
    }
}
