import { describe, it, expect } from "vitest";
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import CeoLenderGuidePage from "@/pages/CeoLenderGuidePage";
import {
  LENDER_TIERS,
  RATE_RULES,
  SOURCES,
  LEGAL_MAX_RATE_PERCENT,
  DEPOSIT_PROTECTION_LIMIT_LABEL,
} from "@/data/lenders";

/**
 * 업권명에 "캐피탈 (여신전문금융회사)" 처럼 괄호가 들어 있어 RegExp 로 만들면
 * 메타문자로 해석돼 엉뚱하게 매칭된다. 부분일치 함수 매처를 쓴다.
 */
const tabNamed = (name: string) =>
  screen.getByRole("tab", {
    name: (accessibleName: string) => accessibleName.includes(name),
  });

describe("CeoLenderGuidePage", () => {
  it("네 업권이 모두 표에 나온다", () => {
    render(<CeoLenderGuidePage />);
    const table = screen.getByRole("table");

    for (const tier of LENDER_TIERS) {
      expect(within(table).getByText(tier.name)).toBeInTheDocument();
      expect(within(table).getByText(tier.law)).toBeInTheDocument();
    }
  });

  it("법정 최고금리와 예금보호한도를 데이터에서 그대로 보여준다", () => {
    render(<CeoLenderGuidePage />);

    expect(
      screen.getByText(`연 ${LEGAL_MAX_RATE_PERCENT}%`),
    ).toBeInTheDocument();
    expect(
      screen.getByText(DEPOSIT_PROTECTION_LIMIT_LABEL),
    ).toBeInTheDocument();
  });

  it("업권 탭을 누르면 그 업권의 상세로 바뀐다", async () => {
    const user = userEvent.setup();
    render(<CeoLenderGuidePage />);

    const capital = LENDER_TIERS.find((t) => t.id === "capital")!;
    await user.click(tabNamed(capital.name));

    expect(screen.getByText(capital.oneLiner)).toBeInTheDocument();
    expect(screen.getByText(capital.depositProtection)).toBeInTheDocument();
  });

  it("저축은행이 예금자보호 대상이라는 사실이 화면에 있다", async () => {
    const user = userEvent.setup();
    render(<CeoLenderGuidePage />);

    const savings = LENDER_TIERS.find((t) => t.id === "savings-bank")!;
    await user.click(tabNamed(savings.name));

    expect(screen.getByText(savings.depositProtection)).toHaveTextContent(
      "1억원",
    );
  });

  it("출처를 외부 링크로 노출한다", () => {
    render(<CeoLenderGuidePage />);

    for (const source of SOURCES) {
      const link = screen.getByRole("link", { name: source.label });
      expect(link).toHaveAttribute("href", source.url);
      expect(link).toHaveAttribute("rel", expect.stringContaining("noopener"));
    }
  });
});

/**
 * 이 페이지가 주장하는 것은 법령상 사실이다. 화면이 아니라 데이터가 틀리면 조용히
 * 잘못된 정보를 내보내게 되므로, 사실 자체를 테스트로 고정한다.
 */
describe("lenders 데이터 불변식", () => {
  it("예금을 받지 않는 업권은 예금자보호 대상이 될 수 없다", () => {
    for (const tier of LENDER_TIERS) {
      if (!tier.takesDeposits) {
        expect(tier.depositProtected, `${tier.name}`).toBe(false);
      }
    }
  });

  it("모든 금리 규칙에 근거 조문이 붙어 있다", () => {
    expect(RATE_RULES.length).toBeGreaterThan(0);
    for (const rule of RATE_RULES) {
      expect(rule.basis.trim(), rule.title).not.toBe("");
    }
  });

  it("출처는 모두 https 이고 중복이 없다", () => {
    expect(SOURCES.length).toBeGreaterThan(0);
    for (const source of SOURCES) {
      expect(source.url.startsWith("https://"), source.label).toBe(true);
    }
    expect(new Set(SOURCES.map((s) => s.url)).size).toBe(SOURCES.length);
  });

  it("업권 id 는 고유하다", () => {
    const ids = LENDER_TIERS.map((t) => t.id);
    expect(new Set(ids).size).toBe(ids.length);
  });
});
