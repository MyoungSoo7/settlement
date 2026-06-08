#!/usr/bin/env python3
"""Virtual-customer order/payment/settlement data simulator for Lemuel.

Drives the REAL order -> payment flow over HTTP so the downstream
Outbox -> Kafka -> settlement-service pipeline generates settlement rows.

Flow per order:  POST /orders -> POST /payments -> PATCH /payments/{id}/authorize
                 -> PATCH /payments/{id}/capture  (Order=PAID, Payment=CAPTURED)

PG note: the standard authorize/capture path routes to TossPgAdapter etc. which
return mock transaction ids (no real Toss API call), so this runs offline.

After generation, an optional backdate step spreads orders.created_at over the
last N days and propagates the same timestamp to payments + settlements so that
daily/monthly settlement reports look like a real month of activity. Backdating
talks to Postgres via `docker exec lemuel-postgres psql` (no Python DB driver
needed -- stdlib only).

Requires: a running stack with Kafka enabled (e.g. `docker compose up -d`).

Examples
--------
    # everything: seed -> 30k orders -> wait -> backdate over 30 days
    python3 order_simulator.py --orders 30000 --users 1000 --products 50

    # quick smoke test, no backdate
    python3 order_simulator.py --orders 100 --users 20 --no-backdate

    # re-run only the backdate step against a previous run
    python3 order_simulator.py --phase backdate
"""
from __future__ import annotations

import argparse
import json
import os
import random
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path

STATE_FILE = Path(__file__).resolve().parent / ".sim_state.json"


# --------------------------------------------------------------------------- #
# HTTP helpers (stdlib only)
# --------------------------------------------------------------------------- #
class ApiError(Exception):
    def __init__(self, status: int, body: str):
        super().__init__(f"HTTP {status}: {body[:300]}")
        self.status = status
        self.body = body


def _request(method: str, url: str, token: str | None, payload: dict | None,
             timeout: float) -> dict | None:
    data = json.dumps(payload).encode() if payload is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read().decode()
            return json.loads(raw) if raw else None
    except urllib.error.HTTPError as e:
        raise ApiError(e.code, e.read().decode(errors="replace")) from None
    except urllib.error.URLError as e:
        raise ApiError(0, str(e.reason)) from None


def api(method: str, base: str, path: str, token: str | None = None,
        payload: dict | None = None, timeout: float = 30.0,
        retries: int = 2) -> dict | None:
    """HTTP call with a few retries on transient (5xx / network) errors."""
    url = base.rstrip("/") + path
    last: Exception | None = None
    for attempt in range(retries + 1):
        try:
            return _request(method, url, token, payload, timeout)
        except ApiError as e:
            last = e
            # 4xx (except 401) are deterministic -> do not retry
            if e.status and e.status not in (0, 401, 408, 429) and e.status < 500:
                raise
            time.sleep(min(0.25 * (attempt + 1), 2.0))
    assert last is not None
    raise last


# --------------------------------------------------------------------------- #
# State persistence (so phases can run independently)
# --------------------------------------------------------------------------- #
def load_state() -> dict:
    if STATE_FILE.exists():
        return json.loads(STATE_FILE.read_text())
    return {}


def save_state(state: dict) -> None:
    STATE_FILE.write_text(json.dumps(state, indent=2))


# --------------------------------------------------------------------------- #
# Seeding: service token, users, products
# --------------------------------------------------------------------------- #
def register_user(base: str, email: str, password: str, role: str) -> int | None:
    try:
        resp = api("POST", base, "/users",
                   payload={"email": email, "password": password, "role": role})
        return resp["id"] if resp else None
    except ApiError as e:
        # already exists / validation -> skip, caller may still log in
        if e.status in (400, 409):
            return None
        raise


def login(base: str, email: str, password: str) -> str:
    resp = api("POST", base, "/auth/login",
               payload={"email": email, "password": password})
    if not resp or "token" not in resp:
        raise RuntimeError(f"login failed for {email}: {resp}")
    return resp["token"]


