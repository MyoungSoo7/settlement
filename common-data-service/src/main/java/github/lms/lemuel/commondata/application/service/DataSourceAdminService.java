package github.lms.lemuel.commondata.application.service;

import github.lms.lemuel.commondata.application.port.in.RegisterDataSourceUseCase;
import github.lms.lemuel.commondata.application.port.out.LoadDataSourcePort;
import github.lms.lemuel.commondata.application.port.out.SaveDataSourcePort;
import github.lms.lemuel.commondata.domain.DataSource;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Map;

/**
 * 데이터소스 등록/수정 (운영자 전용 경로에서만 호출).
 *
 * <p>code 기준 upsert — 기존 소스가 있으면 요청에서 null 인 필드는 기존 값을 보존하는
 * 부분 갱신이다. 유효성은 {@link DataSource} 도메인 생성자가 강제한다(신규인데 name/endpoint
 * 누락이면 여기서 IllegalArgumentException → 400).
 */
@Service
public class DataSourceAdminService implements RegisterDataSourceUseCase {

    private final LoadDataSourcePort loadDataSourcePort;
    private final SaveDataSourcePort saveDataSourcePort;

    public DataSourceAdminService(LoadDataSourcePort loadDataSourcePort,
                                  SaveDataSourcePort saveDataSourcePort) {
        this.loadDataSourcePort = loadDataSourcePort;
        this.saveDataSourcePort = saveDataSourcePort;
    }

    @Override
    @CacheEvict(cacheNames = {"dataSources", "dataRecords"}, allEntries = true)
    public DataSource register(RegisterCommand command) {
        if (command == null || command.code() == null) {
            throw new IllegalArgumentException("code 은(는) 필수입니다");
        }
        if (command.endpoint() != null) {
            assertNotInternalAddress(command.endpoint());
        }
        DataSource existing = loadDataSourcePort.findByCode(command.code()).orElse(null);
        DataSource merged = new DataSource(
                existing == null ? null : existing.id(),
                command.code(),
                command.name() != null ? command.name()
                        : existing != null ? existing.name() : null,
                command.endpoint() != null ? command.endpoint()
                        : existing != null ? existing.endpoint() : null,
                command.defaultParams() != null ? command.defaultParams()
                        : existing != null ? existing.defaultParams() : Map.of(),
                command.keyFields() != null ? command.keyFields()
                        : existing != null ? existing.keyFields() : List.of(),
                command.pageSize() != null ? command.pageSize()
                        : existing != null ? existing.pageSize() : DataSource.DEFAULT_PAGE_SIZE,
                command.enabled() != null ? command.enabled()
                        : existing == null || existing.enabled(),
                command.description() != null ? command.description()
                        : existing != null ? existing.description() : null,
                null);
        return saveDataSourcePort.upsert(merged);
    }

    /**
     * SSRF 방지 — 등록 endpoint 가 내부/사설/루프백/링크로컬(메타데이터 169.254.169.254 포함) 대상이면 거절한다.
     * 범용 커넥터라 외부 host 는 허용하되 내부망 피벗만 차단한다.
     *
     * <p>오프라인 테스트/수집을 위해 <b>DNS 조회는 하지 않는다</b>: 호스트가 리터럴 IP 면 그 IP 의 범위만
     * 검사하고(getByName 이 리터럴은 DNS 없이 파싱), 호스트명이면 localhost/.local/.internal 등 내부명만 차단한다.
     */
    private static void assertNotInternalAddress(String endpoint) {
        URI uri;
        try {
            uri = URI.create(endpoint);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("endpoint URL 형식이 올바르지 않습니다: " + endpoint);
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("endpoint 에 호스트가 없습니다: " + endpoint);
        }
        String bare = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        String lower = bare.toLowerCase();
        if (lower.equals("localhost") || lower.endsWith(".localhost")
                || lower.endsWith(".local") || lower.endsWith(".internal")) {
            throw new IllegalArgumentException("내부 호스트로의 데이터소스 등록은 차단됩니다 (SSRF 방지): " + host);
        }
        if (isLiteralIp(bare)) {
            InetAddress addr;
            try {
                addr = InetAddress.getByName(bare);   // 리터럴 IP → DNS 없이 파싱
            } catch (UnknownHostException e) {
                throw new IllegalArgumentException("endpoint IP 파싱 실패: " + host);
            }
            if (addr.isLoopbackAddress() || addr.isAnyLocalAddress()
                    || addr.isSiteLocalAddress() || addr.isLinkLocalAddress()
                    || isUniqueLocalIpv6(addr)) {
                throw new IllegalArgumentException(
                        "내부/사설 IP 로의 데이터소스 등록은 차단됩니다 (SSRF 방지): " + host);
            }
        }
    }

    private static boolean isLiteralIp(String host) {
        if (host.indexOf(':') >= 0) {
            return true;   // IPv6 리터럴
        }
        return host.matches("\\d{1,3}(\\.\\d{1,3}){3}");
    }

    private static boolean isUniqueLocalIpv6(InetAddress addr) {
        byte[] b = addr.getAddress();
        return b.length == 16 && (b[0] & 0xfe) == 0xfc;   // fc00::/7 (IPv6 ULA)
    }
}
