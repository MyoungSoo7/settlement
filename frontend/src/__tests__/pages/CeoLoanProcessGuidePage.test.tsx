import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { describe, it, expect } from "vitest";
import CeoLoanProcessGuidePage from "@/pages/CeoLoanProcessGuidePage";

/**
 * 이 화면은 CEO·관리자가 보는 절차 설명이다. 여기 적힌 수치와 절차는 대외 약속처럼 읽히므로
 * loan-service 의 실제 구현과 어긋나면 안 된다. 아래 단언은 정책 상수와 상태 전이표를 그대로
 * 옮긴 것이고, 백엔드가 바뀌면 이 테스트가 먼저 깨지는 것이 목적이다.
 *
 * 특히 "절차" 는 수치보다 조용히 틀리기 쉽다 — 승인 단계가 없는 상품에 승인 단계를 적거나,
 * 자동 배치가 없는 상품에 자동 처리를 약속하는 식이다. 그래서 산식뿐 아니라 절차 문구 자체를
 * 단언한다.
 *
 * 근거: loan-service/src/main/java/.../domain/CorporateCreditPolicy.java (배점·등급·계수)
 *       loan-service/src/main/java/.../domain/SecuredLoanPolicy.java (CB 등급·한도·가산·중도상환)
 *       loan-service/src/main/java/.../domain/RepaymentSchedule.java (상환방식·이자·라운딩)
 *       loan-service/src/main/java/.../domain/SecuredLoanStatus.java (연체 → 기한이익상실)
 *       loan-service/src/main/java/.../application/service/LoanOverdueScheduler.java (배치 기본값)
 */
const renderPage = () =>
  render(
    <MemoryRouter>
      <CeoLoanProcessGuidePage />
    </MemoryRouter>,
  );

