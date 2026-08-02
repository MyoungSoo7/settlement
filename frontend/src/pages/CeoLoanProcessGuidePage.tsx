import React from "react";
import Card from "@/components/Card";

/**
 * CEO 대출 심사·상환 안내 — 신청부터 완제까지의 절차를 설명한다.
 *
 * 기존 「대출 상품 안내」(CeoLoanGuidePage)가 *상품별 조건*(한도·금리·담보)을 다룬다면, 이 화면은
 * *절차와 산식*을 다룬다 — 심사가 언제 어디서 끝나는지, 신용평가 점수가 어떻게 계산되는지,
 * 이자·수수료가 어떤 방식으로 붙고 상환표가 회차별로 어떻게 만들어지는지.
 *
 * 여기 적힌 수치·산식·절차는 전부 loan-service 의 실제 구현에서 옮긴 것이다. 관리자가 보는 설명은
 * 대외 약속처럼 읽히므로, 코드에 없는 절차를 있는 것처럼 적지 않는다. 특히 네 상품의 심사 경로가
 * 서로 다르므로(승인 단계 유무 · 한도 재검증 시점) 뭉뚱그리지 않고 갈래로 나눠 적는다. 아직 열리지
 * 않은 경로는 마지막 "절차상 주의" 섹션에 따로 구분해 둔다.
 *
 * 근거: loan-service/src/main/java/github/lms/lemuel/loan/
 *         domain/{CorporateCreditPolicy,SecuredLoanPolicy,RepaymentSchedule,RepaymentMethod}.java
 *         domain/{LoanStatus,CorporateLoanStatus,SecuredLoanStatus}.java (상태 전이 단일 출처)
 *         domain/{LoanAdvance,CorporateLoan,SecuredLoan}.java (수수료·잔액 구성)
 *         application/service/{CreditPolicy,RequestSecuredLoanService,DisburseLoanService,
 *                             ApplyRepaymentService,PrepaySecuredLoanService,LoanOverdueScheduler}.java
 *       loan-service/src/main/resources/application.yml (app.loan.*)
 */

/** 네 가지 대출의 심사 근거 — 무엇을 보고 한도를 정하는가가 상품을 가른다. */
const PRODUCTS = [
  {
    name: "선정산 대출",
    basis: "미지급 정산예정금 + 셀러 평판",
    limit: "정산예정금 합계 × LTV × 평판 haircut",
    repay: "정산 확정 시 자동 차감",
  },
  {
    name: "기업 신용대출",
    basis: "상장사 재무제표 + 뉴스 평판",
    limit: "자본총계 × 10% × 등급계수",
    repay: "차주가 금액을 지정해 상환",
  },
  {
    name: "개인신용대출",
    basis: "외부 CB 점수",
    limit: "등급별 정액 한도표",
    repay: "원금·이자를 나눠 회차 상환",
  },
  {
    name: "담보대출",
    basis: "담보 평가액",
    limit: "유효담보가치 × 담보인정비율",
    repay: "원금·이자를 나눠 회차 상환",
  },
];

/** 심사 갈래 — 승인 단계의 유무와 한도 재검증 시점이 상품마다 다르다. */
const REVIEW_PATHS = [
  {
    title: "선정산 대출 — 실행 시점에 한 번 더 본다",
    steps: [
      "신청 시 미지급 정산예정금으로 한도를 산정하고 신청 건을 남긴다.",
      "실행을 요청하면 대출 건을 잠근 뒤 한도를 다시 검증한다. 신청 이후 정산이 지급돼 담보가 줄었을 수 있기 때문이다.",
      "재검증에서 한도를 넘으면 그 자리에서 거절로 전이시킨다.",
    ],
    note: "운영자가 승인하는 별도 단계는 없다 — 실행 요청이 승인을 겸한다.",
  },
  {
    title: "기업 신용대출 — 신청 트랜잭션 안에서 끝난다",
    steps: [
      "재무제표와 평판으로 신용점수·등급·한도를 산정한다.",
      "재무자료가 없거나 최하위 등급이거나 신청액이 한도를 넘으면 그 자리에서 거절한다.",
      "통과하면 수수료를 확정하고 점수·등급을 대출 건에 스냅샷으로 박는다.",
    ],
    note: "운영자가 승인하는 별도 단계는 없다 — 실행 요청이 승인을 겸한다.",
  },
  {
    title: "담보 · 개인신용대출 — 심사 후 운영자가 승인한다",
    steps: [
      "신청 시점에 담보를 평가하거나 CB 등급을 환산해 한도를 산정하고, 한도를 넘으면 거절한다.",
      "통과하면 기준금리를 조회해 적용 연이율을 확정하고 대출 건에 고정한다.",
      "이후 운영자가 승인 · 거절 · 실행을 명시적으로 수행한다. 신청이 곧 실행은 아니다.",
    ],
    note: "담보는 한도 검증을 통과한 뒤에 저장한다 — 거절된 신청이 고아 담보 기록을 남기지 않게 순서로 보장한다.",
  },
];

