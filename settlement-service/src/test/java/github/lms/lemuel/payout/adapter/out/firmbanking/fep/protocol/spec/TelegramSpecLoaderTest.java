package github.lms.lemuel.payout.adapter.out.firmbanking.fep.protocol.spec;

import github.lms.lemuel.payout.adapter.out.firmbanking.fep.protocol.FepField;
import github.lms.lemuel.payout.adapter.out.firmbanking.fep.protocol.FepFieldType;
import github.lms.lemuel.payout.adapter.out.firmbanking.fep.protocol.FepProtocolException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 전문 스펙 로더 규격 테스트 (ADR 0033 Phase 1).
 *
 * <p>두 가지가 계약이다 — ① 스펙 파일이 기존 하드코딩 레이아웃과 <b>바이트 단위로 등가</b>일 것,
 * ② 잘못된 스펙은 <b>로딩 시점에</b> 실패할 것. 대외 전송 경계라 "조용히 통과"가 가장 위험하다.
 */
class TelegramSpecLoaderTest {

    private static final String HEADER = """
            fragment: COMMON_HEADER
            fields:
              - { name: MSG_TYPE,    length: 4,  type: AN }
              - { name: TELEGRAM_NO, length: 12, type: N }
            """;

    private static Map<String, String> sources(String... yamls) {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("common-header.yaml", HEADER);
        for (int i = 0; i < yamls.length; i++) {
            map.put("spec-" + i + ".yaml", yamls[i]);
        }
        return map;
    }

    @Nested
    @DisplayName("classpath 실스펙 — 기존 하드코딩 레이아웃과 등가")
    class RealSpecs {

        private final TelegramCatalog catalog =
                TelegramSpecLoader.loadFromClasspath(TelegramSpecLoader.FIRMBANKING_LOCATION);

        @Test
        @DisplayName("펌뱅킹 전문 10종이 로드된다 (fragment 는 전문으로 세지 않는다)")
        void loadsAllTelegrams() {
            assertThat(catalog.names())
                    .containsExactlyInAnyOrder(
                            "TRANSFER_REQUEST", "TRANSFER_RESPONSE", "INQUIRY_REQUEST", "INQUIRY_RESPONSE",
                            "BALANCE_REQUEST", "BALANCE_RESPONSE", "HOLDER_REQUEST", "HOLDER_RESPONSE",
                            "BULK_TRANSFER_REQUEST", "BULK_TRANSFER_RESPONSE");
        }

        @Test
        @DisplayName("조회계 3종 총길이: 잔액 60/95 · 예금주 60/81")
        void inquiryTelegramLengths() {
            assertThat(catalog.spec("BALANCE_REQUEST").totalLength()).isEqualTo(60);
            assertThat(catalog.spec("BALANCE_RESPONSE").totalLength()).isEqualTo(95);
            assertThat(catalog.spec("HOLDER_REQUEST").totalLength()).isEqualTo(60);
            assertThat(catalog.spec("HOLDER_RESPONSE").totalLength()).isEqualTo(81);
        }

        @Test
        @DisplayName("이체요청 필드 = 공통부 4 + 개별부 5, 총 113바이트 (공통부가 선두)")
        void transferRequestMatchesHardcodedLayout() {
            TelegramSpec spec = catalog.spec("TRANSFER_REQUEST");

            assertThat(spec.fields()).containsExactly(
                    new FepField("MSG_TYPE", 4, FepFieldType.AN),
                    new FepField("TELEGRAM_NO", 12, FepFieldType.N),
                    new FepField("TRANS_DT", 14, FepFieldType.N),
                    new FepField("RESP_CODE", 4, FepFieldType.AN),
                    new FepField("BANK_CODE", 10, FepFieldType.AN),
                    new FepField("ACCOUNT_NO", 16, FepFieldType.AN),
                    new FepField("AMOUNT", 13, FepFieldType.N, 0),   // scale 선언 = 금액 필드
                    new FepField("HOLDER_NAME", 20, FepFieldType.AN),
                    new FepField("REF_ID", 20, FepFieldType.AN));
            assertThat(spec.totalLength()).isEqualTo(113);
        }

        @Test
        @DisplayName("나머지 3종 총길이: 응답 133 · 조회요청 66 · 조회응답 91")
        void remainingTotalLengths() {
            assertThat(catalog.spec("TRANSFER_RESPONSE").totalLength()).isEqualTo(133);
            assertThat(catalog.spec("INQUIRY_REQUEST").totalLength()).isEqualTo(66);
            assertThat(catalog.spec("INQUIRY_RESPONSE").totalLength()).isEqualTo(91);
        }

