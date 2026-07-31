import React, { useState } from "react";
import Card from "@/components/Card";
import {
  LENDER_TIERS,
  RATE_RULES,
  SOURCES,
  LEGAL_MAX_RATE_PERCENT,
  DEPOSIT_PROTECTION_LIMIT_LABEL,
  type LenderTier,
} from "@/data/lenders";

const groupBadgeClass = (group: LenderTier["group"]) => {
  switch (group) {
    case "제1금융권":
      return "bg-blue-100 text-blue-800";
    case "제2금융권":
      return "bg-amber-100 text-amber-800";
    default:
      return "bg-red-100 text-red-800";
  }
};

const YesNo: React.FC<{ value: boolean }> = ({ value }) => (
  <span className={value ? "text-emerald-700 font-semibold" : "text-slate-400"}>
    {value ? "가능" : "불가"}
  </span>
);

const CeoLenderGuidePage: React.FC = () => {
  const [selectedId, setSelectedId] = useState<string>(LENDER_TIERS[0].id);
  const selected =
    LENDER_TIERS.find((t) => t.id === selectedId) ?? LENDER_TIERS[0];

  return (
    <div className="space-y-6">
      {/* ── 헤더 ─────────────────────────────────────────────── */}
      <div>
        <h1 className="text-2xl font-bold text-gray-900">대출기관 안내</h1>
        <p className="mt-1 text-sm text-gray-500">
          은행 · 저축은행 · 캐피탈 · 대부업이 무엇이 어떻게 다른지, 그리고
          금리와 예금자보호가 법적으로 어떻게 정해지는지 정리했습니다.
        </p>
      </div>

      {/* ── 핵심 두 줄 ───────────────────────────────────────── */}
      <div className="grid gap-4 sm:grid-cols-2">
        <div className="rounded-lg border border-red-200 bg-red-50 p-4">
          <p className="text-xs font-semibold text-red-700">법정 최고금리</p>
          <p className="mt-1 text-2xl font-bold text-red-900">
            연 {LEGAL_MAX_RATE_PERCENT}%
          </p>
          <p className="mt-1 text-xs text-red-800">
            등록 대부업자와 여신금융기관 모두 동일합니다. 초과분은 무효입니다.
          </p>
        </div>
        <div className="rounded-lg border border-blue-200 bg-blue-50 p-4">
          <p className="text-xs font-semibold text-blue-700">예금자보호 한도</p>
          <p className="mt-1 text-2xl font-bold text-blue-900">
            {DEPOSIT_PROTECTION_LIMIT_LABEL}
          </p>
          <p className="mt-1 text-xs text-blue-800">
            2025년 9월 1일부터 상향되었습니다. 저축은행도 은행과 동일합니다.
          </p>
        </div>
      </div>

      {/* ── 한눈 비교 ────────────────────────────────────────── */}
      <Card title="한눈에 비교">
        <div className="overflow-x-auto">
          <table className="min-w-full text-sm">
            <thead>
              <tr className="border-b border-gray-200 text-left text-xs text-gray-500">
                <th className="py-2 pr-4 font-medium">구분</th>
                <th className="py-2 pr-4 font-medium">근거법</th>
                <th className="py-2 pr-4 font-medium whitespace-nowrap">
                  예금 수신
                </th>
                <th className="py-2 pr-4 font-medium whitespace-nowrap">
                  예금자보호
                </th>
                <th className="py-2 pr-4 font-medium">자금조달</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-100">
              {LENDER_TIERS.map((tier) => (
                <tr key={tier.id} className="align-top">
                  <td className="py-3 pr-4">
                    <div className="flex items-center gap-2">
                      <span aria-hidden="true">{tier.icon}</span>
                      <span className="font-semibold text-gray-900">
                        {tier.name}
                      </span>
                    </div>
                    <span
                      className={`mt-1 inline-block rounded px-1.5 py-0.5 text-[11px] font-medium ${groupBadgeClass(tier.group)}`}
                    >
                      {tier.group}
                    </span>
                  </td>
                  <td className="py-3 pr-4 text-gray-700">{tier.law}</td>
                  <td className="py-3 pr-4 whitespace-nowrap">
                    <YesNo value={tier.takesDeposits} />
                  </td>
                  <td className="py-3 pr-4 whitespace-nowrap">
                    <span
                      className={
                        tier.depositProtected
                          ? "font-semibold text-emerald-700"
                          : "text-slate-400"
                      }
                    >
                      {tier.depositProtected ? "대상" : "해당 없음"}
                    </span>
                  </td>
                  <td className="py-3 pr-4 text-gray-600">{tier.funding}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <p className="mt-3 text-xs text-gray-500">
          예금자보호는 <strong>예금을 받는 곳</strong>에만 있습니다.
          캐피탈·대부업은 애초에 예금을 받지 않으므로 보호 대상이 아니며, 이것은
          회사가 부실하다는 뜻이 아닙니다.
        </p>
      </Card>

      {/* ── 업권별 상세 ──────────────────────────────────────── */}
      <Card title="업권별 상세">
        <div
          className="flex flex-wrap gap-2"
          role="tablist"
          aria-label="업권 선택"
        >
          {LENDER_TIERS.map((tier) => {
            const active = tier.id === selected.id;
            return (
              <button
                key={tier.id}
                type="button"
                role="tab"
                aria-selected={active}
                onClick={() => setSelectedId(tier.id)}
                className={`rounded-lg border px-3 py-2 text-sm font-medium transition-colors ${
                  active
                    ? "border-gray-900 bg-gray-900 text-white"
                    : "border-gray-200 bg-white text-gray-700 hover:bg-gray-50"
                }`}
              >
                <span aria-hidden="true" className="mr-1">
                  {tier.icon}
                </span>
                {tier.name}
              </button>
            );
          })}
        </div>

        <div className="mt-5 space-y-4">
          <p className="text-base font-semibold text-gray-900">
            {selected.oneLiner}
          </p>

          <dl className="grid gap-3 sm:grid-cols-2">
            <div className="rounded-lg bg-slate-50 p-3">
              <dt className="text-xs text-slate-500">감독·등록</dt>
              <dd className="mt-0.5 text-sm text-slate-900">
                {selected.supervisor}
              </dd>
            </div>
            <div className="rounded-lg bg-slate-50 p-3">
              <dt className="text-xs text-slate-500">금리 상한</dt>
              <dd className="mt-0.5 text-sm text-slate-900">
                {selected.rateCeiling}
              </dd>
              <dd className="mt-1 text-xs text-slate-500">
                {selected.typicalRateNote}
              </dd>
            </div>
            <div className="rounded-lg bg-slate-50 p-3 sm:col-span-2">
              <dt className="text-xs text-slate-500">예금자보호</dt>
              <dd className="mt-0.5 text-sm text-slate-900">
                {selected.depositProtection}
              </dd>
            </div>
          </dl>

          <div>
            <p className="text-xs font-semibold text-gray-500">주요 상품</p>
            <div className="mt-1.5 flex flex-wrap gap-1.5">
              {selected.products.map((p) => (
                <span
                  key={p}
                  className="rounded-full bg-gray-100 px-2.5 py-1 text-xs text-gray-700"
                >
                  {p}
                </span>
              ))}
            </div>
          </div>

          <div className="grid gap-4 sm:grid-cols-2">
            <div className="rounded-lg border border-emerald-200 bg-emerald-50 p-3">
              <p className="text-xs font-semibold text-emerald-800">
                유리한 점
              </p>
              <ul className="mt-1.5 space-y-1">
                {selected.strengths.map((s) => (
                  <li key={s} className="text-sm text-emerald-900">
                    · {s}
                  </li>
                ))}
              </ul>
            </div>
            <div className="rounded-lg border border-amber-200 bg-amber-50 p-3">
              <p className="text-xs font-semibold text-amber-800">유의할 점</p>
              <ul className="mt-1.5 space-y-1">
                {selected.cautions.map((c) => (
                  <li key={c} className="text-sm text-amber-900">
                    · {c}
                  </li>
                ))}
              </ul>
            </div>
          </div>
        </div>
      </Card>

      {/* ── 금리 규칙 ────────────────────────────────────────── */}
      <Card title="금리에서 실제로 다투게 되는 지점">
        <ul className="space-y-4">
          {RATE_RULES.map((rule) => (
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

      {/* ── 우리 서비스와의 관계 ─────────────────────────────── */}
      <Card title="선정산은 어디에 해당하나">
        <p className="text-sm text-gray-700">
          <strong>대출관리</strong> 메뉴에서 다루는 선정산은 아직 정산되지 않은
          매출채권을 근거로 자금을 미리 지급하는 구조입니다. 담보로 잡는 대상이
          부동산이나 신용이 아니라 <strong>이미 발생한 매출</strong>이라는 점이
          위 업권들과 다릅니다.
        </p>
        <p className="mt-2 text-sm text-gray-700">
          다만 자금을 빌려주고 이자 성격의 대가를 받는다면 명칭과 무관하게 위의
          금리 규칙이 그대로 적용됩니다. 수수료로 부르든 할인료로 부르든, 대부와
          관련해 받은 금액은 전부 이자로 계산해 연 {LEGAL_MAX_RATE_PERCENT}%
          초과 여부를 판단합니다.
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
          이 페이지는 법령과 감독기관 발표에 근거한 일반 정보이며 개별 대출에
          대한 법률 자문이 아닙니다. 실제 계약 조건은 회사·상품·차주 신용도에
          따라 달라집니다.
        </p>
      </Card>
    </div>
  );
};

export default CeoLenderGuidePage;