/** 기업 신용점수 100점의 배점 구조 — 안정성 40 + 수익성 40 + 평판 20. */
const SCORE_AXES = [
  {
    axis: "안정성",
    weight: "40점",
    metric: "부채비율",
    bands:
      "100% 이하 40점 · 200% 이하 30점 · 300% 이하 20점 · 400% 이하 10점 · 초과 0점",
  },
  {
    axis: "수익성",
    weight: "20점",
    metric: "영업이익률",
    bands:
      "20% 이상 20점 · 10% 이상 15점 · 5% 이상 10점 · 0% 이상 5점 · 음수 0점",
  },
  {
    axis: "수익성",
    weight: "20점",
    metric: "ROA",
    bands:
      "10% 이상 20점 · 5% 이상 15점 · 2% 이상 10점 · 0% 이상 5점 · 음수 0점",
  },
  {
    axis: "평판",
    weight: "20점",
    metric: "뉴스 평판 등급",
    bands: "A 20점 · B 15점 · C 10점 · D 5점 · E 0점 · 미상 10점(중립)",
  },
];

/** 기업 신용등급 → 한도계수·수수료할증. E 는 대출 자체가 차단된다. */
const CORP_GRADES = [
  { grade: "A", score: "80점 이상", factor: "1.0", surcharge: "1.0배" },
  { grade: "B", score: "65점 이상", factor: "0.8", surcharge: "1.1배" },
  { grade: "C", score: "50점 이상", factor: "0.6", surcharge: "1.25배" },
  { grade: "D", score: "35점 이상", factor: "0.3", surcharge: "1.5배" },
];

/** 개인신용 CB 등급 밴드 — 담보가 없어 등급이 한도와 금리를 모두 결정한다. */
const CB_GRADES = [
  { grade: "A", score: "850점 이상", limit: "1억 원", surcharge: "+1.5%p" },
  { grade: "B", score: "750점 이상", limit: "5,000만 원", surcharge: "+2.5%p" },
  { grade: "C", score: "650점 이상", limit: "3,000만 원", surcharge: "+4.0%p" },
  { grade: "D", score: "550점 이상", limit: "1,000만 원", surcharge: "+6.0%p" },
];

/** 상환방식 3종 — 원금·기간·이자율이 같아도 회차 구조가 달라진다. */
const METHODS = [
  {
    name: "만기일시상환",
    structure:
      "1회차부터 마지막 직전까지는 이자만, 마지막 회차에 원금 전액 + 이자",
    burden: "만기 전까지 가장 가벼움",
    interest: "총이자 최대",
  },
  {
    name: "원리금균등상환",
    structure:
      "매 회차 납입액(원금+이자) 동일 — 초반 이자 비중이 크고 갈수록 원금 비중 증가",
    burden: "매달 동일",
    interest: "중간",
  },
  {
    name: "원금균등상환",
    structure: "매 회차 납입 원금 동일, 잔액이 줄며 이자 체감",
    burden: "초반이 가장 무거움",
    interest: "총이자 최소",
  },
];