def seed(base: str, n_users: int, n_products: int, password: str,
         run_id: str, concurrency: int) -> dict:
    print(f"[seed] creating {n_users} users + {n_products} products ...")

    # --- users (signup is public) ---
    user_ids: list[int] = []

    def make_user(i: int) -> int | None:
        email = f"sim-{run_id}-{i}@example.com"
        return register_user(base, email, password, "USER")

    with ThreadPoolExecutor(max_workers=concurrency) as ex:
        for fut in as_completed([ex.submit(make_user, i) for i in range(n_users)]):
            try:
                uid = fut.result()
                if uid:
                    user_ids.append(uid)
            except Exception as e:  # noqa: BLE001
                print(f"  user create failed: {e}", file=sys.stderr)

    if not user_ids:
        raise RuntimeError("no users created -- aborting")
    print(f"[seed] {len(user_ids)} users created")

    # --- service token: any authenticated user can create products/orders/payments
    #     (SecurityConfig: those routes fall through to anyRequest().authenticated())
    svc_email = f"sim-{run_id}-0@example.com"
    token = login(base, svc_email, password)
    print("[seed] service token acquired")

    # --- products (POST /api/products requires auth) ---
    products: list[dict] = []

    def make_product(i: int) -> dict | None:
        price = random.randint(50, 4000) * 50  # 2,500 .. 200,000 KRW, round
        body = {
            "name": f"sim-product-{run_id}-{i}",
            "description": f"Simulator generated product #{i}",
            "price": price,
            "stockQuantity": 100_000_000,  # effectively unlimited for the run
        }
        resp = api("POST", base, "/api/products", token=token, payload=body)
        return {"id": resp["id"], "price": float(resp["price"])} if resp else None

    with ThreadPoolExecutor(max_workers=concurrency) as ex:
        for fut in as_completed([ex.submit(make_product, i) for i in range(n_products)]):
            try:
                p = fut.result()
                if p:
                    products.append(p)
            except Exception as e:  # noqa: BLE001
                print(f"  product create failed: {e}", file=sys.stderr)

    if not products:
        raise RuntimeError("no products created -- aborting")
    print(f"[seed] {len(products)} products created")

    return {"products": products, "user_ids": user_ids,
            "svc_email": svc_email, "password": password}


# --------------------------------------------------------------------------- #
# Order generation
# --------------------------------------------------------------------------- #
class TokenHolder:
    """Single shared token, refreshed on 401."""

    def __init__(self, base: str, email: str, password: str, token: str):
        self.base = base
        self.email = email
        self.password = password
        self._token = token
        self._lock = threading.Lock()

    @property
    def token(self) -> str:
        return self._token

    def refresh(self) -> str:
        with self._lock:
            self._token = login(self.base, self.email, self.password)
            return self._token


_counters_lock = threading.Lock()
_counters = {"ok": 0, "fail": 0, "min_order_id": None, "max_order_id": None}


def _record(order_id: int | None, ok: bool) -> None:
    with _counters_lock:
        if ok:
            _counters["ok"] += 1
        else:
            _counters["fail"] += 1
        if order_id is not None:
            lo = _counters["min_order_id"]
            hi = _counters["max_order_id"]
            _counters["min_order_id"] = order_id if lo is None else min(lo, order_id)
            _counters["max_order_id"] = order_id if hi is None else max(hi, order_id)


def place_one(base: str, holder: TokenHolder, user_id: int, product: dict,
              method: str) -> None:
    qty = random.randint(1, 3)
    amount = round(product["price"] * qty, 2)
    order_id = None

    def call(m: str, path: str, payload: dict | None = None) -> dict | None:
        try:
            return api(m, base, path, token=holder.token, payload=payload)
        except ApiError as e:
            if e.status == 401:
                holder.refresh()
                return api(m, base, path, token=holder.token, payload=payload)
            raise

    try:
        order = call("POST", "/orders",
                     {"userId": user_id, "productId": product["id"], "amount": amount})
        order_id = order["id"]
        pay = call("POST", "/payments", {"orderId": order_id, "paymentMethod": method})
        pid = pay["id"]
        call("PATCH", f"/payments/{pid}/authorize")
        call("PATCH", f"/payments/{pid}/capture")
        _record(order_id, ok=True)
    except Exception as e:  # noqa: BLE001
        _record(order_id, ok=False)
        if _counters["fail"] <= 20:  # avoid log spam
            print(f"  order failed (user={user_id}, product={product['id']}): {e}",
                  file=sys.stderr)


