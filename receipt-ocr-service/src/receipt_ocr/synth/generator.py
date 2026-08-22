"""합성 영수증 데이터 생성 — 매입 1건 + 그에 대응하는(또는 일부러 어긋나는) 영수증 내용.

**왜 합성인가**: 한국 카드 영수증의 공개 라벨셋은 사실상 없다. 공개 영수증 데이터셋(CORD 등)은
인도네시아 영수증 기반이라 한국 전표 양식·한글 상호·원 단위 금액과 다르다. 그래서 Phase 0 은
합성으로 하네스를 세우고 baseline 을 재는 데까지만 쓴다.

**합성의 한계를 분명히 해 둔다**: 여기서 나온 점수는 실물 영수증 성능이 아니다. 렌더러가 만든
글자는 이미 '완벽한 디지털 글리프' 라서 실제 감열지의 번짐·결·인쇄 불량을 흉내만 낸다. 합성
점수는 **모델 간 상대 비교와 하네스 검증**에만 쓰고, 절대 성능 주장에는 쓰지 않는다.

생성은 시드로 결정적이다 — 같은 시드는 같은 골든셋을 만든다(리플레이 가능한 비교의 전제).
"""

from __future__ import annotations

import datetime as _dt
import random
from dataclasses import dataclass, field
from decimal import Decimal
from enum import Enum
from zoneinfo import ZoneInfo

KST = ZoneInfo("Asia/Seoul")


class Scenario(str, Enum):
    """영수증과 매입의 관계 — 정답 판정을 결정하는 축."""

    #: 영수증 = 매입, 같은 날. 정답 MATCHED.
    CLEAN = "CLEAN"
    #: 심야 결제라 전표 날짜가 매입일 +1. 허용 오차가 흡수해야 한다. 정답 MATCHED.
    NEXT_DAY = "NEXT_DAY"
    #: 거래일이 인쇄되지 않음(간이 영수증). 정답 NEEDS_REVIEW.
    NO_DATE = "NO_DATE"
    #: 금액이 매입과 다름 — 오첨부·조작. 정답 MISMATCHED. **여기서 통과하면 증빙 없는 지출이다.**
    AMOUNT_TAMPERED = "AMOUNT_TAMPERED"
    #: 한참 전 영수증을 다시 붙임. 정답 MISMATCHED.
    STALE_DATE = "STALE_DATE"


class RenderCondition(str, Enum):
    """촬영·인쇄 품질 — 정답은 바꾸지 않고 난이도만 바꾼다."""

    PRISTINE = "PRISTINE"
    FADED = "FADED"
    CRUMPLED = "CRUMPLED"
    SKEWED = "SKEWED"
    LOW_LIGHT = "LOW_LIGHT"
    #: 휴대폰으로 멀리서 찍어 해상도가 낮고 JPEG 압축이 심하다 — 실제 업로드에서 가장 흔한 열화.
    LOW_RES = "LOW_RES"
    #: 형광등·플래시 반사로 한 줄이 하얗게 날아간다. 하필 합계 줄이 걸리면 판독이 무너진다.
    GLARE = "GLARE"


#: 거래일시 표기는 가맹점 POS 마다 다르다 — 모델이 한 가지 형식에만 맞춰지지 않도록 섞는다.
DATE_FORMATS = ["%Y-%m-%d %H:%M:%S", "%Y/%m/%d %H:%M", "%y.%m.%d %H:%M:%S", "%Y년 %m월 %d일 %H:%M"]

