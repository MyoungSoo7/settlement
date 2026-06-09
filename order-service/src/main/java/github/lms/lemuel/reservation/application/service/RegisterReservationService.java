package github.lms.lemuel.reservation.application.service;

import github.lms.lemuel.reservation.application.port.in.RegisterReservationUseCase;
import github.lms.lemuel.reservation.application.port.out.SaveReservationPort;
import github.lms.lemuel.reservation.domain.Reservation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class RegisterReservationService implements RegisterReservationUseCase {

    private final SaveReservationPort saveReservationPort;

    @Override
    public Reservation register(RegisterReservationCommand command) {
        log.info("시공 예약 등록 시작: companyId={}, scheduledDate={}",
                command.companyId(), command.scheduledDate());

        // 1. 도메인 생성 (필수 항목 검증)
        Reservation reservation = Reservation.register(
                command.companyId(),
                command.scheduledDate(),
                command.siteAddress(),
                command.siteManagerName(),
                command.siteManagerPhone(),
                command.constructionArea()
        );

        // 2. 선택 항목 반영
        reservation.setSitePassword(command.sitePassword());
        reservation.setProductId(command.productId());
        reservation.setWoodSpecies(command.woodSpecies());
        reservation.setBrand(command.brand());
        reservation.setProductName(command.productName());
        reservation.setProductSize(command.productSize());
        reservation.setFieldMeasured(command.fieldMeasured());
        reservation.setExpansion(command.expansion());
        reservation.setExpansionArea(command.expansionArea());
        reservation.setNewFloor(command.newFloor());
        reservation.setBaseboard(command.baseboard());
        reservation.setProtectionWork(command.protectionWork());
        reservation.setProtectionArea(command.protectionArea());
        reservation.setNote(command.note());

        // 3. 조건부 규칙 재검증 (확장→확장면적, 보양→보양평수)
        reservation.validate();

        // NOTE: 보양비/추가비용 자동계산은 pricing 도메인(2단계)에서 PricingPort 로 위임 예정.
        //       현재는 0 으로 저장된다.

        // 4. 저장
        Reservation saved = saveReservationPort.save(reservation);
        log.info("시공 예약 등록 완료: reservationId={}", saved.getId());
        return saved;
    }
}
