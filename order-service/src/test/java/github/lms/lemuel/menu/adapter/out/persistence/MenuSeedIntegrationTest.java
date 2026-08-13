package github.lms.lemuel.menu.adapter.out.persistence;

import github.lms.lemuel.common.outbox.adapter.out.persistence.OutboxSchema;
import github.lms.lemuel.menu.domain.Menu;
import github.lms.lemuel.menu.domain.MenuArea;
import github.lms.lemuel.menu.domain.MenuType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 시드된 메뉴 트리가 이관 전 하드코딩 네비게이션과 정확히 같은지 검증한다.
 *
 * <p>이 P-1 단계의 목표는 "기능 0 추가, 회귀 0" 이다. 마이그레이션 SQL 이 실제 Postgres 에
 * 적용된 결과를 세어 보지 않으면 시드가 반쯤 들어가도 아무도 모른다 — 그래서 DDL 검증
 * (SchemaIntegrationTest)과 별개로 데이터까지 본다.
 */
@Testcontainers
@EnabledIf(value = "isDockerAvailable", disabledReason = "Docker is not available")
@DataJpaTest
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({OutboxSchema.class, MenuPersistenceAdapter.class})
@ActiveProfiles("test")
class MenuSeedIntegrationTest {

    static boolean isDockerAvailable() {
        try { DockerClientFactory.instance().client(); return true; }
        catch (Throwable ex) { return false; }
    }

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("inter")
            .withUsername("lemuel")
            .withPassword("lemuel");

    @DynamicPropertySource
    static void overrideDatasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Autowired MenuPersistenceAdapter adapter;

    private Map<String, Menu> byName() {
        return adapter.findAll().stream().collect(Collectors.toMap(Menu::getName, Function.identity()));
    }

    private List<Menu> childrenOf(String parentName) {
        Menu parent = byName().get(parentName);
        return adapter.findAll().stream()
                .filter(m -> parent.getId().equals(m.getParentId()))
                .sorted(java.util.Comparator.comparingInt(Menu::getSortOrder))
                .toList();
    }

    @Test
    @DisplayName("시드 총 36행 — 이관분 31 + 정산운영 그룹 1 + 운영 화면 4")
    void seedsExactlyThirtySix() {
        assertThat(adapter.findAll()).hasSize(36);
    }

    @Test
    @DisplayName("최상위 10개가 상단 네비 순서대로 들어간다")
    void rootsInOrder() {
        List<Menu> roots = adapter.findAll().stream()
                .filter(m -> m.getParentId() == null)
                .sorted(java.util.Comparator.comparingInt(Menu::getSortOrder))
                .toList();

        assertThat(roots).extracting(Menu::getName).containsExactly(
                "대시보드", "정산", "정산운영", "배송", "승인", "AI 도우미", "CEO", "시스템 관리",
                "주문하기", "추천받기");
    }

    @Test
    @DisplayName("정산운영 그룹은 운영 화면만 담는다 — 정산 그룹과 섞이지 않는다")
    void settlementOpsChildren() {
        assertThat(childrenOf("정산운영")).extracting(Menu::getName)
                .containsExactly("정합성 검증", "일일 대사", "PG 대사", "원장·시산표");
        assertThat(childrenOf("정산")).extracting(Menu::getName)
                .containsExactly("상품관리", "정산관리", "정산조회", "지급관리");
    }

    @Test
    @DisplayName("정산 사이드바 4개 — 지급관리만 ADMIN 전용")
    void settlementChildren() {
        List<Menu> children = childrenOf("정산");

        assertThat(children).extracting(Menu::getName)
                .containsExactly("상품관리", "정산관리", "정산조회", "지급관리");
        assertThat(children).extracting(Menu::getPath)
                .containsExactly("/product", "/admin/settlement", "/settlement/search", "/admin/payouts");
        assertThat(children.get(3).allowedRoles()).containsExactly("ADMIN");
        assertThat(children.get(0).allowedRoles()).containsExactlyInAnyOrder("ADMIN", "MANAGER");
    }