MERCHANTS = [
    ("김밥천국 강남점", "서울 강남구 테헤란로 123", ["김밥", "라면", "돈까스", "제육덮밥"]),
    ("스타벅스 역삼역점", "서울 강남구 역삼로 45", ["아메리카노", "카페라떼", "치즈케이크"]),
    ("이마트24 삼성점", "서울 강남구 봉은사로 77", ["생수 2L", "샌드위치", "커피우유", "건전지"]),
    ("본죽 논현점", "서울 강남구 학동로 9", ["전복죽", "야채죽", "낙지죽"]),
    ("교보문고 강남점", "서울 서초구 강남대로 465", ["도서", "필기구", "노트"]),
    ("올리브영 신논현점", "서울 강남구 강남대로 470", ["핸드크림", "마스크팩", "칫솔세트"]),
    ("한신포차 selected", "서울 강남구 봉은사로 112", ["닭발", "계란말이", "소주"]),
    ("GS칼텍스 대치주유소", "서울 강남구 남부순환로 3030", ["휘발유"]),
]

OWNERS = ["홍길동", "김영수", "박민정", "이철호", "정수연"]


@dataclass(frozen=True)
class LineItem:
    name: str
    quantity: int
    unit_price: Decimal

    @property
    def amount(self) -> Decimal:
        return self.unit_price * self.quantity


@dataclass(frozen=True)
class SyntheticReceipt:
    """렌더러에 넘길 영수증 1장의 내용 + 그 영수증에 대응하는 매입 사실.

    :param printed_total: 영수증에 **인쇄되는** 총액 (정답 라벨)
    :param printed_datetime: 영수증에 인쇄되는 거래일시. None 이면 인쇄하지 않는다.
    :param captured_amount: 카드 매입 금액 — 시나리오에 따라 ``printed_total`` 과 다를 수 있다.
    """

    case_id: str
    scenario: Scenario
    condition: RenderCondition
    merchant_name: str
    business_no: str
    owner: str
    address: str
    phone: str
    items: list[LineItem]
    printed_total: Decimal
    printed_datetime: _dt.datetime | None
    date_format: str
    card_masked: str
    approval_no: str
    capture_id: str
    captured_amount: Decimal
    captured_at: _dt.datetime
    note: str = ""
    show_currency_suffix: bool = False
    #: 할인·포인트 사용액. 0 이 아니면 영수증에 소계/할인/합계가 함께 찍혀 **금액 후보가 늘어난다**
    #: — 모델이 품목합계나 소계를 총액으로 잘못 집는지 보는 축이다.
    discount: Decimal = Decimal("0")

    @property
    def printed_date(self) -> _dt.date | None:
        return self.printed_datetime.date() if self.printed_datetime else None

    @property
    def subtotal(self) -> Decimal:
        """할인 전 품목 합계. 할인이 있을 때만 영수증에 따로 찍힌다."""
        return self.printed_total + self.discount

    @property
    def supply_amount(self) -> Decimal:
        """공급가액 — 총액에서 부가세를 뺀 값(원 단위 반올림)."""
        return self.printed_total - self.vat

    @property
    def vat(self) -> Decimal:
        """부가세 10% — 총액의 1/11 을 원 단위로 반올림한다."""
        return (self.printed_total / Decimal("11")).quantize(Decimal("1"))


def _round_to_won(value: Decimal) -> Decimal:
    return value.quantize(Decimal("1"))


