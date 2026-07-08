package github.lms.lemuel.commondata.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DataSourceNotFoundExceptionTest {

    @Test
    void messageContainsCode() {
        assertTrue(new DataSourceNotFoundException("kasi-rest-days")
                .getMessage().contains("kasi-rest-days"));
    }
}
