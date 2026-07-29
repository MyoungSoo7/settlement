package github.lms.lemuel.company.application.service;

import github.lms.lemuel.company.application.port.in.ImportCompanyWorkforceUseCase;
import github.lms.lemuel.company.application.port.out.BuildWorkforceAggregatePort;
import github.lms.lemuel.company.application.port.out.SaveCompanyWorkforcePort;
import github.lms.lemuel.company.domain.AggregateRowTally;
import github.lms.lemuel.company.domain.CompanyWorkforce;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.YearMonth;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CompanyWorkforceImportServiceTest {

    private static final Charset CP949 = Charset.forName("CP949");

    // 실제 원본 CSV 헤더(사업장가입상태코드/사업장형태구분코드 컬럼명에 코드 설명이 그대로 박혀 있음)
    private static final String HEADER = "자료생성년월,사업장명,사업자등록번호,사업장가입상태코드 1 등록 2 탈퇴,우편번호,"
            + "사업장지번상세주소,사업장도로명상세주소,고객법정동주소코드,고객행정동주소코드,법정동주소광역시도코드,"
            + "법정동주소광역시시군구코드,법정동주소광역시시군구읍면동코드,사업장형태구분코드 1 법인 2 개인,사업장업종코드,"
            + "사업장업종코드명,적용일자,재등록일자,탈퇴일자,가입자수,당월고지금액,신규취득자수,상실가입자수";

    private final SaveCompanyWorkforcePort saveCompanyWorkforcePort = mock(SaveCompanyWorkforcePort.class);
    private final BuildWorkforceAggregatePort buildWorkforceAggregatePort = mock(BuildWorkforceAggregatePort.class);
    private final CompanyWorkforceImportService service =
            new CompanyWorkforceImportService(saveCompanyWorkforcePort, buildWorkforceAggregatePort);

    private Path writeFixture(Path dir, String... dataRows) throws IOException {
        Path csv = dir.resolve("workforce.csv");
        String content = HEADER + "\r\n" + String.join("\r\n", dataRows) + "\r\n";
        Files.write(csv, content.getBytes(CP949));
        return csv;
    }

    @Test
    @DisplayName("정상행은 적재하고, 탈퇴·사업장명 공백 행은 skip 하며, 임베디드 콤마/따옴표 필드를 올바르게 파싱한다")
    void importsValidRowsAndSkipsInvalidOnes(@TempDir Path tempDir) throws IOException {
        Path csv = writeFixture(tempDir,
                "2026-06,주식회사에고이즘,866759,1,04537,서울특별시 성동구 성수동,서울특별시 성동구 연무장19길,"
                        + "1120011200,1120068000,11,200,112,1,525101,전자상거래 소매업,2020-01-01,,,50,16406250,2,1",
                "2026-06,\"(유)케이비에프에스\"\"전주밥상 다잡수소!\"\"\",418851,1,54932,전북특별자치도 전주시 덕진구 금암동,"
                        + "전북특별자치도 전주시 덕진구 백제대로,5211310700,5211357500,52,113,107,1,552101,한식 일반 음식점업,"
                        + "2014-02-17,,,4,1199160,1,0",
                "2026-06,탈퇴회사,999999,2,00000,주소,주소,0,0,0,0,0,1,999999,폐업업종,1990-01-01,,2026-05-01,10,5000000,0,10",
                "2026-06,,111111,1,00000,주소,주소,0,0,0,0,0,1,999999,업종,1990-01-01,,,5,1000000,0,0",
                "2026-06,둘째회사,222222,1,05678,서울특별시 강남구,서울특별시 강남구 테헤란로,1,1,1,1,1,1,620101,"
                        + "소프트웨어 개발업,2015-03-01,,,10,3286000,0,1");

        when(saveCompanyWorkforcePort.batchUpsert(anyList()))
                .thenAnswer(inv -> new SaveCompanyWorkforcePort.UpsertResult(
                        ((List<?>) inv.getArgument(0)).size()));

        ImportCompanyWorkforceUseCase.ImportResult result = service.importFrom(csv);

        assertEquals(5, result.received());
        assertEquals(3, result.imported());
        assertEquals(2, result.skipped());

        var captor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(saveCompanyWorkforcePort).batchUpsert(captor.capture());
        @SuppressWarnings("unchecked")
        List<CompanyWorkforce> saved = (List<CompanyWorkforce>) captor.getValue();

        CompanyWorkforce egoism = saved.get(0);
        assertEquals("주식회사에고이즘", egoism.workplaceName());
        assertEquals("866759", egoism.bizRegNoPrefix());
        assertEquals(java.util.Optional.of("525101"), egoism.industryGroupKey());
        assertEquals("전자상거래 소매업", egoism.industryName());
        assertEquals("서울특별시 성동구 연무장19길", egoism.address());
        assertEquals(YearMonth.of(2026, 6), egoism.snapshotMonth());
        assertEquals(50, egoism.headcount());
        assertEquals(0, new BigDecimal("16406250").compareTo(egoism.monthlyBilledAmount()));

        CompanyWorkforce quoted = saved.get(1);
        assertEquals("(유)케이비에프에스\"전주밥상 다잡수소!\"", quoted.workplaceName());
    }

    @Test
    @DisplayName("같은 배치 내 (사업장명, 사업자등록번호앞6자리, 자료생성년월) 중복은 한 건으로 합친다 " +
            "— 실서비스에서 reWriteBatchedInserts=true 로 인해 중복 그대로 배치 upsert 하면 " +
            "'ON CONFLICT DO UPDATE command cannot affect row a second time' 로 임포트 전체가 죽는 것을 재현")
    void deduplicatesSameKeyWithinBatch(@TempDir Path tempDir) throws IOException {
        Path csv = writeFixture(tempDir,
                "2026-06,중복회사,333333,1,00000,주소1,도로주소1,0,0,0,0,0,1,999999,업종,1990-01-01,,,10,1000000,0,0",
                "2026-06,중복회사,333333,1,00000,주소2,도로주소2,0,0,0,0,0,1,999999,업종,1990-01-01,,,20,2000000,0,0");

        when(saveCompanyWorkforcePort.batchUpsert(anyList()))
                .thenAnswer(inv -> new SaveCompanyWorkforcePort.UpsertResult(
                        ((List<?>) inv.getArgument(0)).size()));

        ImportCompanyWorkforceUseCase.ImportResult result = service.importFrom(csv);

        assertEquals(2, result.received());
        assertEquals(1, result.imported());
        assertEquals(1, result.skipped());

        var captor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(saveCompanyWorkforcePort).batchUpsert(captor.capture());
        @SuppressWarnings("unchecked")
        List<CompanyWorkforce> saved = (List<CompanyWorkforce>) captor.getValue();

        assertEquals(1, saved.size());
        assertEquals(20, saved.get(0).headcount()); // 같은 키는 마지막 값이 이긴다
    }

    @Test
    @DisplayName("CP949 로 매핑되지 않는 바이트가 섞여 있어도 임포트 전체를 죽이지 않는다 " +
            "— 실제 원본 파일 한복판에서 Java CP949 디코더가 MalformedInputException 을 던져 " +
            "임포트 전체가 죽는 것을 재현(0x81 0xFF 는 어떤 CP949 리드바이트의 유효 후속바이트도 아님)")
    void toleratesUnmappableBytesWithoutCrashing(@TempDir Path tempDir) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes((HEADER + "\r\n").getBytes(CP949));
        out.writeBytes(("2026-06,정상회사1,111111,1,00000,주소,주소,0,0,0,0,0,1,999999,업종,"
                + "1990-01-01,,,5,1000000,0,0\r\n").getBytes(CP949));
        out.writeBytes("2026-06,깨진".getBytes(CP949));
        out.writeBytes(new byte[]{(byte) 0x81, (byte) 0xFF}); // CP949 로 디코드 불가능한 바이트열
        out.writeBytes((",111112,1,00000,주소,주소,0,0,0,0,0,1,999999,업종,"
                + "1990-01-01,,,7,2000000,0,0\r\n").getBytes(CP949));
        out.writeBytes(("2026-06,정상회사2,111113,1,00000,주소,주소,0,0,0,0,0,1,999999,업종,"
                + "1990-01-01,,,9,3000000,0,0\r\n").getBytes(CP949));
        Path csv = tempDir.resolve("malformed.csv");
        Files.write(csv, out.toByteArray());

        when(saveCompanyWorkforcePort.batchUpsert(anyList()))
                .thenAnswer(inv -> new SaveCompanyWorkforcePort.UpsertResult(
                        ((List<?>) inv.getArgument(0)).size()));

        ImportCompanyWorkforceUseCase.ImportResult result = service.importFrom(csv);

        assertEquals(3, result.received());
        assertEquals(3, result.imported());
    }

    @Test
    @DisplayName("적재 후 해당 월 사전 집계를 재생성하고, 대사는 원본 = 수용 + 거부 로 맞춘다 " +
            "— 파싱 실패·중복 병합이 모두 거부로 흡수된다")
    void rebuildsAggregateWithReconciledTally(@TempDir Path tempDir) throws IOException {
        Path csv = writeFixture(tempDir,
                "2026-06,정상회사,111111,1,00000,서울특별시 성동구,서울특별시 성동구 연무장19길,0,0,0,0,0,1,"
                        + "525101,전자상거래 소매업,2020-01-01,,,50,16406250,0,0",
                "2026-06,중복회사,333333,1,00000,주소1,경기도 광주시 도척윗로,0,0,0,0,0,1,292201,업종,"
                        + "1990-01-01,,,10,1000000,0,0",
                "2026-06,중복회사,333333,1,00000,주소2,경기도 광주시 도척윗로,0,0,0,0,0,1,292201,업종,"
                        + "1990-01-01,,,20,2000000,0,0",
                "2026-06,탈퇴회사,999999,2,00000,주소,주소,0,0,0,0,0,1,999999,폐업업종,1990-01-01,,2026-05-01,"
                        + "10,5000000,0,10");

        when(saveCompanyWorkforcePort.batchUpsert(anyList()))
                .thenAnswer(inv -> new SaveCompanyWorkforcePort.UpsertResult(
                        ((List<?>) inv.getArgument(0)).size()));

        ImportCompanyWorkforceUseCase.ImportResult result = service.importFrom(csv);

        assertEquals(4, result.received());
        assertEquals(2, result.imported());   // 정상회사 + 중복회사(마지막 값)
        assertEquals(2, result.skipped());    // 탈퇴 1 + 중복 병합 1
        assertEquals(0, result.unattributed());
        assertEquals(List.of("2026-06"), result.aggregatedMonths());

        // 원본 4 = 수용 2 + 거부 2 (AggregateRowTally 가 이 등식을 강제한다)
        verify(buildWorkforceAggregatePort).rebuild(YearMonth.of(2026, 6), new AggregateRowTally(4, 2, 2));
    }

    @Test
    @DisplayName("여러 월이 섞인 CSV 는 월별로 대사·집계를 따로 만든다")
    void rebuildsEachMonthSeparately(@TempDir Path tempDir) throws IOException {
        Path csv = writeFixture(tempDir,
                "2026-05,오월회사,111111,1,00000,주소,서울특별시 성동구 연무장19길,0,0,0,0,0,1,525101,업종,"
                        + "2020-01-01,,,10,1000000,0,0",
                "2026-06,육월회사,222222,1,00000,주소,서울특별시 성동구 연무장19길,0,0,0,0,0,1,525101,업종,"
                        + "2020-01-01,,,20,2000000,0,0",
                "2026-06,탈퇴회사,999999,2,00000,주소,주소,0,0,0,0,0,1,999999,업종,1990-01-01,,2026-05-01,"
                        + "10,5000000,0,10");

        when(saveCompanyWorkforcePort.batchUpsert(anyList()))
                .thenAnswer(inv -> new SaveCompanyWorkforcePort.UpsertResult(
                        ((List<?>) inv.getArgument(0)).size()));

        ImportCompanyWorkforceUseCase.ImportResult result = service.importFrom(csv);

        assertEquals(List.of("2026-05", "2026-06"), result.aggregatedMonths());
        verify(buildWorkforceAggregatePort).rebuild(YearMonth.of(2026, 5), new AggregateRowTally(1, 1, 0));
        verify(buildWorkforceAggregatePort).rebuild(YearMonth.of(2026, 6), new AggregateRowTally(2, 1, 1));
    }

    @Test
    @DisplayName("자료생성년월을 못 읽은 행은 어느 월 대사에도 섞이지 않고 별도 건수로 보고된다")
    void unattributedRowsAreReportedSeparately(@TempDir Path tempDir) throws IOException {
        Path csv = writeFixture(tempDir,
                "이상한월,깨진회사,111111,1,00000,주소,서울특별시 성동구 연무장19길,0,0,0,0,0,1,525101,업종,"
                        + "2020-01-01,,,10,1000000,0,0",
                "2026-06,정상회사,222222,1,00000,주소,서울특별시 성동구 연무장19길,0,0,0,0,0,1,525101,업종,"
                        + "2020-01-01,,,20,2000000,0,0");

        when(saveCompanyWorkforcePort.batchUpsert(anyList()))
                .thenAnswer(inv -> new SaveCompanyWorkforcePort.UpsertResult(
                        ((List<?>) inv.getArgument(0)).size()));

        ImportCompanyWorkforceUseCase.ImportResult result = service.importFrom(csv);

        assertEquals(1, result.unattributed());
        assertEquals(1, result.skipped());
        verify(buildWorkforceAggregatePort).rebuild(YearMonth.of(2026, 6), new AggregateRowTally(1, 1, 0));
        verify(buildWorkforceAggregatePort, never()).rebuild(eq(null), any());
    }
}