        @Test
        @DisplayName("전문구분코드로 레이아웃을 찾는다 — 수신 전문 해석 경로")
        void resolvesByMsgType() {
            assertThat(catalog.byMsgType("0200").name()).isEqualTo("TRANSFER_REQUEST");
            assertThat(catalog.byMsgType("0410").name()).isEqualTo("INQUIRY_RESPONSE");
            assertThatThrownBy(() -> catalog.byMsgType("9999"))
                    .isInstanceOf(FepProtocolException.class)
                    .hasMessageContaining("알 수 없는 전문구분코드");
        }

        @Test
        @DisplayName("로드된 스펙은 곧바로 코덱이 된다 — 인코딩 길이가 총길이와 일치")
        void layoutEncodesToDeclaredLength() {
            byte[] telegram = catalog.layout("INQUIRY_REQUEST").encode(
                    Map.of("MSG_TYPE", "0400", "ORIG_TELEGRAM_NO", "260808000001", "REF_ID", "PAYOUT-42"));
            assertThat(telegram).hasSize(66);
        }
    }

    @Nested
    @DisplayName("반복부(OCCURS) — 다건이체")
    class Repetition {

        private final TelegramCatalog catalog =
                TelegramSpecLoader.loadFromClasspath(TelegramSpecLoader.FIRMBANKING_LOCATION);

        @Test
        @DisplayName("반복부는 인덱스 붙은 필드로 펼쳐진다: DETAIL_1_SEQ … DETAIL_5_REF_ID")
        void flattensRepeatedGroup() {
            TelegramSpec spec = catalog.spec("BULK_TRANSFER_REQUEST");

            assertThat(spec.fields()).extracting(FepField::name)
                    .startsWith("MSG_TYPE", "TELEGRAM_NO", "TRANS_DT", "RESP_CODE", "TOTAL_CNT", "TOTAL_AMOUNT",
                            "DETAIL_1_SEQ", "DETAIL_1_BANK_CODE")
                    .endsWith("DETAIL_5_AMOUNT", "DETAIL_5_HOLDER_NAME", "DETAIL_5_REF_ID")
                    // 공통부 4 + 합계부 2 + 명세 6 x 5
                    .hasSize(36);
            assertThat(spec.totalLength()).isEqualTo(462);
        }

        @Test
        @DisplayName("반복 구조가 보존된다 — 코드 생성이 List<Detail> 을 만들 근거")
        void keepsGroupStructure() {
            TelegramSpec spec = catalog.spec("BULK_TRANSFER_RESPONSE");

            assertThat(spec.elements()).hasSize(4 + 2 + 1);
            TelegramElement.Repeated group = (TelegramElement.Repeated) spec.elements().getLast();
            assertThat(group.name()).isEqualTo("DETAIL");
            assertThat(group.count()).isEqualTo(5);
            assertThat(group.fields()).extracting(FepField::name)
                    .containsExactly("SEQ", "REF_ID", "RESULT", "TXN_ID", "ERROR_CODE");
            assertThat(spec.totalLength()).isEqualTo(280);
        }

        @Test
        @DisplayName("건별 값이 제자리에 인코딩된다 — 3번째 명세만 채워도 그 슬롯에서만 읽힌다")
        void encodesPerOccurrenceSlot() {
            var layout = catalog.layout("BULK_TRANSFER_REQUEST");
            Map<String, String> values = new LinkedHashMap<>();
            values.put("MSG_TYPE", "0220");
            values.put("TOTAL_CNT", "1");
            values.put("TOTAL_AMOUNT", "50000");
            values.put("DETAIL_3_SEQ", "3");
            values.put("DETAIL_3_AMOUNT", "50000");
            values.put("DETAIL_3_REF_ID", "PAYOUT-777");

            Map<String, String> decoded = layout.decode(layout.encode(values));

            assertThat(decoded).containsEntry("DETAIL_3_REF_ID", "PAYOUT-777");
            assertThat(decoded).containsEntry("DETAIL_3_AMOUNT", "0000000050000");
            assertThat(decoded.get("DETAIL_2_REF_ID")).isEmpty();
            assertThat(decoded).containsEntry("DETAIL_4_AMOUNT", "0000000000000");
        }

        @Test
        @DisplayName("반복 횟수 0 이하는 실패")
        void rejectsNonPositiveCount() {
            String yaml = """
                    telegram: X
                    msgType: "0100"
                    fields:
                      - occurs:
                          name: D
                          count: 0
                          fields:
                            - { name: A, length: 5, type: AN }
                    """;
            assertThatThrownBy(() -> TelegramSpecLoader.parseAll(sources(yaml)))
                    .isInstanceOf(FepProtocolException.class)
                    .hasMessageContaining("반복 횟수");
        }

