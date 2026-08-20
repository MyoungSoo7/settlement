package github.lms.lemuel.payment.adapter.out.external;

import github.lms.lemuel.payment.application.port.out.CashReceiptGatewayPort;
import github.lms.lemuel.payment.domain.CashReceipt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 현금영수증 발급 대행 모의 어댑터 — 실제 국세청/PG 연동이 붙기 전까지의 자리 표시자.
 *
 * <p>승인번호는 <b>결제 id 로부터 결정적으로</b> 만든다. 난수를 쓰면 같은 결제가 두 번 발급됐을 때
 * 서로 다른 번호가 남아 데이터만 봐서는 이중 발급을 구분할 수 없다. 결정적 번호면 중복이 눈에 띈다.
 *
 * <p>발급·취소마다 WARN 을 남기는 이유: 모의 구현이 운영에 그대로 올라가면 <b>고객에게는 발급됐다고
 * 표시되는데 국세청에는 아무것도 없는</b> 상태가 된다. 실제 연동이 붙으면 이 클래스를 그 구현으로
 * 대체하고(같은 포트), 이 경고가 사라지는 것으로 전환을 확인한다.
 */
@Component
public class MockCashReceiptGatewayAdapter implements CashReceiptGatewayPort {

    private static final Logger log = LoggerFactory.getLogger(MockCashReceiptGatewayAdapter.class);

    @Override
    public Result issue(CashReceipt receipt) {
        log.warn("현금영수증 모의 발급 — 실제 국세청 연동 아님: paymentId={}, amount={}",
                receipt.getPaymentId(), receipt.getTotalAmount());
        return Result.issued(String.format("MOCK-%010d", receipt.getPaymentId()));
    }

    @Override
    public Result cancel(CashReceipt receipt, String reason) {
        log.warn("현금영수증 모의 취소 — 실제 국세청 연동 아님: paymentId={}, approvalNumber={}, reason={}",
                receipt.getPaymentId(), receipt.getApprovalNumber(), reason);
        return Result.ok();
    }
}