    @Test
    @DisplayName("CEO 사이드바 13개가 순서대로 들어간다")
    void ceoChildren() {
        assertThat(childrenOf("CEO")).extracting(Menu::getName).containsExactly(
                "통합 브리핑", "경제지표", "재무제표", "기업조회", "사업장비교",
                "투자하기", "투자 추천", "대출관리", "대출 상품 안내", "대출 심사·상환 안내",
                "대출기관 안내", "자산운용펀드 안내", "계정계 현황");
    }

    @Test
    @DisplayName("시스템 사이드바 5개 — 앞 3개는 RBAC permission 과 짝지어진다")
    void systemChildren() {
        List<Menu> children = childrenOf("시스템 관리");

        assertThat(children).extracting(Menu::getName).containsExactly(
                "메뉴 관리", "공통코드 관리", "RBAC 관리", "이커머스 카테고리", "운영관리");
        assertThat(children).extracting(Menu::getRequiredPermission).containsExactly(
                "SYSTEM_MENU_MANAGE", "SYSTEM_CODE_MANAGE", "SYSTEM_RBAC_MANAGE", null, null);
    }

    @Test
    @DisplayName("시스템 루트는 상단 네비에 '시스템', 사이드바에 '시스템 관리' 로 보인다")
    void systemRootLabels() {
        Menu system = byName().get("시스템 관리");

        assertThat(system.displayLabel()).isEqualTo("시스템");
        assertThat(system.getDescription()).isEqualTo("System Administration");
        assertThat(system.getType()).isEqualTo(MenuType.GROUP);
        assertThat(system.getArea()).isEqualTo(MenuArea.SYSTEM);
    }

    @Test
    @DisplayName("모든 행에 영역이 채워져 있고, 자식은 부모와 같은 영역이다")
    void areaIsConsistent() {
        List<Menu> all = adapter.findAll();
        Map<Long, Menu> byId = all.stream().collect(Collectors.toMap(Menu::getId, Function.identity()));

        assertThat(all).allSatisfy(m -> assertThat(m.getArea()).isNotNull());
        assertThat(all).allSatisfy(m -> {
            if (m.getParentId() != null) {
                assertThat(m.getArea()).isEqualTo(byId.get(m.getParentId()).getArea());
            }
        });
    }

    @Test
    @DisplayName("깊이는 2 단계를 넘지 않는다 (도메인 상한 3 이내)")
    void depthWithinLimit() {
        Map<Long, Menu> byId = adapter.findAll().stream()
                .collect(Collectors.toMap(Menu::getId, Function.identity()));

        for (Menu menu : byId.values()) {
            int depth = 1;
            Long cursor = menu.getParentId();
            while (cursor != null) {
                depth++;
                cursor = byId.get(cursor).getParentId();
            }
            assertThat(depth).isLessThanOrEqualTo(2);
            assertThat(depth).isLessThanOrEqualTo(Menu.MAX_DEPTH);
        }
    }

    @Test
    @DisplayName("링크 노드는 모두 '/' 로 시작하는 경로를 갖는다")
    void everyLinkHasAbsolutePath() {
        assertThat(adapter.findAll())
                .filteredOn(m -> m.getType() != MenuType.DIVIDER)
                .allSatisfy(m -> assertThat(m.getPath()).startsWith("/"));
    }

    @Test
    @DisplayName("구매자 메뉴 2개는 USER 에게만 보인다 — 관리자 네비에는 주문/추천이 없었다")
    void shopMenusAreUserOnly() {
        Set<String> shopNames = adapter.findAll().stream()
                .filter(m -> m.getArea() == MenuArea.SHOP)
                .map(Menu::getName)
                .collect(Collectors.toSet());

        assertThat(shopNames).containsExactlyInAnyOrder("주문하기", "추천받기");
        assertThat(adapter.findAll()).filteredOn(m -> m.getArea() == MenuArea.SHOP)
                .allSatisfy(m -> assertThat(m.allowedRoles()).containsExactly("USER"));
    }
}