        @Test
        @DisplayName("반복부 중첩은 실패 — Phase 2 는 1단만 지원한다")
        void rejectsNestedOccurs() {
            String yaml = """
                    telegram: X
                    msgType: "0100"
                    fields:
                      - occurs:
                          name: OUTER
                          count: 2
                          fields:
                            - occurs:
                                name: INNER
                                count: 2
                                fields:
                                  - { name: A, length: 5, type: AN }
                    """;
            assertThatThrownBy(() -> TelegramSpecLoader.parseAll(sources(yaml)))
                    .isInstanceOf(FepProtocolException.class)
                    .hasMessageContaining("중첩");
        }

        @Test
        @DisplayName("반복부의 알 수 없는 키·이름 누락은 실패")
        void rejectsMalformedOccurs() {
            String unknownKey = """
                    telegram: X
                    msgType: "0100"
                    fields:
                      - occurs: { name: D, count: 2, feilds: [] }
                    """;
            String missingName = """
                    telegram: X
                    msgType: "0100"
                    fields:
                      - occurs:
                          count: 2
                          fields:
                            - { name: A, length: 5, type: AN }
                    """;
            assertThatThrownBy(() -> TelegramSpecLoader.parseAll(sources(unknownKey)))
                    .isInstanceOf(FepProtocolException.class)
                    .hasMessageContaining("feilds");
            assertThatThrownBy(() -> TelegramSpecLoader.parseAll(sources(missingName)))
                    .isInstanceOf(FepProtocolException.class)
                    .hasMessageContaining("name");
        }

        @Test
        @DisplayName("펼친 뒤 필드명이 겹치면 실패 — 반복부 이름이 기존 필드와 충돌하는 경우")
        void rejectsCollisionAfterFlattening() {
            String yaml = """
                    telegram: X
                    msgType: "0100"
                    fields:
                      - { name: D_1_A, length: 5, type: AN }
                      - occurs:
                          name: D
                          count: 2
                          fields:
                            - { name: A, length: 5, type: AN }
                    """;
            assertThatThrownBy(() -> TelegramSpecLoader.parseAll(sources(yaml)))
                    .isInstanceOf(FepProtocolException.class)
                    .hasMessageContaining("필드명 중복");
        }
    }

    @Nested
    @DisplayName("스펙 검증 — 위반은 전부 로딩 시점 실패")
    class Validation {

        @Test
        @DisplayName("선언 총길이가 계산값과 다르면 실패 — 설계서 오탈자를 여기서 잡는다")
        void rejectsTotalLengthMismatch() {
            String yaml = """
                    telegram: X
                    msgType: "0100"
                    include: COMMON_HEADER
                    totalLength: 99
                    fields:
                      - { name: A, length: 5, type: AN }
                    """;
            assertThatThrownBy(() -> TelegramSpecLoader.parseAll(sources(yaml)))
                    .isInstanceOf(FepProtocolException.class)
                    .hasMessageContaining("총길이 불일치")
                    .hasMessageContaining("99")
                    .hasMessageContaining("21");
        }

        @Test
        @DisplayName("공통부와 개별부에 같은 필드명이 있으면 실패")
        void rejectsDuplicateFieldName() {
            String yaml = """
                    telegram: X
                    msgType: "0100"
                    include: COMMON_HEADER
                    fields:
                      - { name: MSG_TYPE, length: 4, type: AN }
                    """;
            assertThatThrownBy(() -> TelegramSpecLoader.parseAll(sources(yaml)))
                    .isInstanceOf(FepProtocolException.class)
                    .hasMessageContaining("필드명 중복");
        }

        @Test
        @DisplayName("알 수 없는 키는 실패 — 오타(lenght)가 조용히 무시되면 전문 길이가 어긋난다")
        void rejectsUnknownKey() {
            String yaml = """
                    telegram: X
                    msgType: "0100"
                    fields:
                      - { name: A, lenght: 5, type: AN }
                    """;
            assertThatThrownBy(() -> TelegramSpecLoader.parseAll(sources(yaml)))
                    .isInstanceOf(FepProtocolException.class)
                    .hasMessageContaining("lenght");
        }

        @Test
        @DisplayName("존재하지 않는 fragment 를 include 하면 실패")
        void rejectsDanglingInclude() {
            String yaml = """
                    telegram: X
                    msgType: "0100"
                    include: NO_SUCH_FRAGMENT
                    fields:
                      - { name: A, length: 5, type: AN }
                    """;
            assertThatThrownBy(() -> TelegramSpecLoader.parseAll(sources(yaml)))
                    .isInstanceOf(FepProtocolException.class)
                    .hasMessageContaining("NO_SUCH_FRAGMENT");
        }