def generate_orders(base: str, seed_data: dict, n_orders: int, concurrency: int,
                    method: str) -> dict:
    print(f"[orders] generating {n_orders} orders @ concurrency={concurrency} ...")
    holder = TokenHolder(base, seed_data["svc_email"], seed_data["password"],
                         login(base, seed_data["svc_email"], seed_data["password"]))
    products = seed_data["products"]
    user_ids = seed_data["user_ids"]

    start = time.time()
    progress_every = max(1, n_orders // 20)
    done = 0
    with ThreadPoolExecutor(max_workers=concurrency) as ex:
        futures = [
            ex.submit(place_one, base, holder,
                      random.choice(user_ids), random.choice(products), method)
            for _ in range(n_orders)
        ]
        for _ in as_completed(futures):
            done += 1
            if done % progress_every == 0 or done == n_orders:
                ok, fail = _counters["ok"], _counters["fail"]
                rate = done / max(time.time() - start, 1e-6)
                print(f"  {done}/{n_orders}  ok={ok} fail={fail}  {rate:.0f}/s")

    elapsed = time.time() - start
    print(f"[orders] done in {elapsed:.1f}s  ok={_counters['ok']} fail={_counters['fail']}")
    return {
        "captured_ok": _counters["ok"],
        "min_order_id": _counters["min_order_id"],
        "max_order_id": _counters["max_order_id"],
    }


# --------------------------------------------------------------------------- #
# DB access via docker exec psql (stdlib only -- no driver dependency)
# --------------------------------------------------------------------------- #
def _read_env_file(path: Path) -> dict:
    out: dict = {}
    if path.exists():
        for line in path.read_text().splitlines():
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            k, v = line.split("=", 1)
            out[k.strip()] = v.strip()
    return out


def db_creds(args) -> tuple[str, str, str, str]:
    env_file = _read_env_file(Path(args.env_file))
    user = args.db_user or os.environ.get("POSTGRES_USER") or env_file.get("POSTGRES_USER")
    pw = args.db_password or os.environ.get("POSTGRES_PASSWORD") or env_file.get("POSTGRES_PASSWORD")
    if not user or not pw:
        raise RuntimeError(
            "DB credentials not found. Set POSTGRES_USER/POSTGRES_PASSWORD, "
            "pass --db-user/--db-password, or ensure --env-file points at .env")
    return user, pw, args.db_name, args.db_container


def psql(args, sql: str, capture: bool = False) -> str:
    user, pw, db, container = db_creds(args)
    cmd = ["docker", "exec", "-i", "-e", f"PGPASSWORD={pw}", container,
           "psql", "-U", user, "-d", db, "-v", "ON_ERROR_STOP=1"]
    if capture:
        cmd += ["-tA", "-c", sql]
    else:
        cmd += ["-c", sql]
    res = subprocess.run(cmd, capture_output=True, text=True)
    if res.returncode != 0:
        raise RuntimeError(f"psql failed: {res.stderr.strip()}")
    return res.stdout.strip()


def count_settlements(args, min_id: int, max_id: int) -> int:
    out = psql(args,
               f"SELECT count(*) FROM settlements "
               f"WHERE order_id BETWEEN {min_id} AND {max_id};",
               capture=True)
    return int(out or "0")


def wait_for_settlements(args, min_id: int, max_id: int, expected: int,
                         timeout_s: int) -> int:
    print(f"[wait] waiting for settlements (expect ~{expected}) ...")
    deadline = time.time() + timeout_s
    last = -1
    stable_for = 0
    while time.time() < deadline:
        cur = count_settlements(args, min_id, max_id)
        if cur >= expected:
            print(f"[wait] settlements caught up: {cur}/{expected}")
            return cur
        if cur == last:
            stable_for += 1
            # if no progress for ~30s assume the pipeline drained what it could
            if stable_for >= 6:
                print(f"[wait] settlements stable at {cur}/{expected} -- continuing")
                return cur
        else:
            stable_for = 0
            print(f"  settlements: {cur}/{expected}")
        last = cur
        time.sleep(5)
    print(f"[wait] timeout -- proceeding with {last} settlements")
    return last


def backdate(args, min_id: int, max_id: int) -> None:
    days = args.days
    settle_lag = args.settlement_lag_days
    print(f"[backdate] spreading orders {min_id}..{max_id} over last {days} days ...")

    # 1) orders: random instant within the last `days` days (never future)
    psql(args, f"""
        UPDATE orders
           SET created_at = NOW() - (random() * INTERVAL '{days} days'),
               updated_at = NOW()
         WHERE id BETWEEN {min_id} AND {max_id};
    """)
    # keep updated_at a touch after created_at
    psql(args, f"""
        UPDATE orders
           SET updated_at = created_at + INTERVAL '90 seconds'
         WHERE id BETWEEN {min_id} AND {max_id};
    """)

    # 2) payments inherit their order's timestamp (+ small capture delay)
    psql(args, f"""
        UPDATE payments p
           SET created_at = o.created_at,
               updated_at = o.created_at + INTERVAL '120 seconds'
          FROM orders o
         WHERE p.order_id = o.id
           AND p.order_id BETWEEN {min_id} AND {max_id};
    """)

    # 3) settlements: settlement_date = capture date + lag (T+lag cycle),
    #    created_at aligned, confirmed_at set when already confirmed.
    psql(args, f"""
        UPDATE settlements s
           SET created_at = o.created_at + INTERVAL '150 seconds',
               updated_at = o.created_at + INTERVAL '150 seconds',
               settlement_date = (o.created_at + INTERVAL '{settle_lag} days')::date,
               confirmed_at = CASE
                   WHEN s.confirmed_at IS NOT NULL
                   THEN o.created_at + INTERVAL '{settle_lag} days'
                   ELSE NULL END
          FROM orders o
         WHERE s.order_id = o.id
           AND s.order_id BETWEEN {min_id} AND {max_id};
    """)

    # report distribution
    dist = psql(args, f"""
        SELECT settlement_date, count(*)
          FROM settlements
         WHERE order_id BETWEEN {min_id} AND {max_id}
         GROUP BY settlement_date ORDER BY settlement_date;
    """, capture=True)
    print("[backdate] settlements per settlement_date:")
    print(dist or "  (none)")


# --------------------------------------------------------------------------- #
# Health check
# --------------------------------------------------------------------------- #
def health_check(base: str) -> None:
    try:
        api("GET", base, "/actuator/health", timeout=5, retries=1)
        print(f"[check] order-service reachable at {base}")
    except Exception as e:  # noqa: BLE001
        print(f"[check] WARNING: {base} health check failed: {e}", file=sys.stderr)
        print("        Is the stack up? Try: docker compose up -d", file=sys.stderr)


# --------------------------------------------------------------------------- #
# Main
# --------------------------------------------------------------------------- #
def parse_args(argv: list[str]) -> argparse.Namespace:
    p = argparse.ArgumentParser(description=__doc__,
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--base-url", default=os.environ.get("SIM_BASE_URL", "http://localhost:8088"),
                   help="order-service base URL (default: http://localhost:8088)")
    p.add_argument("--orders", type=int, default=30000, help="number of orders to generate")
    p.add_argument("--users", type=int, default=1000, help="number of virtual customers")
    p.add_argument("--products", type=int, default=50, help="number of products to seed")
    p.add_argument("--concurrency", type=int, default=20, help="parallel workers")
    p.add_argument("--method", default="CARD", help="payment method (default: CARD)")
    p.add_argument("--password", default="simPassw0rd!", help="password for seeded users")
    p.add_argument("--days", type=int, default=30, help="backdate window in days")
    p.add_argument("--settlement-lag-days", type=int, default=1,
                   help="settlement_date = capture date + this many days (T+N)")
    p.add_argument("--phase", choices=["all", "seed", "orders", "backdate"], default="all",
                   help="which phase(s) to run")
    p.add_argument("--no-backdate", action="store_true", help="skip the backdate step in 'all'")
    p.add_argument("--settlement-timeout", type=int, default=600,
                   help="max seconds to wait for settlements before backdating")
    # DB / docker
    p.add_argument("--db-container", default="lemuel-postgres")
    p.add_argument("--db-name", default="inter")
    p.add_argument("--db-user", default=None, help="overrides POSTGRES_USER / .env")
    p.add_argument("--db-password", default=None, help="overrides POSTGRES_PASSWORD / .env")
    p.add_argument("--env-file", default=str(Path(__file__).resolve().parents[2] / ".env"),
                   help="path to .env for DB creds (default: repo-root/.env)")
    return p.parse_args(argv)


def main(argv: list[str]) -> int:
    args = parse_args(argv)
    state = load_state()

    run_seed = args.phase in ("all", "seed")
    run_orders = args.phase in ("all", "orders")
    run_back = (args.phase in ("all", "backdate")) and not (args.phase == "all" and args.no_backdate)

    if run_seed or run_orders:
        health_check(args.base_url)

    if run_seed:
        run_id = str(int(time.time()))
        seed_data = seed(args.base_url, args.users, args.products, args.password,
                         run_id, args.concurrency)
        state.update({"run_id": run_id, "seed": seed_data})
        save_state(state)

    if run_orders:
        seed_data = state.get("seed")
        if not seed_data:
            print("ERROR: no seed data in state -- run --phase seed first", file=sys.stderr)
            return 2
        result = generate_orders(args.base_url, seed_data, args.orders,
                                 args.concurrency, args.method)
        if not result["min_order_id"]:
            print("ERROR: no orders captured", file=sys.stderr)
            return 3
        state["orders"] = result
        save_state(state)

    if run_back:
        od = state.get("orders")
        if not od or not od.get("min_order_id"):
            print("ERROR: no order range in state -- run --phase orders first", file=sys.stderr)
            return 4
        lo, hi, expected = od["min_order_id"], od["max_order_id"], od["captured_ok"]
        wait_for_settlements(args, lo, hi, expected, args.settlement_timeout)
        backdate(args, lo, hi)

    print("[done]")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
