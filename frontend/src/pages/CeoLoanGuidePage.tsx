import React from "react";
import Card from "@/components/Card";

/**
 * CEO 대출 상품 안내 — 개인신용대출 / 주택담보대출의 심사·한도·금리 규칙을 설명한다.
 *
 * 이 화면의 수치는 전부 loan-service 의 실제 정책 상수에서 옮긴 것이다. 관리자가 보는
 * 설명은 대외 약속처럼 읽히므로, 코드에 없는 기능을 있는 것처럼 적지 않는다. 아직 열리지
 * 않은 경로는 마지막 "구현 현황" 섹션에 따로 구분해 둔다.
 *
 * 근거: loan-service/src/main/java/github/lms/lemuel/loan/domain/SecuredLoanPolicy.java
 *       loan-service/src/main/resources/application.yml (app.loan.secured.*)
 */

const GRADES = [
  { grade: "A", score: "850점 이상", limit: "1억 원", surcharge: "+1.5%p" },
  { grade: "B", score: "750점 이상", limit: "5,000만 원", surcharge: "+2.5%p" },
  { grade: "C", score: "650점 이상", limit: "3,000만 원", surcharge: "+4.0%p" },
  { grade: "D", score: "550점 이상", limit: "1,000만 원", surcharge: "+6.0%p" },
];

const COLLATERAL = [
  {
    type: "부동산(주택)",
    ltv: "70%",
    valuation: "공공 실거래가",
    marginCall: "해당 없음",
  },
  { type: "예금", ltv: "95%", valuation: "서류 제시값", marginCall: "대상" },
  { type: "채권", ltv: "80%", valuation: "서류 제시값", marginCall: "대상" },
  {
    type: "주식·수익증권",
    ltv: "60%",
    valuation: "전일 종가 × 수량",
    marginCall: "대상",
  },
];

const STATUSES = [
  "신청(REQUESTED)",
  "승인(APPROVED)",
  "실행(DISBURSED)",
  "연체(OVERDUE)",
  "기한이익상실(DEFAULTED)",
  "완제(REPAID) 또는 상각(WRITTEN_OFF)",
];

const NOT_YET = [
  {
    title: "보증기관 보증담보",
    detail:
      "인정비율·보증료·대위변제 회수 로직은 구현돼 있으나 신청 경로가 열려 있지 않다. 현재 접수 가능한 담보는 부동산과 금융자산뿐이다.",
  },
  {
    title: "담보 재평가·마진콜 판정",
    detail:
      "담보유지비율 계산과 부족액 산정은 구현돼 있으나 REST 로 노출되지 않아 운영 화면에서 호출할 수 없다.",
  },
  {
    title: "자동 연체·상각 처리",
    detail:
      "담보/개인신용대출에는 자동 배치가 없다. 연체와 기한이익상실은 운영자가 직접 API 를 호출해야 전이된다. (선정산 대출에만 자동 배치가 있다)",
  },
  {
    title: "부동산 실거래가 수집원",
    detail:
      "수집 소스가 아직 등록되지 않아 주택 담보는 현재 신청인 제시값으로 평가된다. 소스가 연결되면 별도 설정 없이 실거래가 기준으로 전환된다.",
  },
];

const Th: React.FC<{ children: React.ReactNode }> = ({ children }) => (
  <th className="px-4 py-2.5 text-left text-xs font-semibold text-gray-500 uppercase tracking-wider">
    {children}
  </th>
);

const Td: React.FC<{ children: React.ReactNode; className?: string }> = ({
  children,
  className = "",
}) => (
  <td className={`px-4 py-2.5 text-sm text-gray-800 ${className}`}>
    {children}
  </td>
);

const Formula: React.FC<{ children: React.ReactNode }> = ({ children }) => (
  <p className="bg-gray-50 border border-gray-200 rounded-lg px-4 py-3 font-mono text-sm text-gray-800">
    {children}
  </p>
);