        @Test
        @DisplayName("두 전문이 같은 전문구분코드를 선언하면 실패 — 수신 해석이 모호해진다")
        void rejectsDuplicateMsgType() {
            String a = """
                    telegram: A
                    msgType: "0100"
                    fields:
                      - { name: F, length: 5, type: AN }
                    """;
            String b = """
                    telegram: B
                    msgType: "0100"
                    fields:
                      - { name: F, length: 5, type: AN }
                    """;
            assertThatThrownBy(() -> TelegramSpecLoader.parseAll(sources(a, b)))
                    .isInstanceOf(FepProtocolException.class)
                    .hasMessageContaining("전문구분코드 중복");
        }

        @Test
        @DisplayName("알 수 없는 필드 타입은 실패 — AN/N 만 허용")
        void rejectsUnknownFieldType() {
            String yaml = """
                    telegram: X
                    msgType: "0100"
                    fields:
                      - { name: A, length: 5, type: PACKED }
                    """;
            assertThatThrownBy(() -> TelegramSpecLoader.parseAll(sources(yaml)))
                    .isInstanceOf(FepProtocolException.class)
                    .hasMessageContaining("PACKED");
        }

        @Test
        @DisplayName("길이 0 필드는 실패")
        void rejectsZeroLength() {
            String yaml = """
                    telegram: X
                    msgType: "0100"
                    fields:
                      - { name: A, length: 0, type: AN }
                    """;
            assertThatThrownBy(() -> TelegramSpecLoader.parseAll(sources(yaml)))
                    .isInstanceOf(FepProtocolException.class)
                    .hasMessageContaining("길이");
        }

        @Test
        @DisplayName("전문구분코드 누락은 실패")
        void rejectsMissingMsgType() {
            String yaml = """
                    telegram: X
                    fields:
                      - { name: A, length: 5, type: AN }
                    """;
            assertThatThrownBy(() -> TelegramSpecLoader.parseAll(sources(yaml)))
                    .isInstanceOf(FepProtocolException.class)
                    .hasMessageContaining("msgType");
        }

        @Test
        @DisplayName("전문구분코드를 인용부호 없이 쓰면 실패 — 0200 이 숫자 200 이 되어 선행 0 이 사라진다")
        void rejectsUnquotedMsgType() {
            String yaml = """
                    telegram: X
                    msgType: 0200
                    fields:
                      - { name: A, length: 5, type: AN }
                    """;
            assertThatThrownBy(() -> TelegramSpecLoader.parseAll(sources(yaml)))
                    .isInstanceOf(FepProtocolException.class)
                    .hasMessageContaining("인용부호");
        }

        @Test
        @DisplayName("telegram 도 fragment 도 없으면 실패")
        void rejectsFileWithNeitherKey() {
            assertThatThrownBy(() -> TelegramSpecLoader.parseAll(sources("description: 빈 파일\n")))
                    .isInstanceOf(FepProtocolException.class)
                    .hasMessageContaining("telegram 또는 fragment");
        }

        @Test
        @DisplayName("같은 fragment 를 두 파일이 선언하면 실패")
        void rejectsDuplicateFragment() {
            Map<String, String> sources = sources();
            sources.put("dup.yaml", HEADER);
            assertThatThrownBy(() -> TelegramSpecLoader.parseAll(sources))
                    .isInstanceOf(FepProtocolException.class)
                    .hasMessageContaining("fragment 중복");
        }

        @Test
        @DisplayName("fields 누락·비목록·비매핑 항목은 실패")
        void rejectsMalformedFields() {
            String missing = """
                    telegram: X
                    msgType: "0100"
                    """;
            String notAMapping = """
                    telegram: X
                    msgType: "0100"
                    fields:
                      - JUST_A_STRING
                    """;
            assertThatThrownBy(() -> TelegramSpecLoader.parseAll(sources(missing)))
                    .isInstanceOf(FepProtocolException.class)
                    .hasMessageContaining("fields 목록 필수");
            assertThatThrownBy(() -> TelegramSpecLoader.parseAll(sources(notAMapping)))
                    .isInstanceOf(FepProtocolException.class)
                    .hasMessageContaining("매핑");
        }

        @Test
        @DisplayName("스펙 파일이 매핑이 아니면 실패")
        void rejectsNonMappingRoot() {
            assertThatThrownBy(() -> TelegramSpecLoader.parseAll(sources("- 목록일 뿐\n")))
                    .isInstanceOf(FepProtocolException.class)
                    .hasMessageContaining("매핑이 아니다");
        }