describe("CeoLoanProcessGuidePage", () => {
  it("심사·신용평가·상환 세 주제를 모두 다룬다", () => {
    renderPage();
    expect(
      screen.getByRole("heading", { name: /대출심사/ }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("heading", { name: /신용평가 ①/ }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("heading", { name: /신용평가 ②/ }),
    ).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: /상환 ①/ })).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: /상환 ②/ })).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: /상환 ③/ })).toBeInTheDocument();
  });

  it("기업 신용점수 배점을 정책값 그대로 노출한다", () => {
    renderPage();
    // 안정성 40 + 수익성 40(영업이익률 20 + ROA 20) + 평판 20 = 100
    expect(screen.getByText("40점")).toBeInTheDocument();
    expect(screen.getAllByText("20점")).toHaveLength(3);
    // 정확 일치 — "부채비율을 산출할 수 없는 경우" 문단과 구분해 표의 지표 셀만 집는다.
    expect(screen.getByText("부채비율")).toBeInTheDocument();
    expect(screen.getByText("영업이익률")).toBeInTheDocument();
    expect(screen.getByText("ROA")).toBeInTheDocument();
  });

  it("기업 등급별 한도계수·수수료할증을 노출한다", () => {
    renderPage();
    for (const factor of ["1.0", "0.8", "0.6", "0.3"]) {
      expect(screen.getByText(factor)).toBeInTheDocument();
    }
    for (const surcharge of ["1.0배", "1.1배", "1.25배", "1.5배"]) {
      expect(screen.getByText(surcharge)).toBeInTheDocument();
    }
  });

  it("개인신용 CB 등급별 한도·가산금리를 노출한다", () => {
    renderPage();
    expect(screen.getByText("1억 원")).toBeInTheDocument();
    expect(screen.getByText("5,000만 원")).toBeInTheDocument();
    expect(screen.getByText("3,000만 원")).toBeInTheDocument();
    expect(screen.getByText("1,000만 원")).toBeInTheDocument();
    for (const surcharge of ["+1.5%p", "+2.5%p", "+4.0%p", "+6.0%p"]) {
      expect(screen.getByText(surcharge)).toBeInTheDocument();
    }
  });

  it("E 등급이 두 신용평가 모두에서 대출 불가임을 밝힌다", () => {
    renderPage();
    expect(screen.getAllByText("대출 불가")).toHaveLength(2);
    expect(screen.getByText("35점 미만")).toBeInTheDocument(); // 기업
    expect(screen.getByText("550점 미만")).toBeInTheDocument(); // 개인신용
  });

  /**
   * 등급 미상을 개인신용은 차단(fail-closed)하고 선정산은 통과(fail-open)시킨다. 이 비대칭은
   * 의도된 설계라, 한쪽으로 뭉뚱그리면 심사 기준을 잘못 안내하게 된다.
   */
  it("등급 미상을 상품마다 다르게 다룬다는 점을 명시한다", () => {
    renderPage();
    expect(
      screen.getByText(/개인신용대출은 등급을 알 수 없으면 거절한다/),
    ).toBeInTheDocument();
    expect(
      screen.getByText(
        /선정산 대출은 셀러 평판\s*등급이 없어도 한도를 깎지 않고/,
      ),
    ).toBeInTheDocument();
  });

  it("세 가지 상환방식을 모두 설명한다", () => {
    renderPage();
    expect(screen.getByText("만기일시상환")).toBeInTheDocument();
    expect(screen.getByText("원리금균등상환")).toBeInTheDocument();
    expect(screen.getByText("원금균등상환")).toBeInTheDocument();
  });

  it("이자가 잔액 기준 후취임을 산식으로 밝힌다", () => {
    renderPage();
    expect(screen.getByText(/월이율 = 연이율 ÷ 12/)).toBeInTheDocument();
    expect(
      screen.getByText(/직전 회차 상환 후 잔액 × 월이율/),
    ).toBeInTheDocument();
  });

  it("마지막 회차가 잔여 원금을 흡수한다는 라운딩 규칙을 밝힌다", () => {
    renderPage();
    expect(
      screen.getByText(/마지막 회차가 잔여 원금을 흡수/),
    ).toBeInTheDocument();
    expect(screen.getByText(/반올림하지 않고 거부/)).toBeInTheDocument();
  });

  it("중도상환수수료 산식과 면제 시점을 노출한다", () => {
    renderPage();
    expect(
      screen.getByText(/중도상환액 × 1\.2% × \(1,095 − 경과일\) ÷ 1,095/),
    ).toBeInTheDocument();
    expect(screen.getByText(/3년\(1,095일\)/)).toBeInTheDocument();
  });

  /**
   * 중도상환수수료는 상환액에서 차감하는 것이 아니라 위에 더해 청구한다. 이걸 뒤집어 적으면
   * 차주가 실제 내야 할 금액을 과소 안내하게 된다.
   */
  it("중도상환수수료가 상환액에 더해 청구됨을 밝힌다", () => {
    renderPage();
    expect(
      screen.getByText(/총 수취액 = 중도상환액 \+ 수수료/),
    ).toBeInTheDocument();
  });

  it("연체·상각 배치 기본값을 노출한다", () => {
    renderPage();
    // 본문과 "절차상 주의" 양쪽에 나오므로 존재만 확인한다.
    expect(screen.getAllByText(/새벽 3시\(KST\)/).length).toBeGreaterThan(0);
    expect(screen.getByText(/유예 기간 기본값은 0일/)).toBeInTheDocument();
    expect(screen.getByText("30일")).toBeInTheDocument();
  });

  /**
   * 자동 연체 배치는 선정산 대출에만 있다. 이걸 전체 상품의 기능처럼 적으면 담보·개인신용
   * 대출이 방치되는데도 자동으로 처리되고 있다고 오인하게 된다.
   */
  it("자동 연체 배치가 선정산 대출 전용임을 구분해 밝힌다", () => {
    renderPage();
    expect(
      screen.getByRole("heading", {
        name: /연체와 상각 \(선정산 대출\)|상환 ②/,
      }),
    ).toBeInTheDocument();
    expect(
      screen.getByText(/자동 연체 배치는 선정산 대출에만 있다/),
    ).toBeInTheDocument();
    expect(
      screen.getByText(/연체와 기한이익상실 전이는 운영자가 직접/),
    ).toBeInTheDocument();
  });

  it("기한이익상실이 연체를 거쳐야만 도달함을 밝힌다", () => {
    renderPage();
    expect(
      screen.getByText(/기한이익상실은 연체를 거쳐야만 도달한다/),
    ).toBeInTheDocument();
  });

  /**
   * 상품마다 승인 단계의 유무가 다르다. 선정산·기업은 실행이 승인을 겸하지만 담보·개인신용은
   * 운영자가 명시적으로 승인한다. 뭉뚱그리면 없는 승인 대기 큐를 약속하게 된다.
   */
  it("승인 단계 유무가 상품마다 다르다는 점을 갈래로 구분한다", () => {
    renderPage();
    expect(
      screen.getAllByText(/실행 요청이 승인을 겸한다/).length,
    ).toBeGreaterThanOrEqual(2);
    expect(
      screen.getByText(/운영자가 승인 · 거절 · 실행을 명시적으로 수행한다/),
    ).toBeInTheDocument();
  });

  it("기업 신용대출에 연체·상각 상태가 없음을 밝힌다", () => {
    renderPage();
    expect(screen.getByText(/연체·상각 상태 자체가 없음/)).toBeInTheDocument();
  });

  /**
   * 선정산·기업은 수수료 선취(잔액 = 원금 + 수수료), 담보·개인신용은 회차 이자 후취(잔액 = 원금).
   * 이 차이를 빼면 회차 상환표가 모든 상품에 적용되는 것처럼 읽힌다.
   */
  it("수수료 선취와 회차 이자 후취의 차이를 밝힌다", () => {
    renderPage();
    expect(
      screen.getByText(/실행 시 잔액 = 원금 \+ 수수료/),
    ).toBeInTheDocument();
    expect(screen.getByText("실행 시 잔액 = 원금")).toBeInTheDocument();
  });

  it("선정산 상환이 FIFO 자동 차감임을 밝힌다", () => {
    renderPage();
    expect(screen.getByText(/오래된 건부터\(FIFO\)/)).toBeInTheDocument();
    expect(
      screen.getByText(/차감할 대출이 하나도 없어도 통지는 발행된다/),
    ).toBeInTheDocument();
  });

  /**
   * 이 화면의 수치는 loan-service 기본값을 옮긴 것이고, 배포에서 덮어쓰면 실제 적용값과
   * 달라진다. 「대출 상품 안내」와 같은 고지 규율을 지킨다(PR #193 코드리뷰 P1 반영).
   */
  it("수치가 기본값이며 배포 설정으로 덮일 수 있음을 고지한다", () => {
    renderPage();
    expect(screen.getByText(/기본값으로 설정된 정책값/)).toBeInTheDocument();
    expect(
      screen.getByText(/app\.loan\.secured\.base-rate-percent/),
    ).toBeInTheDocument();
    expect(screen.getByText(/app\.loan\.ltv/)).toBeInTheDocument();
  });

  it("현재 운영값이라고 단정하지 않는다", () => {
    renderPage();
    expect(screen.queryByText(/현재 운영 중인 정책값/)).not.toBeInTheDocument();
  });

  it("아직 열리지 않은 경로를 절차상 주의로 구분해 표시한다", () => {
    renderPage();
    expect(
      screen.getByRole("heading", { name: /절차상 주의/ }),
    ).toBeInTheDocument();
    expect(
      screen.getByText(/담보 처분·대위변제는 화면에서 실행할 수 없다/),
    ).toBeInTheDocument();
    expect(
      screen.getByText(/상환표 계산은 아직 화면에 붙어 있지 않다/),
    ).toBeInTheDocument();
  });
});
