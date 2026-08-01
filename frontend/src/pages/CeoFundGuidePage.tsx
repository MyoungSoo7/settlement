import React, { useState } from "react";
import Card from "@/components/Card";
import {
  FUND_TYPES,
  ASSET_CLASSES,
  FUND_RULES,
  REVIEW_CHECKLIST,
  SOURCES,
  ASSET_RATIO_PERCENT,
  DIVERSIFICATION_LIMIT_PERCENT,
  PRIVATE_FUND_MIN_INVESTMENT_LABEL,
  type AssetClass,
} from "@/data/funds";

const CeoFundGuidePage: React.FC = () => {
  const [selectedId, setSelectedId] = useState<AssetClass["id"]>(
    ASSET_CLASSES[0].id,
  );
  const selected =
    ASSET_CLASSES.find((a) => a.id === selectedId) ?? ASSET_CLASSES[0];

  return (
    <div className="space-y-6">
      {/* ── 헤더 ─────────────────────────────────────────────── */}
      <div>
        <h1 className="text-2xl font-bold text-gray-900">자산운용펀드 안내</h1>
        <p className="mt-1 text-sm text-gray-500">
          펀드가 법적으로 무엇인지, 그리고 펀드를 통해 부동산 · 주식 · 채권에
          투자할 때 각각 무엇이 달라지는지 정리했습니다.
        </p>
      </div>

      {/* ── 핵심 두 줄 ───────────────────────────────────────── */}
      <div className="grid gap-4 sm:grid-cols-2">
        <div className="rounded-lg border border-indigo-200 bg-indigo-50 p-4">
          <p className="text-xs font-semibold text-indigo-700">
            펀드를 가르는 기준
          </p>
          <p className="mt-1 text-2xl font-bold text-indigo-900">
            운용대상 {ASSET_RATIO_PERCENT}% 초과
          </p>
          <p className="mt-1 text-xs text-indigo-800">
            어디에 절반 넘게 넣었는지가 그 펀드의 법령상 종류를 정합니다. 상품
            이름이 아니라 편입 비율입니다.
          </p>
        </div>
        <div className="rounded-lg border border-red-200 bg-red-50 p-4">
          <p className="text-xs font-semibold text-red-700">예금자보호</p>
          <p className="mt-1 text-2xl font-bold text-red-900">해당 없음</p>
          <p className="mt-1 text-xs text-red-800">
            펀드는 운용 결과를 그대로 돌려주는 실적배당상품입니다. 원금이
            보장되지 않습니다.
          </p>
        </div>
      </div>

      {/* ── 자산운용펀드란 ───────────────────────────────────── */}
      <Card title="자산운용펀드란 무엇인가">
        <p className="text-sm text-gray-700">
          법에서 부르는 정식 명칭은 <strong>집합투자기구</strong>입니다. 2인
          이상에게 투자를 권유해 모은 재산을, 투자자로부터 일상적인 운용지시를
          받지 않고 운용한 뒤 그 결과를 투자자에게 그대로 귀속시키는 구조입니다.
        </p>
        <div className="mt-4 grid gap-3 sm:grid-cols-3">
          <div className="rounded-lg bg-slate-50 p-3">
            <p className="text-xs text-slate-500">판매사</p>
            <p className="mt-0.5 text-sm font-semibold text-slate-900">
              은행 · 증권사
            </p>
            <p className="mt-1 text-xs text-slate-600">
              상품을 권유하고 팝니다. 굴리지도, 보관하지도 않습니다.
            </p>
          </div>
          <div className="rounded-lg bg-slate-50 p-3">
            <p className="text-xs text-slate-500">집합투자업자</p>
            <p className="mt-0.5 text-sm font-semibold text-slate-900">
              자산운용사
            </p>
            <p className="mt-1 text-xs text-slate-600">
              무엇을 사고팔지 결정합니다. 돈을 직접 들고 있지 않습니다.
            </p>
          </div>
          <div className="rounded-lg bg-slate-50 p-3">
            <p className="text-xs text-slate-500">신탁업자</p>
            <p className="mt-0.5 text-sm font-semibold text-slate-900">
              보관 · 관리
            </p>
            <p className="mt-1 text-xs text-slate-600">
              재산을 따로 맡아 둡니다. 운용사가 망해도 펀드 재산은 남습니다.
            </p>
          </div>
        </div>
        <p className="mt-4 text-sm text-gray-600">
          이 셋이 다른 회사인 것은 관행이 아니라 제도입니다. 그래서 운용사가
          부실해지는 것과 내 펀드의 손실은 원인이 다릅니다.
        </p>
      </Card>

      {/* ── 법령상 종류 ──────────────────────────────────────── */}
      <Card title="법이 정한 여섯 가지 종류">
        <div className="overflow-x-auto">
          <table className="min-w-full text-sm">
            <thead>
              <tr className="border-b border-gray-200 text-left text-xs text-gray-500">
                <th className="py-2 pr-4 font-medium">종류</th>
                <th className="py-2 pr-4 font-medium">근거 조문</th>
                <th className="py-2 pr-4 font-medium">편입 기준</th>
                <th className="py-2 pr-4 font-medium whitespace-nowrap">
                  중도 환매
                </th>
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-100">
              {FUND_TYPES.map((fund) => (
                <tr key={fund.id} className="align-top">
                  <td className="py-3 pr-4">
                    <div className="flex items-center gap-2">
                      <span aria-hidden="true">{fund.icon}</span>
                      <span className="font-semibold text-gray-900">
                        {fund.name}
                      </span>
                    </div>
                    <span className="mt-1 block text-xs text-gray-500">
                      {fund.alias}
                    </span>
                  </td>
                  <td className="py-3 pr-4 text-gray-700">{fund.clause}</td>
                  <td className="py-3 pr-4 text-gray-600">{fund.ratioRule}</td>
                  <td className="py-3 pr-4">
                    <span
                      className={
                        fund.redeemable
                          ? "font-semibold text-emerald-700"
                          : "font-semibold text-amber-700"
                      }
                    >
                      {fund.redeemable ? "가능" : "제한"}
                    </span>
                    <span className="mt-1 block text-xs text-gray-500">
                      {fund.redemptionNote}
                    </span>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <p className="mt-3 text-xs text-gray-500">
          자본시장법 제229조는 2025.9.16 개정(법률 제21061호)으로{" "}
          <strong>기업성장집합투자기구(BDC)</strong>가 추가되어 2026.3.17부터
          여섯 종류가 되었습니다.
        </p>
      </Card>

      {/* ── 자산별 상세 ──────────────────────────────────────── */}
      <Card title="펀드로 부동산 · 주식 · 채권에 투자한다는 것">
        <div
          className="flex flex-wrap gap-2"
          role="tablist"
          aria-label="자산 선택"
        >
          {ASSET_CLASSES.map((asset) => {
            const active = asset.id === selected.id;
            return (
              <button
                key={asset.id}
                type="button"
                role="tab"
                aria-selected={active}
                onClick={() => setSelectedId(asset.id)}
                className={`rounded-lg border px-3 py-2 text-sm font-medium transition-colors ${
                  active
                    ? "border-gray-900 bg-gray-900 text-white"
                    : "border-gray-200 bg-white text-gray-700 hover:bg-gray-50"
                }`}
              >
                <span aria-hidden="true" className="mr-1">
                  {asset.icon}
                </span>
                {asset.label}
              </button>
            );
          })}
        </div>

        <div className="mt-5 space-y-5">
          <div>
            <p className="text-base font-semibold text-gray-900">
              {selected.oneLiner}
            </p>
            <p className="mt-1 text-xs text-gray-500">{selected.vehicle}</p>
          </div>

          <div className="grid gap-4 sm:grid-cols-2">
            <div className="rounded-lg border border-emerald-200 bg-emerald-50 p-3">
              <p className="text-xs font-semibold text-emerald-800">
                수익이 나오는 곳
              </p>
              <ul className="mt-1.5 space-y-1">
                {selected.returnSources.map((s) => (
                  <li key={s} className="text-sm text-emerald-900">
                    · {s}
                  </li>
                ))}
              </ul>
            </div>
            <div className="rounded-lg border border-amber-200 bg-amber-50 p-3">
              <p className="text-xs font-semibold text-amber-800">
                손실이 나오는 곳
              </p>
              <ul className="mt-1.5 space-y-1">
                {selected.risks.map((r) => (
                  <li key={r} className="text-sm text-amber-900">
                    · {r}
                  </li>
                ))}
              </ul>
            </div>
          </div>

          <div className="rounded-lg bg-slate-50 p-3">
            <p className="text-xs text-slate-500">돈을 빼는 방법</p>
            <p className="mt-0.5 text-sm text-slate-900">
              {selected.liquidity}
            </p>
          </div>

          <div>
            <p className="text-xs font-semibold text-gray-500">
              법이 강제하는 것
            </p>
            <ul className="mt-2 space-y-3">
              {selected.legalConstraints.map((c) => (
                <li key={c.rule} className="border-l-2 border-gray-900 pl-3">
                  <p className="text-sm text-gray-800">{c.rule}</p>
                  <p className="mt-0.5 text-xs text-gray-400">{c.basis}</p>
                </li>
              ))}
            </ul>
          </div>

          <div>
            <p className="text-xs font-semibold text-gray-500">
              반드시 확인할 것
            </p>
            <ul className="mt-1.5 space-y-1">
              {selected.keyQuestions.map((q) => (
                <li key={q} className="text-sm text-gray-700">
                  · {q}
                </li>
              ))}
            </ul>
          </div>
        </div>
      </Card>

      {/* ── 공통 규칙 ────────────────────────────────────────── */}
      <Card title="펀드에 공통으로 적용되는 규칙">
        <ul className="space-y-4">
          {FUND_RULES.map((rule) => (
            <li key={rule.title} className="border-l-2 border-gray-900 pl-3">
              <p className="text-sm font-semibold text-gray-900">
                {rule.title}
              </p>
              <p className="mt-1 text-sm text-gray-600">{rule.detail}</p>
              <p className="mt-1 text-xs text-gray-400">{rule.basis}</p>
            </li>
          ))}
        </ul>
      </Card>

      {/* ── 검토 체크리스트 ──────────────────────────────────── */}
      <Card title="가입 전에 설명서에서 찾아볼 것">
        <ul className="space-y-3">
          {REVIEW_CHECKLIST.map((row) => (
            <li key={row.item} className="flex gap-3">
              <span
                aria-hidden="true"
                className="mt-1 h-1.5 w-1.5 flex-shrink-0 rounded-full bg-gray-900"
              />
              <span>
                <span className="block text-sm font-semibold text-gray-900">
                  {row.item}
                </span>
                <span className="block text-sm text-gray-600">{row.why}</span>
              </span>
            </li>
          ))}
        </ul>
      </Card>

      {/* ── 대출과의 관계 ────────────────────────────────────── */}
      <Card title="대출과 무엇이 다른가">
        <p className="text-sm text-gray-700">
          <strong>대출관리</strong> 메뉴의 선정산·기업대출은 회사가{" "}
          <strong>돈을 빌리는</strong> 쪽입니다. 갚을 금액과 날짜가 정해져 있고,
          그 대가인 이자는 법정 최고금리의 적용을 받습니다.
        </p>
        <p className="mt-2 text-sm text-gray-700">
          반면 펀드는 회사가 여유자금을 <strong>운용하는</strong> 쪽입니다.
          정해진 수익률이 없고, 잘못되면 원금이 줄어듭니다. 그래서 대출 심사에서
          보는 것(상환 능력)과 펀드에서 보는 것(편입 자산과 환매 조건)이
          다릅니다.
        </p>
        <p className="mt-2 text-sm text-gray-700">
          부동산펀드처럼 존속기간 동안 자금이 잠기는 상품에 운전자금을 넣으면,
          정작 대출 상환 시점에 그 돈을 꺼낼 수 없습니다. 두 메뉴를 나란히 두는
          이유입니다.
        </p>
      </Card>

      {/* ── 출처 ─────────────────────────────────────────────── */}
      <Card title="출처">
        <ul className="space-y-1.5">
          {SOURCES.map((s) => (
            <li key={s.url}>
              <a
                href={s.url}
                target="_blank"
                rel="noopener noreferrer"
                className="text-sm text-blue-600 hover:underline"
              >
                {s.label}
              </a>
            </li>
          ))}
        </ul>
        <p className="mt-3 text-xs text-gray-500">
          이 페이지는 법령과 감독기관 발표에 근거한 일반 정보이며 특정 상품에
          대한 투자 권유나 자문이 아닙니다. 실제 조건은 상품·운용사·클래스에
          따라 달라지므로 투자설명서를 확인해야 합니다. 일반투자자가 사모펀드에
          투자하려면 최소 {PRIVATE_FUND_MIN_INVESTMENT_LABEL} 이상을 투자하는
          적격투자자여야 하며, 공모펀드의 동일종목 한도{" "}
          {DIVERSIFICATION_LIMIT_PERCENT}%는 사모펀드에 그대로 적용되지
          않습니다.
        </p>
      </Card>
    </div>
  );
};

export default CeoFundGuidePage;