const CeoLoanGuidePage: React.FC = () => {
  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-bold text-gray-900">대출 상품 안내</h1>
        <p className="text-sm text-gray-500 mt-1">
          개인신용대출과 주택담보대출의 심사 기준·한도 산정식·금리 구조. 아래
          수치는 현재 운영 중인 정책값이며, 정책이 바뀌면 이 화면도 함께
          갱신된다.
        </p>
      </div>

      {/* ── 공통 구조 ─────────────────────────────────────────────── */}
      <Card title="두 상품의 공통 구조">
        <div className="space-y-4">
          <p className="text-sm text-gray-700">
            개인신용대출과 주택담보대출은 별개 시스템이 아니라 하나의 대출
            원장을 공유한다. 신청·승인·실행·상환·연체 흐름과 회계 처리가
            동일하고, 담보 유무와 심사 근거만 다르다.
            금융자산담보대출(예금·채권·주식)도 같은 원장을 쓴다.
          </p>

          <div>
            <p className="text-sm font-semibold text-gray-900 mb-1.5">금리</p>
            <Formula>연이율 = 기준금리 + 가산금리</Formula>
            <p className="text-sm text-gray-600 mt-2">
              기준금리는 <span className="font-medium">한국은행 기준금리</span>
              를 매 신청 시점에 조회해 적용한다. 조회에 실패하면 설정된 폴백값{" "}
              <span className="font-medium">3.5%</span>가 쓰이며, 확정된 이율은
              신청 시점에 고정돼 이후 기준금리가 움직여도 소급되지 않는다.
            </p>
          </div>

          <div>
            <p className="text-sm font-semibold text-gray-900 mb-1.5">
              상환 방식
            </p>
            <ul className="text-sm text-gray-700 space-y-1 list-disc list-inside">
              <li>만기일시상환 — 매 회차 이자만 내고 만기에 원금 전액</li>
              <li>원리금균등상환 — 매 회차 납입액이 같음</li>
              <li>원금균등상환 — 매 회차 원금이 같고 이자는 점차 감소</li>
            </ul>
            <p className="text-sm text-gray-600 mt-2">
              실행 시점의 대출 잔액은 원금만 잡히고 이자는 회차마다 후취한다.
              완제되면 담보는 자동으로 말소된다.
            </p>
          </div>

          <div>
            <p className="text-sm font-semibold text-gray-900 mb-1.5">
              중도상환수수료
            </p>
            <Formula>
              수수료 = 중도상환액 × 1.2% × (1,095 − 경과일) ÷ 1,095
            </Formula>
            <p className="text-sm text-gray-600 mt-2">
              실행일로부터 <span className="font-medium">3년(1,095일)</span>이
              지나면 면제된다. 중도상환은 정상(실행) 상태에서만 가능하고, 연체
              중 납입은 회차 상환으로 처리된다.
            </p>
          </div>
        </div>
      </Card>

      {/* ── 개인신용대출 ──────────────────────────────────────────── */}
      <Card title="개인신용대출">
        <div className="space-y-4">
          <p className="text-sm text-gray-700">
            담보 없이 신용평가(CB) 점수만으로 심사한다. 담보가 없으므로 등급이
            한도와 금리를 모두 결정하며, 등급을 산출할 수 없으면 대출이 나가지
            않는다.
          </p>

          <div className="overflow-x-auto">
            <table className="min-w-full divide-y divide-gray-200">
              <thead className="bg-gray-50">
                <tr>
                  <Th>등급</Th>
                  <Th>CB 점수</Th>
                  <Th>한도</Th>
                  <Th>가산금리</Th>
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-100">
                {GRADES.map((g) => (
                  <tr key={g.grade}>
                    <Td className="font-semibold">{g.grade}</Td>
                    <Td>{g.score}</Td>
                    <Td>{g.limit}</Td>
                    <Td>{g.surcharge}</Td>
                  </tr>
                ))}
                <tr className="bg-red-50">
                  <Td className="font-semibold">E</Td>
                  <Td>550점 미만</Td>
                  <Td className="text-red-700 font-medium">대출 불가</Td>
                  <Td>—</Td>
                </tr>
              </tbody>
            </table>
          </div>

          <div className="rounded-lg border border-amber-200 bg-amber-50 px-4 py-3">
            <p className="text-sm text-amber-900">
              <span className="font-semibold">
                등급을 알 수 없는 경우도 거절된다.
              </span>{" "}
              담보대출의 평판 등급은 조회에 실패해도 한도를 깎지 않고
              통과시키지만, 개인신용대출은 등급이 유일한 심사 근거이므로 부재를
              통과로 보지 않는다.
            </p>
          </div>

          <div>
            <p className="text-sm font-semibold text-gray-900 mb-1.5">
              거절 사유
            </p>
            <ul className="text-sm text-gray-700 space-y-1 list-disc list-inside">
              <li>
                CB 점수가 최저 기준에 미달하거나 등급을 산출할 수 없는 경우
              </li>
              <li>신청 금액이 등급별 한도를 넘는 경우</li>
            </ul>
          </div>
        </div>
      </Card>

      {/* ── 주택담보대출 ──────────────────────────────────────────── */}
      <Card title="주택담보대출">
        <div className="space-y-4">
          <p className="text-sm text-gray-700">
            주택을 담보로 잡고 담보가치에 비례해 한도를 정한다. 담보가 위험을
            흡수하므로 신용등급은 금리에 영향을 주지 않는다.
          </p>

          <div>
            <p className="text-sm font-semibold text-gray-900 mb-1.5">
              한도 산정
            </p>
            <Formula>
              유효담보가치 = 담보 평가액 − 선순위 채권액 (음수면 0)
              <br />
              대출 한도 = 유효담보가치 × 인정비율
            </Formula>
            <p className="text-sm text-gray-600 mt-2">
              담보 평가액은 공공 실거래가 데이터에서 해당 물건의 가장 최근
              거래가를 가져와 산정한다. 평가액은 담보 설정 시점의 값으로
              보존되며, 이후 시세 변동은 재평가 이력으로만 쌓이고 원 평가액을
              덮어쓰지 않는다. 인정비율은 담보 유형별로 다르며 아래 표에
              정리했다.
            </p>
          </div>

          <div>
            <p className="text-sm font-semibold text-gray-900 mb-1.5">금리</p>
            <p className="text-sm text-gray-700">
              가산금리는 등급과 무관하게{" "}
              <span className="font-medium">+0.8%p</span>로 고정된다.
              개인신용대출의 등급별 가산(최대 +6.0%p)과 비교하면 담보 제공의
              이점이 금리 차이로 드러난다.
            </p>
          </div>

          <div className="rounded-lg border border-blue-200 bg-blue-50 px-4 py-3">
            <p className="text-sm text-blue-900">
              주택담보대출은 담보 시세가 떨어져도 마진콜(추가담보 요구)이나
              강제처분 대상이 되지 않는다. 담보유지비율 관리는 금융자산담보에만
              적용된다.
            </p>
          </div>

          <div>
            <p className="text-sm font-semibold text-gray-900 mb-1.5">
              회수 절차
            </p>
            <p className="text-sm text-gray-700">
              연체가 발생한 뒤에만 기한이익상실로 넘어갈 수 있고, 담보 처분은
              기한이익상실 이후에만 가능하다. 매각대금이 잔액을 넘으면
              처분이익으로, 모자라면 미회수분을 처분손실로 기표한다. 연체에서
              곧바로 담보를 처분하는 경로는 없다.
            </p>
          </div>
        </div>
      </Card>

      {/* ── 담보 유형별 비교 ──────────────────────────────────────── */}
      <Card title="담보 유형별 인정비율">
        <div className="overflow-x-auto">
          <table className="min-w-full divide-y divide-gray-200">
            <thead className="bg-gray-50">
              <tr>
                <Th>담보 유형</Th>
                <Th>인정비율</Th>
                <Th>평가 기준</Th>
                <Th>담보유지비율 관리</Th>
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-100">
              {COLLATERAL.map((c) => (
                <tr key={c.type}>
                  <Td className="font-medium">{c.type}</Td>
                  <Td className="font-semibold">{c.ltv}</Td>
                  <Td>{c.valuation}</Td>
                  <Td>{c.marginCall}</Td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <p className="text-sm text-gray-600 mt-4">
          금융자산담보는 담보가치가 잔액 대비 일정 수준 아래로 내려가면 관리
          대상이 된다. 유지비율이 <span className="font-medium">140%</span>{" "}
          아래로 떨어지면 추가담보를 요구하고,{" "}
          <span className="font-medium">120%</span> 아래로 더 떨어지면 강제처분
          단계로 올린다. 주식은 가격 변동이 커서 인정비율이 가장 낮고, 예금은
          가치가 확정적이라 가장 높다.
        </p>
      </Card>

      {/* ── 상태 흐름 ─────────────────────────────────────────────── */}
      <Card title="상태 흐름">
        <div className="flex flex-wrap items-center gap-2">
          {STATUSES.map((s, i) => (
            <React.Fragment key={s}>
              <span className="inline-flex items-center px-3 py-1.5 rounded-full bg-gray-100 text-sm text-gray-800">
                {s}
              </span>
              {i < STATUSES.length - 1 && (
                <span className="text-gray-400">→</span>
              )}
            </React.Fragment>
          ))}
        </div>
        <p className="text-sm text-gray-600 mt-4">
          연체 없이 완제되는 것이 정상 경로다. 신청·승인 단계에서는
          거절(REJECTED)로 끝날 수 있고, 상각은 기한이익상실을 거친 뒤에만
          가능하다.
        </p>
      </Card>

      {/* ── 구현 현황 ─────────────────────────────────────────────── */}
      <Card title="구현 현황">
        <p className="text-sm text-gray-700 mb-4">
          아래 항목은 코드가 준비돼 있으나 아직 운영에서 쓸 수 없는 것들이다.
          상품 설명과 섞이면 없는 기능을 약속하게 되므로 구분해 적는다.
        </p>
        <ul className="space-y-3">
          {NOT_YET.map((n) => (
            <li key={n.title} className="border-l-2 border-gray-300 pl-4">
              <p className="text-sm font-semibold text-gray-900">{n.title}</p>
              <p className="text-sm text-gray-600 mt-0.5">{n.detail}</p>
            </li>
          ))}
        </ul>
      </Card>

      <p className="text-xs text-gray-400">
        수치 출처: loan-service SecuredLoanPolicy(등급별
        한도·가산금리·인정비율·수수료), application.yml(app.loan.secured.*).
        정책 상수가 바뀌면 이 화면의 값도 함께 고쳐야 한다.
      </p>
    </div>
  );
};

export default CeoLoanGuidePage;
