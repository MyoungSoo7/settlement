package github.lms.lemuel.insurance.application.port;

import github.lms.lemuel.insurance.application.port.in.RecordDisclosureDeliveryUseCase.DeliveredDisclosure;
import github.lms.lemuel.insurance.application.port.in.RenderProductDisclosureUseCase.RenderedDisclosure;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PDF 바이트를 품은 포트 record 2종의 값 계약.
 *
 * <p>record 기본 구현은 배열을 참조 동일성으로 비교한다 — 같은 상품설명서를 두 번 렌더링하면
 * 내용이 같아도 다른 값이 되어 비교·중복제거가 조용히 틀어진다. 교부 증빙은 해시로 동일성을 따지는
 * 도메인이라 내용 기준 비교가 맞다. toString 은 PDF 바이트를 로그로 흘리지 않아야 한다.
 */
@DisplayName("상품설명서 포트 record — 배열 값 계약")
class DisclosureRecordContractTest {

    /**
     * equals 의 타입 가드 분기를 덮기 위한 이종 객체.
     *
     * <p>단정문에 문자열 리터럴을 직접 넣으면 "다른 타입끼리 비교하는 단정"으로 잡힌다(S5845).
     * 그 규칙이 노리는 것은 <i>실수로</i> 다른 타입을 비교하는 테스트인데, 여기서는 그게 검증 대상이다.
     * {@code Object} 로 받아 의도를 코드에 드러낸다 — 규칙 회피가 아니라 "일부러 이종을 넣는다"는 선언이다.
     */
    private static final Object FOREIGN_TYPE = "다른 타입";

    @Test
    @DisplayName("RenderedDisclosure: 같은 PDF 내용이면 같은 값이다")
    void rendered_equalsByContent() {
        RenderedDisclosure a = new RenderedDisclosure("P-001", "무배당종신", new byte[]{1, 2}, "abc");
        RenderedDisclosure b = new RenderedDisclosure("P-001", "무배당종신", new byte[]{1, 2}, "abc");

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
    }

    @Test
    @DisplayName("RenderedDisclosure: PDF 내용이 다르면 다른 값이다")
    void rendered_differsByContent() {
        RenderedDisclosure a = new RenderedDisclosure("P-001", "무배당종신", new byte[]{1, 2}, "abc");

        assertThat(a)
                .isNotEqualTo(new RenderedDisclosure("P-001", "무배당종신", new byte[]{9}, "abc"))
                .isNotEqualTo(new RenderedDisclosure("P-002", "무배당종신", new byte[]{1, 2}, "abc"))
                .isNotEqualTo(null)
                .isNotEqualTo(FOREIGN_TYPE);
    }

    @Test
    @DisplayName("RenderedDisclosure: toString 은 PDF 바이트 대신 길이를 남긴다")
    void rendered_toStringHidesBytes() {
        String s = new RenderedDisclosure("P-001", "무배당종신", new byte[]{1, 2}, "abc").toString();

        assertThat(s).contains("2B").contains("P-001").doesNotContain("[B@");
        assertThat(new RenderedDisclosure("P-001", "n", null, "abc").toString()).contains("pdf=null");
    }

    @Test
    @DisplayName("DeliveredDisclosure: 내용 기준 비교 + toString 이 바이트를 감춘다")
    void delivered_contract() {
        DeliveredDisclosure a = new DeliveredDisclosure(null, new byte[]{7, 7, 7});
        DeliveredDisclosure b = new DeliveredDisclosure(null, new byte[]{7, 7, 7});

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a).isNotEqualTo(new DeliveredDisclosure(null, new byte[]{7})).isNotEqualTo(FOREIGN_TYPE);
        assertThat(a.toString()).contains("3B").doesNotContain("[B@");
        assertThat(new DeliveredDisclosure(null, null).toString()).contains("pdf=null");
    }
}
