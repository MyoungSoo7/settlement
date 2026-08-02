package github.lms.lemuel.card.adapter.out.persistence;

import github.lms.lemuel.card.application.port.out.LoadCardAccountPort;
import github.lms.lemuel.card.application.port.out.SaveCardAccountPort;
import github.lms.lemuel.card.domain.CardAccount;
import github.lms.lemuel.card.domain.CardAccountStatus;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
public class CardAccountPersistenceAdapter implements LoadCardAccountPort, SaveCardAccountPort {

    private final SpringDataCardAccountRepository repository;

    public CardAccountPersistenceAdapter(SpringDataCardAccountRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<CardAccount> findByOrganizationId(Long organizationId) {
        // REJECTED(터미널 탈락 이력)는 제외 — 재신청 중복 검증·이탈자 카드 정지 모두
        // "살아 있는 계정"만 봐야 하고, V5 부분 인덱스가 비-REJECTED 1개를 보장한다.
        return repository.findByOrganizationIdAndStatusNot(organizationId, CardAccountStatus.REJECTED)
                .map(CardAccountJpaEntity::toDomain);
    }

    @Override
    public Optional<CardAccount> findById(Long id) {
        return repository.findById(id).map(CardAccountJpaEntity::toDomain);
    }

    @Override
    public Optional<CardAccount> findByIdForUpdate(Long id) {
        return repository.findByIdForUpdate(id).map(CardAccountJpaEntity::toDomain);
    }

    @Override
    public List<CardAccount> findAllActive() {
        return repository.findByStatus(CardAccountStatus.ACTIVE).stream()
                .map(CardAccountJpaEntity::toDomain)
                .toList();
    }

    @Override
    public CardAccount save(CardAccount account) {
        // 도메인 스냅샷 → detached 엔티티 재구성 후 merge. @Version 이 그대로 실려 낙관적 락이 동작한다.
        CardAccountJpaEntity entity = CardAccountJpaEntity.fromDomain(account);
        preserveScreenedAtIfSnapshotUnchanged(account.getId(), entity);
        return repository.saveAndFlush(entity).toDomain();
    }

    /**
     * screened_at 은 "한도 산정 근거를 마지막으로 다시 계산한 시각"을 뜻한다. 그런데 save() 는
     * 항상 도메인 스냅샷 전체를 detached 엔티티로 재구성해 merge 하므로, 스냅샷과 무관한 상태
     * 변경(suspend/resume/close/changeMasterLimit)으로 인한 재저장도 fromDomain() 이 매번
     * Instant.now() 를 새로 찍어버린다. Task 13 이 일 1회 재산정 스케줄러로 같은 계정을 주기적으로
     * 재저장하기 시작하면 이 컬럼이 "마지막 배치 실행 시각"으로 굳어, 존재 이유("사후에 왜 이
     * 한도였나 재현")가 무너진다 — 게다가 이미 쌓인 값은 소급 정정할 수 없다.
     *
     * <p>기존 행을 먼저 조회해 스냅샷 5개 필드가 신규 값과 동일하면 기존 screened_at 을 그대로
     * 유지하고, 다르거나(재심사로 실제 값이 바뀜) 신규 INSERT 면 fromDomain() 이 이미 찍어둔
     * Instant.now() 를 그대로 쓴다. 추가 SELECT 가 필요하지만 save() 는 개설·발급·한도변경에서만
     * 호출되는 비핫패스라 허용된다. findByIdForUpdate 로 이미 잠근 트랜잭션 안에서 호출돼도, 같은
     * 영속성 컨텍스트 1차 캐시에서 동일 관리 엔티티를 반환할 뿐 추가 락을 걸지 않으므로 락 순서
     * 문제를 일으키지 않는다.
     */
    private void preserveScreenedAtIfSnapshotUnchanged(Long id, CardAccountJpaEntity incoming) {
        if (id == null) {
            return;
        }
        repository.findById(id).ifPresent(existing -> {
            if (incoming.hasSameLimitSnapshot(existing)) {
                incoming.setScreenedAt(existing.getScreenedAt());
            }
        });
    }
}
