package github.lms.lemuel.account.integration;

import github.lms.lemuel.AccountServiceApplication;
import github.lms.lemuel.account.application.port.in.AccountQueryUseCase;
import github.lms.lemuel.account.application.port.in.SellerFundingQuery.SellerFunding;
import github.lms.lemuel.account.application.port.in.RecordAccountEntryUseCase;
import github.lms.lemuel.account.domain.AccountEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 셀러 재원 조회가 실체화 잔액과 일치하는지 실 PG 로 증명한다.
 *
 * <p>card-service 의 한도는 이 값에서 직접 유도되므로, 여기서 어긋나면 한도가 통째로 틀린다.
 * 특히 홀드백 해제(재분류)가 총 재원을 바꾸지 않아야 한다는 점을 고정한다 —
 * 해제는 HOLDBACK_PAYABLE → SELLER_PAYABLE 이동일 뿐 합계 불변이다.
 */
@SpringBootTest(
        classes = AccountServiceApplication.class,
        properties = {
                "app.kafka.enabled=false",
                "app.jwt.secret=integration-test-secret-key-32-bytes-min-OK"
        }
)
@Testcontainers
@EnabledIf(value = "isDockerAvailable", disabledReason = "Docker is not available")
class SellerFundingIT {

    static boolean isDockerAvailable() {
        try {
            DockerClientFactory.instance().client();
            return true;
        } catch (Throwable ex) {
            return false;
        }
    }

    @Container
    static final PostgreSQLContainer<?> ACCOUNT_DB = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("lemuel_account").withUsername("test").withPassword("test");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", ACCOUNT_DB::getJdbcUrl);
        r.add("spring.datasource.username", ACCOUNT_DB::getUsername);
        r.add("spring.datasource.password", ACCOUNT_DB::getPassword);
        r.add("POSTGRES_USER", ACCOUNT_DB::getUsername);
        r.add("POSTGRES_PASSWORD", ACCOUNT_DB::getPassword);
    }

    @Autowired RecordAccountEntryUseCase recordAccountEntryUseCase;
    @Autowired AccountQueryUseCase accountQueryUseCase;

    @Test
    @DisplayName("재원 = SELLER_PAYABLE + HOLDBACK_PAYABLE, 홀드백 해제는 총액을 바꾸지 않는다")
    void fundingIsPayablePlusHoldback_andReleaseIsTotalNeutral() {
        String seller = "940001";
        recordAccountEntryUseCase.record(
                AccountEntry.settlementCreatedImmediate(seller, "S1", new BigDecimal("100000")));
        recordAccountEntryUseCase.record(
                AccountEntry.settlementHoldbackRecognized(seller, "S1", new BigDecimal("30000")));

        SellerFunding before = accountQueryUseCase.sellerFunding(seller);
        assertThat(before.sellerPayable()).isEqualByComparingTo("100000");
        assertThat(before.holdbackPayable()).isEqualByComparingTo("30000");
        BigDecimal totalBefore = before.sellerPayable().add(before.holdbackPayable());

        // 유보 해제 — 지급 가능으로 재분류될 뿐 총 재원은 그대로여야 한다.
        recordAccountEntryUseCase.record(
                AccountEntry.holdbackReleased(seller, "S1", new BigDecimal("20000")));

        SellerFunding after = accountQueryUseCase.sellerFunding(seller);
        assertThat(after.sellerPayable()).isEqualByComparingTo("120000");
        assertThat(after.holdbackPayable()).isEqualByComparingTo("10000");
        assertThat(after.sellerPayable().add(after.holdbackPayable()))
                .isEqualByComparingTo(totalBefore);
    }

    @Test
    @DisplayName("실지급은 재원을 줄인다")
    void payoutReducesFunding() {
        String seller = "940002";
        recordAccountEntryUseCase.record(
                AccountEntry.settlementCreatedImmediate(seller, "S9", new BigDecimal("80000")));
        recordAccountEntryUseCase.record(
                AccountEntry.payoutCompleted(seller, "P9", new BigDecimal("50000")));

        SellerFunding funding = accountQueryUseCase.sellerFunding(seller);
        assertThat(funding.sellerPayable()).isEqualByComparingTo("30000");
    }

    @Test
    @DisplayName("잔액 행이 없는 셀러는 0")
    void unknownSellerIsZero() {
        SellerFunding funding = accountQueryUseCase.sellerFunding("940099");
        assertThat(funding.sellerPayable()).isEqualByComparingTo("0");
        assertThat(funding.holdbackPayable()).isEqualByComparingTo("0");
    }
}