        @Test
        @DisplayName("길이·개정번호·총길이가 정수가 아니면 실패")
        void rejectsNonIntegerNumbers() {
            String badLength = """
                    telegram: X
                    msgType: "0100"
                    fields:
                      - { name: A, length: five, type: AN }
                    """;
            String badVersion = """
                    telegram: X
                    msgType: "0100"
                    version: 일
                    fields:
                      - { name: A, length: 5, type: AN }
                    """;
            assertThatThrownBy(() -> TelegramSpecLoader.parseAll(sources(badLength)))
                    .isInstanceOf(FepProtocolException.class)
                    .hasMessageContaining("정수");
            assertThatThrownBy(() -> TelegramSpecLoader.parseAll(sources(badVersion)))
                    .isInstanceOf(FepProtocolException.class)
                    .hasMessageContaining("정수");
        }

        @Test
        @DisplayName("effectiveFrom 은 yyyy-MM-dd — 인용부호 유무와 무관하게 같은 날짜로 읽힌다")
        void parsesEffectiveFromBothForms() {
            String quoted = """
                    telegram: Q
                    msgType: "0101"
                    effectiveFrom: "2026-03-04"
                    fields:
                      - { name: A, length: 5, type: AN }
                    """;
            String unquoted = """
                    telegram: U
                    msgType: "0102"
                    effectiveFrom: 2026-03-04
                    fields:
                      - { name: A, length: 5, type: AN }
                    """;
            TelegramCatalog catalog = TelegramSpecLoader.parseAll(sources(quoted, unquoted));

            assertThat(catalog.spec("Q").effectiveFrom()).isEqualTo(LocalDate.of(2026, 3, 4));
            assertThat(catalog.spec("U").effectiveFrom()).isEqualTo(LocalDate.of(2026, 3, 4));
        }

        @Test
        @DisplayName("effectiveFrom 형식이 틀리면 실패")
        void rejectsBadEffectiveFrom() {
            String yaml = """
                    telegram: X
                    msgType: "0100"
                    effectiveFrom: "2026년 3월"
                    fields:
                      - { name: A, length: 5, type: AN }
                    """;
            assertThatThrownBy(() -> TelegramSpecLoader.parseAll(sources(yaml)))
                    .isInstanceOf(FepProtocolException.class)
                    .hasMessageContaining("effectiveFrom");
        }

        @Test
        @DisplayName("같은 키를 두 번 쓰면 실패 — 뒤엣것이 조용히 이기는 일을 막는다")
        void rejectsDuplicateYamlKey() {
            String yaml = """
                    telegram: X
                    msgType: "0100"
                    msgType: "0101"
                    fields:
                      - { name: A, length: 5, type: AN }
                    """;
            assertThatThrownBy(() -> TelegramSpecLoader.parseAll(sources(yaml)))
                    .isInstanceOf(RuntimeException.class);
        }

        @Test
        @DisplayName("스펙이 하나도 없는 위치를 로드하면 실패 — 빈 카탈로그로 조용히 넘어가지 않는다")
        void rejectsEmptyLocation() {
            assertThatThrownBy(() -> TelegramSpecLoader.loadFromClasspath("telegram/no-such-dir"))
                    .isInstanceOf(FepProtocolException.class)
                    .hasMessageContaining("하나도 없다");
        }

        @Test
        @DisplayName("등록되지 않은 전문 이름 조회는 실패 — 오타를 빈 전문으로 흘리지 않는다")
        void rejectsUnknownTelegramName() {
            TelegramCatalog catalog =
                    TelegramSpecLoader.loadFromClasspath(TelegramSpecLoader.FIRMBANKING_LOCATION);
            assertThatThrownBy(() -> catalog.layout("TRANSFER_REQEUST"))
                    .isInstanceOf(FepProtocolException.class)
                    .hasMessageContaining("알 수 없는 전문");
            assertThat(catalog.size()).isEqualTo(10);
        }

        @Test
        @DisplayName("선언 총길이가 맞으면 통과하고 개정번호 기본값은 1")
        void acceptsValidSpec() {
            String yaml = """
                    telegram: X
                    msgType: "0100"
                    description: 시험 전문
                    include: COMMON_HEADER
                    totalLength: 21
                    fields:
                      - { name: A, length: 5, type: AN }
                    """;
            TelegramSpec spec = TelegramSpecLoader.parseAll(sources(yaml)).spec("X");

            assertThat(spec.totalLength()).isEqualTo(21);
            assertThat(spec.version()).isEqualTo(1);
            assertThat(spec.description()).isEqualTo("시험 전문");
        }
    }
}
