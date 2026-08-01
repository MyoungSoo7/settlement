import { describe, it, expect } from "vitest";
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import CeoFundGuidePage from "@/pages/CeoFundGuidePage";
import {
  FUND_TYPES,
  ASSET_CLASSES,
  FUND_RULES,
  REVIEW_CHECKLIST,
  SOURCES,
} from "@/data/funds";

/** 탭 이름에 이모지가 붙으므로 부분일치 함수 매처를 쓴다. */
const tabNamed = (name: string) =>
  screen.getByRole("tab", {
    name: (accessibleName: string) => accessibleName.includes(name),
  });

describe("CeoFundGuidePage", () => {
  it("법령상 여섯 가지 펀드 종류가 근거 조문과 함께 표에 나온다", () => {
    render(<CeoFundGuidePage />);
    const table = screen.getByRole("table");

    for (const fund of FUND_TYPES) {
      expect(within(table).getByText(fund.name)).toBeInTheDocument();
      expect(within(table).getByText(fund.clause)).toBeInTheDocument();
    }
  });

  it("부동산·주식·채권 세 자산을 탭으로 제공한다", () => {
    render(<CeoFundGuidePage />);

    for (const asset of ASSET_CLASSES) {
      expect(tabNamed(asset.label)).toBeInTheDocument();
    }
  });

  it("자산 탭을 누르면 그 자산의 설명과 법적 제약으로 바뀐다", async () => {
    const user = userEvent.setup();
    render(<CeoFundGuidePage />);

    const bond = ASSET_CLASSES.find((a) => a.id === "bond")!;
    await user.click(tabNamed(bond.label));

    expect(screen.getByText(bond.oneLiner)).toBeInTheDocument();
    expect(screen.getByText(bond.liquidity)).toBeInTheDocument();
    for (const constraint of bond.legalConstraints) {
      expect(screen.getByText(constraint.rule)).toBeInTheDocument();
    }
  });

  it("부동산펀드가 중도 환매되지 않는다는 사실이 화면에 있다", async () => {
    const user = userEvent.setup();
    render(<CeoFundGuidePage />);

    const realEstate = ASSET_CLASSES.find((a) => a.id === "real-estate")!;
    await user.click(tabNamed(realEstate.label));

    expect(screen.getByText(realEstate.liquidity)).toHaveTextContent(
      "환매금지형",
    );
  });

  it("펀드 공통 규칙과 검토 체크리스트를 모두 보여준다", () => {
    render(<CeoFundGuidePage />);

    for (const rule of FUND_RULES) {
      expect(screen.getByText(rule.title)).toBeInTheDocument();
    }
    for (const row of REVIEW_CHECKLIST) {
      expect(screen.getByText(row.item)).toBeInTheDocument();
    }
  });

  it("출처를 외부 링크로 노출한다", () => {
    render(<CeoFundGuidePage />);

    for (const source of SOURCES) {
      const link = screen.getByRole("link", { name: source.label });
      expect(link).toHaveAttribute("href", source.url);
      expect(link).toHaveAttribute("rel", expect.stringContaining("noopener"));
    }
  });
});

/**
 * lenders 와 같은 이유 — 이 페이지가 주장하는 것은 법령상 사실이다.
 * 화면이 아니라 데이터가 틀리면 조용히 잘못된 정보를 내보내므로 사실을 테스트로 고정한다.
 */
describe("funds 데이터 불변식", () => {
  it("자본시장법 제229조의 여섯 종류를 빠짐없이 담는다", () => {
    expect(FUND_TYPES).toHaveLength(6);
    const ids = FUND_TYPES.map((f) => f.id);
    expect(new Set(ids).size).toBe(ids.length);
  });

  it("환매금지형 설정 의무가 있는 펀드는 환매 가능으로 표시하지 않는다", () => {
    // 법 제230조제5항 · 영 제242조제2항 — 부동산·특별자산·혼합자산
    for (const id of ["real-estate", "special-asset", "mixed-asset"]) {
      const fund = FUND_TYPES.find((f) => f.id === id)!;
      expect(fund.redeemable, fund.name).toBe(false);
    }
  });

  it("모든 펀드 종류에 근거 조문이 붙어 있다", () => {
    for (const fund of FUND_TYPES) {
      expect(fund.clause, fund.name).toContain("자본시장법 제229조");
    }
  });

  it("자산은 부동산·주식·채권 셋이고 모든 법적 제약에 근거가 있다", () => {
    expect(ASSET_CLASSES.map((a) => a.id)).toEqual([
      "real-estate",
      "equity",
      "bond",
    ]);
    for (const asset of ASSET_CLASSES) {
      expect(asset.legalConstraints.length, asset.label).toBeGreaterThan(0);
      for (const constraint of asset.legalConstraints) {
        expect(constraint.basis.trim(), constraint.rule).not.toBe("");
      }
    }
  });

  it("모든 공통 규칙에 근거가 붙어 있다", () => {
    expect(FUND_RULES.length).toBeGreaterThan(0);
    for (const rule of FUND_RULES) {
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
});
