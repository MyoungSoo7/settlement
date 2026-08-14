package github.lms.lemuel.loan.application.port.out;

import github.lms.lemuel.loan.domain.LeaseContract;

/**
 * 리스·할부 이벤트 발행 아웃바운드 포트. 계약 개시 시 {@code LeaseActivated} 를 Outbox 에 기록해
 * {@code lemuel.loan.lease_activated} 로 발행한다.
 *
 * <p>발행 시점이 승인이 아니라 <b>개시</b>인 이유: 물건이 인도되어야 채권이 실재하고, 승인만 하고
 * 취소되는 계약이 실제로 있다. 소비측(계정계 GL 등)이 허위 채권을 잡지 않게 하려면 인도가 기준이다.
 */
public interface PublishLeaseEventPort {

    void publishActivated(LeaseContract contract);
}
