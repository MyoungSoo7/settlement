package github.lms.lemuel.settlement.adapter.in.web;

import github.lms.lemuel.settlement.adapter.in.web.response.SettlementAggregationsResponse;
import github.lms.lemuel.settlement.adapter.in.web.response.SettlementPageResponse;
import github.lms.lemuel.settlement.adapter.in.web.response.SettlementSearchItemResponse;
import github.lms.lemuel.settlement.adapter.out.persistence.SettlementSearchJdbcRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("SettlementSearchController")
class SettlementSearchControllerTest {

    @Mock
    private SettlementSearchJdbcRepository searchRepository;

    @InjectMocks
    private SettlementSearchController controller;

    private SettlementPageResponse emptyPage() {
        return new SettlementPageResponse(
            List.of(), 0L, 0, 0, 20,
            new SettlementAggregationsResponse(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, Map.of())
        );
    }

    private SettlementPageResponse pageWithItem() {
        SettlementSearchItemResponse item = new SettlementSearchItemResponse(
            1L, 10L, 100L,
            "user@test.com", "상품A",
            new BigDecimal("100000"), BigDecimal.ZERO, new BigDecimal("97000"),
            "DONE", false, LocalDate.of(2026, 1, 15)
        );
        return new SettlementPageResponse(
            List.of(item), 1L, 1, 0, 20,
            new SettlementAggregationsResponse(
                new BigDecimal("100000"), BigDecimal.ZERO, new BigDecimal("97000"),
                Map.of("DONE", 1L)
            )
        );
    }

    @Nested
    @DisplayName("search 메서드")
    class SearchMethod {

        @Test
        @DisplayName("파라미터를 그대로 repository에 전달하고 200 OK를 반환한다")
        void search_delegatesToRepository_returns200() {
            given(searchRepository.search(null, null, null, null, null, null, 0, 20, "createdAt", "DESC"))
                .willReturn(emptyPage());

            ResponseEntity<SettlementPageResponse> response = controller.search(
                null, null, null, null, null, null, 0, 20, "createdAt", "DESC");

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getTotalElements()).isEqualTo(0L);
            then(searchRepository).should().search(null, null, null, null, null, null, 0, 20, "createdAt", "DESC");
        }

        @Test
        @DisplayName("날짜 범위 파라미터를 repository에 전달한다")
        void search_withDateRange_passesParamsToRepository() {
            given(searchRepository.search(null, null, null, null, "2026-01-01", "2026-01-31", 0, 20, "createdAt", "DESC"))
                .willReturn(emptyPage());

            controller.search(null, null, null, null, "2026-01-01", "2026-01-31", 0, 20, "createdAt", "DESC");

            then(searchRepository).should().search(null, null, null, null, "2026-01-01", "2026-01-31", 0, 20, "createdAt", "DESC");
        }

        @Test
        @DisplayName("status 필터 파라미터를 repository에 전달한다")
        void search_withStatus_passesStatusToRepository() {
            given(searchRepository.search(null, null, null, "DONE", null, null, 0, 20, "createdAt", "DESC"))
                .willReturn(pageWithItem());

            ResponseEntity<SettlementPageResponse> response =
                controller.search(null, null, null, "DONE", null, null, 0, 20, "createdAt", "DESC");

            assertThat(response.getBody().getTotalElements()).isEqualTo(1L);
            assertThat(response.getBody().getSettlements()).hasSize(1);
            assertThat(response.getBody().getSettlements().get(0).getStatus()).isEqualTo("DONE");
        }

        @Test
        @DisplayName("페이지네이션 파라미터를 repository에 전달한다")
        void search_withPagination_passesPageParams() {
            given(searchRepository.search(null, null, null, null, null, null, 2, 10, "amount", "ASC"))
                .willReturn(emptyPage());

            controller.search(null, null, null, null, null, null, 2, 10, "amount", "ASC");

            then(searchRepository).should().search(null, null, null, null, null, null, 2, 10, "amount", "ASC");
        }

        @Test
        @DisplayName("ordererName, productName, isRefunded 파라미터를 repository에 전달한다")
        void search_withAllFilters_passesAllToRepository() {
            given(searchRepository.search("user@test.com", "상품A", true, "DONE", "2026-01-01", "2026-01-31", 0, 20, "createdAt", "DESC"))
                .willReturn(pageWithItem());

            ResponseEntity<SettlementPageResponse> response =
                controller.search("user@test.com", "상품A", true, "DONE", "2026-01-01", "2026-01-31", 0, 20, "createdAt", "DESC");

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            then(searchRepository).should().search("user@test.com", "상품A", true, "DONE", "2026-01-01", "2026-01-31", 0, 20, "createdAt", "DESC");
        }

        @Test
        @DisplayName("aggregations 정보가 응답에 포함된다")
        void search_includesAggregationsInResponse() {
            given(searchRepository.search(any(), any(), any(), any(), any(), any(),
                anyInt(), anyInt(), any(), any()))
                .willReturn(pageWithItem());

            ResponseEntity<SettlementPageResponse> response =
                controller.search(null, null, null, null, null, null, 0, 20, "createdAt", "DESC");

            SettlementAggregationsResponse agg = response.getBody().getAggregations();
            assertThat(agg).isNotNull();
            assertThat(agg.getTotalAmount()).isEqualByComparingTo("100000");
            assertThat(agg.getTotalFinalAmount()).isEqualByComparingTo("97000");
            assertThat(agg.getStatusCounts()).containsEntry("DONE", 1L);
        }
    }
}