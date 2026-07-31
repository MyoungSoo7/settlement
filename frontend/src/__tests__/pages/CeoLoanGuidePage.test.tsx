import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { describe, it, expect } from "vitest";
import CeoLoanGuidePage from "@/pages/CeoLoanGuidePage";

/**
 * 이 화면은 CEO·관리자가 보는 상품 설명이다. 여기 적힌 수치는 대외 약속처럼 읽히므로
 * loan-service 의 실제 정책 상수와 어긋나면 안 된다. 아래 단언은 SecuredLoanPolicy 의
 * 값을 그대로 옮긴 것이고, 백엔드 정책이 바뀌면 이 테스트가 먼저 깨지는 것이 목적이다.
 *
 * 근거: loan-service/src/main/java/.../domain/SecuredLoanPolicy.java
 *   PERSONAL_CREDIT_LIMITS(:74-78) / CREDIT_SURCHARGE_PERCENTS(:80-85)
 *   SECURED_SURCHARGE_PERCENT(:38) / LTV 상수(:62-67) / earlyRepaymentFee(:306-318)
 */
const renderPage = () =>
  render(
    <MemoryRouter>
      <CeoLoanGuidePage />
    </MemoryRouter>,
  );

describe("CeoLoanGuidePage", () => {
  it("두 상품을 모두 설명한다", () => {
    renderPage();
    expect(
      screen.getByRole("heading", { name: /개인신용대출/ }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("heading", { name: /주택담보대출/ }),
    ).toBeInTheDocument();
  });

  it("개인신용대출 등급별 한도를 정책값 그대로 노출한다", () => {
    renderPage();
    expect(screen.getByText("1억 원")).toBeInTheDocument();
    expect(screen.getByText("5,000만 원")).toBeInTheDocument();
    expect(screen.getByText("3,000만 원")).toBeInTheDocument();
    expect(screen.getByText("1,000만 원")).toBeInTheDocument();
  });

  it("개인신용대출 등급별 가산금리를 노출한다", () => {
    renderPage();
    for (const surcharge of ["+1.5%p", "+2.5%p", "+4.0%p", "+6.0%p"]) {
      expect(screen.getByText(surcharge)).toBeInTheDocument();
    }
  });

  it("담보 유형별 인정비율(LTV)을 노출한다", () => {
    renderPage();
    expect(screen.getByText("70%")).toBeInTheDocument(); // 부동산
    expect(screen.getByText("95%")).toBeInTheDocument(); // 예금
    expect(screen.getByText("80%")).toBeInTheDocument(); // 채권
    expect(screen.getByText("60%")).toBeInTheDocument(); // 주식
  });

  it("담보대출 가산금리가 등급 무관 고정임을 밝힌다", () => {
    renderPage();
    expect(screen.getByText(/\+0\.8%p/)).toBeInTheDocument();
  });

  it("기준금리 출처와 폴백값을 밝힌다", () => {
    renderPage();
    expect(screen.getByText(/한국은행/)).toBeInTheDocument();
    expect(screen.getByText(/3\.5%/)).toBeInTheDocument();
  });

  it("마진콜·반대매매 임계를 노출한다", () => {
    renderPage();
    expect(screen.getByText(/140%/)).toBeInTheDocument();
    expect(screen.getByText(/120%/)).toBeInTheDocument();
  });

  it("주택담보는 마진콜 대상이 아님을 명시한다", () => {
    renderPage();
    expect(
      screen.getByText(/주택담보.*마진콜|마진콜.*금융자산담보만/),
    ).toBeInTheDocument();
  });

  it("중도상환수수료 산식을 노출한다", () => {
    renderPage();
    expect(screen.getByText(/1\.2%/)).toBeInTheDocument();
    expect(screen.getByText(/1,095일|3년/)).toBeInTheDocument();
  });

  /**
   * 이 화면의 수치는 loan-service 기본값을 옮긴 것이고, 배포에서 덮어쓰면
   * 실제 적용값과 달라진다. 리터럴만 단언하는 테스트는 그 드리프트를 잡지 못하므로,
   * "기본값이며 배포 설정으로 덮인다"는 고지 자체를 단언해 문구가 조용히
   * "현재 운영값" 으로 되돌아가는 것을 막는다. (PR #193 코드리뷰 P1 반영)
   */
  it("수치가 기본값이며 배포 설정으로 덮일 수 있음을 고지한다", () => {
    renderPage();
    expect(screen.getByText(/기본값으로 설정된 정책값/)).toBeInTheDocument();
    expect(
      screen.getByText(/app\.loan\.secured\.base-rate-percent/),
    ).toBeInTheDocument();
    expect(
      screen.getByText(/app\.loan\.secured\.real-estate-ltv/),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("heading", { name: /인정비율\(기본값\)/ }),
    ).toBeInTheDocument();
  });

  it("현재 운영값이라고 단정하지 않는다", () => {
    renderPage();
    expect(screen.queryByText(/현재 운영 중인 정책값/)).not.toBeInTheDocument();
  });

  it("아직 열리지 않은 기능을 구현 현황으로 구분해 표시한다", () => {
    renderPage();
    expect(
      screen.getByRole("heading", { name: /구현 현황/ }),
    ).toBeInTheDocument();
    expect(screen.getByText(/보증기관 보증담보/)).toBeInTheDocument();
    expect(screen.getByText(/재평가·마진콜|마진콜 판정/)).toBeInTheDocument();
  });
});
