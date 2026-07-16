package github.lms.lemuel.reconciliation.adapter.`in`.web

import github.lms.lemuel.reconciliation.domain.DiscrepancyType
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

import github.lms.lemuel.reconciliation.adapter.out.source.SampleActualSource
import github.lms.lemuel.reconciliation.adapter.out.source.SampleExpectedSource
import github.lms.lemuel.reconciliation.application.ReconciliationService

/**
 * Slice test for the web adapter. Imports the real service + sample sources so
 * the demo runs end-to-end through the controller.
 */
@WebMvcTest(ReconciliationController::class)
@Import(ReconciliationService::class, SampleExpectedSource::class, SampleActualSource::class)
class ReconciliationControllerTest(@Autowired val mockMvc: MockMvc) {

    @Test
    fun `demo returns a report containing every discrepancy type`() {
        mockMvc.get("/reconciliation/demo")
            .andExpect { status { isOk() } }
            .andExpect { jsonPath("$.matchedCount") { value(2) } } // pay_1001 + pay_1005
            .andExpect { jsonPath("$.byType.${DiscrepancyType.MISSING}") { value(1) } }
            .andExpect { jsonPath("$.byType.${DiscrepancyType.EXTRA}") { value(1) } }
            .andExpect { jsonPath("$.byType.${DiscrepancyType.AMOUNT_MISMATCH}") { value(1) } }
            .andExpect { jsonPath("$.byType.${DiscrepancyType.STATUS_MISMATCH}") { value(1) } }
            .andExpect { jsonPath("$.discrepancyCount") { value(4) } }
    }

    @Test
    fun `run reconciles supplied sets and honors tolerance`() {
        val body = """
            {
              "expected": [
                {"businessKey":"k1","amountKrw":1000,"status":"PAID"},
                {"businessKey":"k2","amountKrw":2000,"status":"PAID"}
              ],
              "actual": [
                {"businessKey":"k1","amountKrw":1000,"status":"PAID"},
                {"businessKey":"k2","amountKrw":2500,"status":"PAID"}
              ],
              "toleranceKrw": 1
            }
        """.trimIndent()

        mockMvc.post("/reconciliation/run") {
            contentType = MediaType.APPLICATION_JSON
            content = body
        }
            .andExpect { status { isOk() } }
            .andExpect { jsonPath("$.matchedCount") { value(1) } }
            .andExpect { jsonPath("$.discrepancyCount") { value(1) } }
            .andExpect { jsonPath("$.discrepancies[0].type") { value("AMOUNT_MISMATCH") } }
    }
}