/** 상품군별 상태 흐름 — 연체·상각의 유무가 상품 성격을 드러낸다. */
const FLOWS = [
  {
    title: "선정산 대출",
    line: "신청 → 승인 → 실행 → 완제",
    branch: "실행 전까지 거절 가능 · 실행 후 연체 → 상각",
  },
  {
    title: "기업 신용대출",
    line: "신청 → 승인 → 실행 → 완제",
    branch:
      "신청 단계에서만 거절 가능(승인 후 거절 경로 없음) · 연체·상각 상태 자체가 없음",
  },
  {
    title: "담보 · 개인신용대출",
    line: "신청 → 승인 → 실행 → 완제",
    branch:
      "실행 전까지 거절 가능 · 실행 후 연체 → 기한이익상실 → 완제 또는 상각",
  },
];

/** 설명과 섞이면 없는 절차를 약속하게 되는 항목들. */
const CAVEATS = [
  {
    title: "자동 연체 배치는 선정산 대출에만 있다",
    detail:
      "매일 새벽 3시(KST)에 만기 경과분을 연체로, 상각 임계 경과분을 상각으로 승격시키는 배치는 선정산 대출만 대상으로 한다. 담보·개인신용대출의 연체와 기한이익상실은 운영자가 직접 전이시켜야 한다.",
  },
  {
    title: "담보 처분·대위변제는 화면에서 실행할 수 없다",
    detail:
      "기한이익상실 이후의 담보 처분과 보증기관 대위변제는 회계 처리까지 구현돼 있으나 REST 로 노출되지 않아 운영 화면에서 호출할 수 없다. 담보 재평가와 마진콜 판정도 마찬가지다.",
  },
  {
    title: "확정된 금리는 소급되지 않는다",
    detail:
      "적용 연이율은 신청 시점의 기준금리로 산정돼 대출 건에 스냅샷으로 박힌다. 이후 한국은행 기준금리가 움직여도 이미 신청된 대출의 조건은 바뀌지 않는다. 신용점수와 등급도 같은 방식으로 신청 시점 값이 보존된다.",
  },
  {
    title: "상환표 계산은 아직 화면에 붙어 있지 않다",
    detail:
      "원금·기간·이자율·상환방식으로 회차표를 돌려주는 계산 API 는 있으나 화면에서 호출하지 않는다. 또 그 계산은 특정 대출 건과 연결되지 않는 순수 미리보기라, 결과가 실제 대출의 확정 상환표는 아니다.",
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

const CeoLoanProcessGuidePage: React.FC = () => {
  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-bold text-gray-900">
          대출 심사·상환 안내
        </h1>
        <p className="text-sm text-gray-500 mt-1">
          신청부터 완제까지 — 심사가 무엇을 근거로 어디서 끝나는지, 신용평가
          점수가 어떻게 산출되는지, 이자와 수수료가 어떤 방식으로 붙는지.
          <br />
          아래 수치는 loan-service 에{" "}
          <span className="font-medium">기본값으로 설정된 정책값</span>이다.
          배포 환경에서 설정을 덮어쓰면 실제 적용값이 달라지므로, 대외 고지나
          계약에 쓰기 전에는 운영 설정을 확인해야 한다.
        </p>
      </div>

      {/* ── 상품별 심사 근거 ──────────────────────────────────────── */}
      <Card title="무엇을 보고 심사하는가">
        <p className="text-sm text-gray-700 mb-4">
          네 가지 대출이 한 서비스 안에 공존한다. 회계 처리는 같은 원장을
          공유하지만 <span className="font-medium">심사 근거가 다르다</span> —
          담보가 있으면 담보가치를, 없으면 점수를 본다.
        </p>
        <div className="overflow-x-auto">
          <table className="min-w-full divide-y divide-gray-200">
            <thead className="bg-gray-50">
              <tr>
                <Th>대출</Th>
                <Th>심사 근거</Th>
                <Th>한도 산정</Th>
                <Th>상환</Th>
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-100">
              {PRODUCTS.map((p) => (
                <tr key={p.name}>
                  <Td className="font-semibold whitespace-nowrap">{p.name}</Td>
                  <Td>{p.basis}</Td>
                  <Td>{p.limit}</Td>
                  <Td>{p.repay}</Td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </Card>

      {/* ── 심사 절차 ─────────────────────────────────────────────── */}
      <Card title="대출심사 — 세 갈래 절차">
        <div className="space-y-4">
          <p className="text-sm text-gray-700">
            하나로 통일된 심사 절차는 없다. 상품마다{" "}
            <span className="font-medium">
              심사가 끝나는 시점과 승인 단계의 유무
            </span>
            가 다르므로 갈래를 나눠 봐야 한다.
          </p>

          {REVIEW_PATHS.map((p) => (
            <div key={p.title} className="border-l-2 border-gray-300 pl-4">
              <p className="text-sm font-semibold text-gray-900">{p.title}</p>
              <ol className="text-sm text-gray-700 mt-1.5 space-y-1 list-decimal list-inside">
                {p.steps.map((s) => (
                  <li key={s}>{s}</li>
                ))}
              </ol>
              <p className="text-sm text-gray-500 mt-1.5">{p.note}</p>
            </div>
          ))}

          <div>
            <p className="text-sm font-semibold text-gray-900 mb-1.5">
              금리 확정 (담보 · 개인신용대출)
            </p>
            <Formula>연이율 = 기준금리 + 가산금리</Formula>
            <p className="text-sm text-gray-600 mt-2">
              기준금리는 <span className="font-medium">한국은행 기준금리</span>
              를 매 신청 시점에 조회한다. 조회에 실패하면 폴백값(기본{" "}
              <span className="font-medium">3.5%</span>,{" "}
              <code className="text-xs">
                app.loan.secured.base-rate-percent
              </code>
              )이 쓰인다. 담보형 가산은 등급과 무관하게 +0.8%p 고정, 신용형은
              등급별이다.
            </p>
          </div>

          <div>
            <p className="text-sm font-semibold text-gray-900 mb-1.5">
              거절 사유
            </p>
            <ul className="text-sm text-gray-700 space-y-1 list-disc list-inside">
              <li>신청 금액이 산정된 한도를 넘는 경우</li>
              <li>
                신용등급이 최하위(E)이거나 등급을 산출할 수 없는 경우 — 개인신용
                기준 CB 550점 미만, 기업 신용 기준 35점 미만
              </li>
              <li>
                기업 신용대출에서 해당 상장사의 재무자료를 찾을 수 없는 경우
              </li>
              <li>담보 유효가치가 0 이하로 나와 한도가 0 인 경우</li>
            </ul>
          </div>
        </div>
      </Card>

      {/* ── 상태 흐름 ─────────────────────────────────────────────── */}
      <Card title="상태 흐름">
        <div className="space-y-4">
          {FLOWS.map((f) => (
            <div key={f.title} className="border-l-2 border-gray-300 pl-4">
              <p className="text-sm font-semibold text-gray-900">{f.title}</p>
              <p className="text-sm text-gray-800 mt-1 font-medium">{f.line}</p>
              <p className="text-sm text-gray-600 mt-0.5">{f.branch}</p>
            </div>
          ))}
          <div className="rounded-lg border border-amber-200 bg-amber-50 px-4 py-3">
            <p className="text-sm text-amber-900">
              <span className="font-semibold">
                기한이익상실은 연체를 거쳐야만 도달한다.
              </span>{" "}
              실행 상태에서 곧바로 잔액 전액을 청구하는 경로는 없다. 잔여 원금을
              즉시 청구하는 중대한 조치라 연체 사실이 먼저 기록되어야 근거가
              남기 때문이다. 상각도 마찬가지로 기한이익상실을 거친 뒤에만
              가능하다.
            </p>
          </div>
        </div>
      </Card>

      {/* ── 신용평가 ① 기업 ───────────────────────────────────────── */}
      <Card title="신용평가 ① 기업 신용대출 — 재무·평판 100점">
        <div className="space-y-4">
          <p className="text-sm text-gray-700">
            상장사의 공시 재무제표와 뉴스 평판을 합쳐 100점으로 환산한다. 담보를
            잡지 않으므로 점수가 한도와 수수료를 모두 결정한다.
          </p>

          <div className="overflow-x-auto">
            <table className="min-w-full divide-y divide-gray-200">
              <thead className="bg-gray-50">
                <tr>
                  <Th>축</Th>
                  <Th>배점</Th>
                  <Th>지표</Th>
                  <Th>구간</Th>
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-100">
                {SCORE_AXES.map((a) => (
                  <tr key={a.metric}>
                    <Td className="font-semibold whitespace-nowrap">
                      {a.axis}
                    </Td>
                    <Td className="whitespace-nowrap">{a.weight}</Td>
                    <Td className="whitespace-nowrap">{a.metric}</Td>
                    <Td className="text-xs">{a.bands}</Td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          <div className="rounded-lg border border-blue-200 bg-blue-50 px-4 py-3">
            <p className="text-sm text-blue-900">
              부채비율을 산출할 수 없는 경우(자본잠식 등)는 안정성 0점으로 본다.
              반면 <span className="font-medium">평판 등급이 없으면</span> 0점이
              아니라 중립 10점을 준다 — 평판은 보조 지표라 데이터 부재가 곧
              악재는 아니기 때문이다.
            </p>
          </div>

          <div className="overflow-x-auto">
            <table className="min-w-full divide-y divide-gray-200">
              <thead className="bg-gray-50">
                <tr>
                  <Th>등급</Th>
                  <Th>신용점수</Th>
                  <Th>한도계수</Th>
                  <Th>수수료 할증</Th>
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-100">
                {CORP_GRADES.map((g) => (
                  <tr key={g.grade}>
                    <Td className="font-semibold">{g.grade}</Td>
                    <Td>{g.score}</Td>
                    <Td>{g.factor}</Td>
                    <Td>{g.surcharge}</Td>
                  </tr>
                ))}
                <tr className="bg-red-50">
                  <Td className="font-semibold">E</Td>
                  <Td>35점 미만</Td>
                  <Td className="text-red-700 font-medium">대출 불가</Td>
                  <Td>—</Td>
                </tr>
              </tbody>
            </table>
          </div>

          <div>
            <p className="text-sm font-semibold text-gray-900 mb-1.5">
              한도와 수수료
            </p>
            <Formula>
              한도 = 자본총계 × 10% × 등급계수
              <br />
              수수료 = 원금 × 일할이율 × 기간(일) × 등급할증
            </Formula>
            <p className="text-sm text-gray-600 mt-2">
              자본총계 비율의 기본값은{" "}
              <code className="text-xs">
                app.loan.corporate.equity-limit-ratio
              </code>
              , 일할이율의 기본값은{" "}
              <code className="text-xs">app.loan.daily-rate</code>(
              <span className="font-medium">1일 0.02%</span>)이며 일할이율은
              선정산 대출과 공용이다. 자본총계가 없거나 0 이하면 한도는 0 이다.
            </p>
          </div>
        </div>
      </Card>

      {/* ── 신용평가 ② 개인 ───────────────────────────────────────── */}
      <Card title="신용평가 ② 개인신용대출 — 외부 CB 점수">
        <div className="space-y-4">
          <p className="text-sm text-gray-700">
            기업과 달리 자체 점수를 계산하지 않고 외부 신용평가사(CB)의 점수
            스냅샷을 그대로 등급으로 환산한다.
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
                {CB_GRADES.map((g) => (
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
                데이터 부재를 다루는 방식이 상품마다 다르다.
              </span>{" "}
              개인신용대출은 등급을 알 수 없으면 거절한다 — 담보가 없어 등급이
              유일한 심사 근거이기 때문이다. 반대로 선정산 대출은 셀러 평판
              등급이 없어도 한도를 깎지 않고 통과시킨다 — 이미 정산예정금이라는
              담보가 있고 평판은 가중치일 뿐이라, 평판 데이터가 없다는 이유로
              대출을 막지 않는다.
            </p>
          </div>

          <div>
            <p className="text-sm font-semibold text-gray-900 mb-1.5">
              선정산 대출의 평판 반영
            </p>
            <Formula>
              한도 = 미지급 정산예정금 합계 × LTV × 평판 haircut
            </Formula>
            <p className="text-sm text-gray-600 mt-2">
              평판 haircut 기본값은 A·B 1.0 / C 0.85 / D 0.70 / E 0(차단), 등급
              미상은 1.0 이다. LTV 기본값은{" "}
              <code className="text-xs">app.loan.ltv</code>에 설정돼 있다. 평판
              등급은 기업조회 쪽 뉴스 평판이 바뀔 때마다 대출 서비스로 전달돼
              갱신된다.
            </p>
          </div>
        </div>
      </Card>

      {/* ── 이자·수수료 구조 ──────────────────────────────────────── */}
      <Card title="이자와 수수료 — 붙는 방식이 상품마다 다르다">
        <div className="space-y-4">
          <p className="text-sm text-gray-700">
            상환을 이해하려면 먼저 이 차이를 알아야 한다. 어떤 대출은 수수료를
            처음에 한 번 확정해 잔액에 얹고, 어떤 대출은 회차마다 이자를 새로
            계산한다.
          </p>

          <div className="border-l-2 border-gray-300 pl-4">
            <p className="text-sm font-semibold text-gray-900 mb-1.5">
              선정산 · 기업 신용대출 — 수수료 선취
            </p>
            <Formula>실행 시 잔액 = 원금 + 수수료</Formula>
            <p className="text-sm text-gray-600 mt-2">
              실행 시점에 기간 전체의 수수료를 계산해 잔액에 더한다. 이후
              회차별로 붙는 이자가 <span className="font-medium">없다</span>.
              선정산 수수료는{" "}
              <code className="text-xs">선지급액 × 일할이율 × 선지급일수</code>
              이고, 선지급 기간은 1~365일 범위로 받는다.
            </p>
          </div>

          <div className="border-l-2 border-gray-300 pl-4">
            <p className="text-sm font-semibold text-gray-900 mb-1.5">
              담보 · 개인신용대출 — 회차 이자 후취
            </p>
            <Formula>실행 시 잔액 = 원금</Formula>
            <p className="text-sm text-gray-600 mt-2">
              실행 시점의 잔액에는 원금만 잡힌다. 이자는 회차마다 남은 잔액에
              대해 새로 계산해 붙이므로 미리 더할 것이 없다. 그래서 회차별
              상환표는 이쪽에서만 의미를 갖는다.
            </p>
          </div>
        </div>
      </Card>

      {/* ── 상환 ① 상환방식 ───────────────────────────────────────── */}
      <Card title="상환 ① 세 가지 상환방식 (담보 · 개인신용대출)">
        <div className="space-y-4">
          <p className="text-sm text-gray-700">
            원금·기간·이자율이 같아도 상환방식에 따라 회차별 납입 구조와
            총이자가 달라진다. 회차표 계산은 한 곳에서만 이뤄지는 결정적
            계산이라 같은 입력이면 언제나 같은 표가 나온다.
          </p>

          <div className="overflow-x-auto">
            <table className="min-w-full divide-y divide-gray-200">
              <thead className="bg-gray-50">
                <tr>
                  <Th>방식</Th>
                  <Th>회차 구조</Th>
                  <Th>초반 부담</Th>
                  <Th>총이자</Th>
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-100">
                {METHODS.map((m) => (
                  <tr key={m.name}>
                    <Td className="font-semibold whitespace-nowrap">
                      {m.name}
                    </Td>
                    <Td className="text-xs">{m.structure}</Td>
                    <Td className="whitespace-nowrap">{m.burden}</Td>
                    <Td className="whitespace-nowrap">{m.interest}</Td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          <div>
            <p className="text-sm font-semibold text-gray-900 mb-1.5">
              이자 계산
            </p>
            <Formula>
              월이율 = 연이율 ÷ 12
              <br />
              회차 이자 = 직전 회차 상환 후 잔액 × 월이율
            </Formula>
            <p className="text-sm text-gray-600 mt-2">
              이자는 잔액 기준으로 <span className="font-medium">후취</span>
              한다. 원리금균등상환의 고정 납입액은 연금 공식{" "}
              <code className="text-xs">P·i·(1+i)ⁿ ÷ ((1+i)ⁿ − 1)</code> 로
              구한다.
            </p>
          </div>

          <div>
            <p className="text-sm font-semibold text-gray-900 mb-1.5">
              라운딩 규칙
            </p>
            <ul className="text-sm text-gray-700 space-y-1 list-disc list-inside">
              <li>
                중간 계산은 고정밀로 하고, 절사는 회차 금액을 확정하는 시점에만
                적용한다.
              </li>
              <li>
                <span className="font-medium">
                  마지막 회차가 잔여 원금을 흡수
                </span>
                해 원금 합계가 신청 원금과 정확히 일치한다.
              </li>
              <li>
                원 단위로 정확히 표현되지 않는 원금은{" "}
                <span className="font-medium">반올림하지 않고 거부</span>한다.
                시스템이 계약 금액을 임의로 바꾸면 상환표 총액과 계약서가
                어긋나기 때문이다.
              </li>
            </ul>
          </div>

          <div>
            <p className="text-sm font-semibold text-gray-900 mb-1.5">
              회차 납입 접수
            </p>
            <p className="text-sm text-gray-700">
              상환은 <span className="font-medium">원금과 이자를 나눠서</span>{" "}
              받는다. 원금은 대출채권을 줄이고 이자는 수익으로 인식해 회계
              처리가 갈리기 때문이다. 완제되면 담보는 자동으로 말소된다. 연체나
              기한이익상실 상태에서도 회차 납입은 계속 받는다.
            </p>
          </div>
        </div>
      </Card>

      {/* ── 상환 ② 중도상환·연체 ──────────────────────────────────── */}
      <Card title="상환 ② 중도상환 · 연체 · 상각">
        <div className="space-y-4">
          <div>
            <p className="text-sm font-semibold text-gray-900 mb-1.5">
              중도상환수수료
            </p>
            <Formula>
              수수료 = 중도상환액 × 1.2% × (1,095 − 경과일) ÷ 1,095
            </Formula>
            <p className="text-sm text-gray-600 mt-2">
              실행일로부터 <span className="font-medium">3년(1,095일)</span>이
              지나면 면제된다. 분모가 약정 기간 전체가 아니라 부과기간이라,
              면제일이 다가올수록 수수료가 0 으로 매끄럽게 수렴한다 — 면제 직전
              하루 사이에 수수료가 계단식으로 사라지는 일이 없다.
            </p>
            <p className="text-sm text-gray-600 mt-2">
              수수료는{" "}
              <span className="font-medium">상환액에서 떼는 것이 아니라</span>{" "}
              위에 더해 청구한다(총 수취액 = 중도상환액 + 수수료). 상환액이
              잔액보다 크면 잔액까지만 차감되고, 수수료도 실제 차감된 금액을
              기준으로 계산한다.
            </p>
            <p className="text-sm text-gray-600 mt-2">
              중도상환은{" "}
              <span className="font-medium">정상 실행 상태에서만</span>{" "}
              가능하다. 연체 이후에는 이 경로로 들어올 수 없고, 납입은 회차
              상환으로 처리된다.
            </p>
          </div>

          <div>
            <p className="text-sm font-semibold text-gray-900 mb-1.5">
              연체와 상각 (선정산 대출)
            </p>
            <p className="text-sm text-gray-700">
              선정산 대출은 매일{" "}
              <span className="font-medium">새벽 3시(KST)</span> 배치가 만기
              경과분을 연체로 승격시키고, 상각 임계를 넘긴 건을 상각으로
              종결한다. 유예 기간 기본값은 0일, 상각 임계 기본값은 만기 경과{" "}
              <span className="font-medium">30일</span>이다. 한 건이 실패해도
              배치 전체가 멈추지 않도록 건별로 격리해 처리하며, 운영자가
              수동으로 연체·상각시키는 경로도 함께 열려 있다.
            </p>
            <p className="text-sm text-gray-600 mt-2">
              상각 시 미상환 잔액은 대손 전표로 인식된다. 회수 조치와 회계
              기표가 같은 트랜잭션에서 일어나므로 원장과 대출 상태가 어긋나지
              않는다.
            </p>
          </div>

          <div>
            <p className="text-sm font-semibold text-gray-900 mb-1.5">
              담보 · 개인신용대출의 회수
            </p>
            <p className="text-sm text-gray-700">
              이쪽은 자동 배치가 없다. 연체와 기한이익상실 전이는 운영자가 직접
              수행해야 한다. 담보 처분은 기한이익상실 이후에만 가능하고,
              매각대금이 잔액을 넘으면 처분이익으로, 모자라면 미회수분을
              처분손실로 기표한다. 보증부 대출은 잔액의{" "}
              <span className="font-medium">85%</span>까지 보증기관에서 회수하고
              나머지는 대손으로 남는다 — 보증부라도 손실이 0 은 아니다.
            </p>
          </div>
        </div>
      </Card>

      {/* ── 상환 ③ 선정산 자동 상환 ───────────────────────────────── */}
      <Card title="상환 ③ 선정산 대출은 정산금에서 자동 차감된다">
        <div className="space-y-4">
          <p className="text-sm text-gray-700">
            선정산 대출은 차주가 따로 상환하지 않는다. 셀러의 정산이 확정되면 그
            정산금에서 미상환 대출을 자동으로 차감하고, 남은 금액만 실제로
            지급한다.
          </p>

          <div>
            <p className="text-sm font-semibold text-gray-900 mb-1.5">
              차감 순서
            </p>
            <p className="text-sm text-gray-700">
              미상환 대출이 여러 건이면{" "}
              <span className="font-medium">오래된 건부터(FIFO)</span> 차감하며,
              연체 상태인 대출도 차감 대상에 포함한다. 차감 결과는 정산 쪽에
              통지되고, 정산은{" "}
              <code className="text-xs">지급액 = 정산금 − 차감액</code> 으로
              지급을 마무리한다.
            </p>
          </div>

          <div className="rounded-lg border border-blue-200 bg-blue-50 px-4 py-3">
            <p className="text-sm text-blue-900">
              차감할 대출이 하나도 없어도 통지는 발행된다. 통지가 없으면 정산이
              지급을 시작하지 못하고 멈추기 때문이다. 같은 정산이 두 번 들어와도
              중복 차감되지 않도록 세 겹으로 막아 둔다 — 발행 단계의 고유
              식별자, 수신 단계의 처리 이력, 그리고 정산 건당 상환 기록을 하나로
              제한하는 DB 제약.
            </p>
          </div>
        </div>
      </Card>

      {/* ── 절차상 주의 ───────────────────────────────────────────── */}
      <Card title="절차상 주의">
        <p className="text-sm text-gray-700 mb-4">
          아래는 설명과 섞이면 없는 절차를 약속하게 되는 항목들이라 따로 구분해
          적는다.
        </p>
        <ul className="space-y-3">
          {CAVEATS.map((c) => (
            <li key={c.title} className="border-l-2 border-gray-300 pl-4">
              <p className="text-sm font-semibold text-gray-900">{c.title}</p>
              <p className="text-sm text-gray-600 mt-0.5">{c.detail}</p>
            </li>
          ))}
        </ul>
      </Card>

      <p className="text-xs text-gray-400">
        수치 출처: loan-service CorporateCreditPolicy(신용점수
        배점·등급·한도계수·수수료할증), SecuredLoanPolicy(CB
        등급·정액한도·가산금리·중도상환수수료·보증비율),
        RepaymentSchedule(상환방식·이자·라운딩), CreditPolicy(선정산 LTV·평판
        haircut), LoanStatus·CorporateLoanStatus·SecuredLoanStatus(상태 전이),
        LoanOverdueScheduler(연체·상각 배치), application.yml(app.loan.*). 정책
        상수가 바뀌면 이 화면의 값도 함께 고쳐야 한다.
      </p>
    </div>
  );
};

export default CeoLoanProcessGuidePage;
