package github.lms.lemuel.insurance.domain;

import github.lms.lemuel.insurance.domain.exception.InvalidDisclosureException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 상품설명서 교부 이력 도메인 테스트 — 채널 불변식 + 해시 형식 검증.
 */
@DisplayName("DisclosureDelivery — 완전판매 증빙")
class DisclosureDeliveryTest {

    private static final String SHA256 = "a".repeat(64);

    @Test
    @DisplayName("FC 교부 — deliveryId 자동 채번, 필드 보존")
    void recordsFcDelivery() {
        DisclosureDelivery d = DisclosureDelivery.record(
                "11111111-1111-1111-1111-111111111111", "PROD-1", SalesChannel.FC,
                "fc-100", null, "홍길동", SHA256);

        assertThat(d.getDeliveryId()).isNotBlank();
        assertThat(d.getSalesChannel()).isEqualTo(SalesChannel.FC);
        assertThat(d.getDeliveredBy()).isEqualTo("fc-100");
        assertThat(d.getContractorName()).isEqualTo("홍길동");
        assertThat(d.getDocumentSha256()).isEqualTo(SHA256);
        assertThat(d.getId()).isNull();
    }

    @Test
    @DisplayName("사전 상담 교부는 청약 ID 없이 허용된다")
    void allowsDeliveryWithoutApplication() {
        DisclosureDelivery d = DisclosureDelivery.record(
                null, "PROD-1", SalesChannel.FC, "fc-100", null, "홍길동", SHA256);

        assertThat(d.getApplicationId()).isNull();
    }

    @Test
    @DisplayName("BANCA 교부는 판매 은행 필수 — 없으면 거부")
    void rejectsBancaWithoutBank() {
        assertThatThrownBy(() -> DisclosureDelivery.record(
                null, "PROD-1", SalesChannel.BANCA, "teller-1", null, "홍길동", SHA256))
                .isInstanceOf(InvalidDisclosureException.class);
    }

    @Test
    @DisplayName("FC 교부에 판매 은행이 지정되면 거부")
    void rejectsFcWithBank() {
        assertThatThrownBy(() -> DisclosureDelivery.record(
                null, "PROD-1", SalesChannel.FC, "fc-100", "BANK-KB", "홍길동", SHA256))
                .isInstanceOf(InvalidDisclosureException.class);
    }

    @Test
    @DisplayName("해시는 소문자 hex 64자만 허용한다 — 짧거나 대문자면 거부")
    void rejectsMalformedSha256() {
        assertThatThrownBy(() -> DisclosureDelivery.record(
                null, "PROD-1", SalesChannel.FC, "fc-100", null, "홍길동", "abc123"))
                .isInstanceOf(InvalidDisclosureException.class);

        assertThatThrownBy(() -> DisclosureDelivery.record(
                null, "PROD-1", SalesChannel.FC, "fc-100", null, "홍길동", "A".repeat(64)))
                .isInstanceOf(InvalidDisclosureException.class);
    }

    @Test
    @DisplayName("교부 상대 이름이 공백이면 거부")
    void rejectsBlankContractorName() {
        assertThatThrownBy(() -> DisclosureDelivery.record(
                null, "PROD-1", SalesChannel.FC, "fc-100", null, "  ", SHA256))
                .isInstanceOf(InvalidDisclosureException.class);
    }

    @Test
    @DisplayName("BANCA 교부 정상 경로 — 은행 코드 보존")
    void recordsBancaDelivery() {
        DisclosureDelivery d = DisclosureDelivery.record(
                null, "PROD-1", SalesChannel.BANCA, "teller-1", "BANK-KB", "홍길동", SHA256);

        assertThat(d.getPartnerBankCode()).isEqualTo("BANK-KB");
    }
}
