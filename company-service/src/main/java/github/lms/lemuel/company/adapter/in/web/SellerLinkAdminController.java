package github.lms.lemuel.company.adapter.in.web;

import github.lms.lemuel.company.application.port.in.LinkSellerUseCase;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 셀러↔기업 명시 링크 (운영자 전용 — AdminApiKeyFilter 게이팅, gateway 미라우팅).
 *
 * <p>user.registered 에 기업 연결 키가 없어 자동 매핑이 불가능하므로 운영자가 명시적으로 링크한다.
 * 링크된 셀러는 다음 평판 등급 변동 이벤트부터 payload 에 동봉돼 loan 이 셀러 신용에 반영한다.
 */
@RestController
@RequestMapping("/admin/company/sellers")
public class SellerLinkAdminController {

    private final LinkSellerUseCase linkSellerUseCase;

    public SellerLinkAdminController(LinkSellerUseCase linkSellerUseCase) {
        this.linkSellerUseCase = linkSellerUseCase;
    }

    @PostMapping("/{sellerId}/link/{stockCode}")
    public ResponseEntity<Map<String, String>> link(@PathVariable Long sellerId, @PathVariable String stockCode) {
        linkSellerUseCase.link(sellerId, stockCode);
        return ResponseEntity.ok(Map.of(
                "message", "링크 완료: seller=" + sellerId + " → " + stockCode));
    }
}