def generate(count: int, *, seed: int = 20260821, base_date: _dt.date | None = None,
             scenarios: list[Scenario] | None = None) -> list[SyntheticReceipt]:
    """골든셋 후보를 결정적으로 생성한다.

    시나리오는 순환 배분한다 — 무작위로 뽑으면 작은 셋에서 MISMATCHED 가 0건이 되어 치명 오류
    지표가 측정 불가가 되는 일이 생긴다.
    """
    if count <= 0:
        raise ValueError(f"생성 건수는 양수여야 합니다: {count}")
    rng = random.Random(seed)
    base = base_date or _dt.date(2026, 3, 4)
    pool = scenarios or list(Scenario)
    receipts: list[SyntheticReceipt] = []

    for index in range(count):
        scenario = pool[index % len(pool)]
        # 시나리오와 촬영조건은 **교차**해야 한다. 둘 다 index % N 으로 돌리면 주기가 같아
        # 완전히 결착되고(CLEAN 은 항상 PRISTINE...), 조건별 단면이 사실은 시나리오를 재게 된다.
        conditions = list(RenderCondition)
        condition = conditions[(index // len(pool)) % len(conditions)]
        merchant, address, menu = MERCHANTS[rng.randrange(len(MERCHANTS))]

        items = _build_items(rng, menu)
        subtotal = _round_to_won(sum((item.amount for item in items), Decimal("0")))
        # 3건에 1건은 할인·포인트 사용 — 영수증에 금액이 여러 개 찍혀 '무엇이 총액인가' 가 어려워진다.
        discount = (
            _round_to_won(subtotal * Decimal(rng.randrange(5, 25)) / Decimal("100"))
            if index % 3 == 1 else Decimal("0")
        )
        printed_total = subtotal - discount

        captured_day = base + _dt.timedelta(days=rng.randrange(0, 21))
        captured_at = _dt.datetime.combine(
            captured_day, _dt.time(rng.randrange(8, 23), rng.randrange(0, 60), rng.randrange(0, 60)),
            tzinfo=KST,
        )

        printed_dt: _dt.datetime | None = captured_at
        captured_amount = printed_total
        note = ""

        if scenario is Scenario.NEXT_DAY:
            # 자정 직전 결제 — 전표는 다음 날로 찍힌다. 허용 오차(±1일)가 흡수해야 한다.
            captured_at = captured_at.replace(hour=23, minute=52)
            printed_dt = captured_at + _dt.timedelta(hours=1)
            note = "심야 결제 — 전표 날짜가 매입일 +1"
        elif scenario is Scenario.NO_DATE:
            printed_dt = None
            note = "간이 영수증 — 거래일 미인쇄"
        elif scenario is Scenario.AMOUNT_TAMPERED:
            # 매입은 실제 결제액, 영수증은 다른 금액 — 오첨부이거나 조작이다.
            delta = Decimal(rng.randrange(1000, 40000))
            captured_amount = _round_to_won(max(Decimal("100"), printed_total - delta))
            note = "금액 불일치 — 다른 건 영수증 오첨부"
        elif scenario is Scenario.STALE_DATE:
            printed_dt = captured_at - _dt.timedelta(days=rng.randrange(3, 30))
            note = "과거 영수증 재첨부"

        receipts.append(
            SyntheticReceipt(
                case_id=f"SYN-{index:04d}",
                scenario=scenario,
                condition=condition,
                merchant_name=merchant,
                business_no=f"{rng.randrange(100, 999)}-{rng.randrange(10, 99)}-{rng.randrange(10000, 99999)}",
                owner=OWNERS[rng.randrange(len(OWNERS))],
                address=address,
                phone=f"02-{rng.randrange(200, 999)}-{rng.randrange(1000, 9999)}",
                items=items,
                printed_total=printed_total,
                printed_datetime=printed_dt,
                date_format=DATE_FORMATS[index % len(DATE_FORMATS)],
                card_masked=f"{rng.randrange(4000, 5599)}-****-****-{rng.randrange(1000, 9999)}",
                approval_no=str(rng.randrange(10000000, 99999999)),
                capture_id=f"CAP-{index:04d}",
                captured_amount=captured_amount,
                captured_at=captured_at,
                note=note,
                show_currency_suffix=(index % 3 == 0),
                discount=discount,
            )
        )
    return receipts


def _build_items(rng: random.Random, menu: list[str]) -> list[LineItem]:
    """품목 1~4개. 단가는 100원 단위 — 실제 소매가가 그렇다."""
    picks = rng.sample(menu, k=min(len(menu), rng.randrange(1, 4)))
    return [
        LineItem(name=name, quantity=rng.randrange(1, 4),
                 unit_price=Decimal(rng.randrange(15, 260) * 100))
        for name in picks
    ]
