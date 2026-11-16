#!/usr/bin/env python3
"""End-to-end smoke test for the order / inventory / refund / logistics loop.

The unit tests under ``springboot-api/src/test`` run against in-memory H2 with
``ddl-auto: create-drop``. They prove the rules, but they never touch a real
database, so they cannot catch a broken migration, a missing foreign key or a
transaction that silently commits. This script drives a *running* API over HTTP
instead, which is exactly the gap.

    python3 smoke/smoke_test.py                                  # 127.0.0.1:8081
    python3 smoke/smoke_test.py --base-url http://127.0.0.1:8091
    SMOKE_BASE_URL=http://127.0.0.1:8091 python3 smoke/smoke_test.py

Exit code is 0 when every check passes and 1 otherwise.

Data: every row written here carries a per-run marker (``ORD-SMOKE-<run>-*``,
``SMOKE-<run>-*``). Most of it is deleted again through the API. Two things
survive on purpose, and the script asserts that they do, because they are the
business rules rather than leftovers:

* an approved refund is retained for audit, so its order cannot be deleted;
* a product with stock movements cannot be deleted, so its ledger stays
  explainable.

Run ``smoke/cleanup.sql`` against a development database to purge the remains
of earlier runs.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import urllib.error
import urllib.request
import uuid
from decimal import Decimal

DEFAULT_BASE_URL = os.environ.get("SMOKE_BASE_URL", "http://127.0.0.1:8081")

PENDING = "PENDING_MANUAL_APPROVAL"
APPROVED = "APPROVED_MANUALLY"
REFUND_ONLY = "REFUND_ONLY"
RETURN_AND_REFUND = "RETURN_AND_REFUND"


class Client:
    """Minimal JSON client; stdlib only so the script runs anywhere Python does."""

    def __init__(self, base_url: str, timeout: float) -> None:
        self.base = base_url.rstrip("/")
        if not self.base.endswith("/api"):
            self.base += "/api"
        self.timeout = timeout

    def call(self, method: str, path: str, body=None, headers=None):
        payload = None if body is None else json.dumps(body).encode("utf-8")
        request_headers = {"Content-Type": "application/json"}
        request_headers.update(headers or {})
        request = urllib.request.Request(
            self.base + path, data=payload, method=method, headers=request_headers
        )
        try:
            with urllib.request.urlopen(request, timeout=self.timeout) as response:
                return response.status, _decode(response.read())
        except urllib.error.HTTPError as error:
            return error.code, _decode(error.read())
        except urllib.error.URLError as error:
            raise SystemExit(
                "cannot reach %s: %s\nStart the API first, or pass --base-url." % (self.base, error.reason)
            )


def _decode(raw: bytes):
    text = raw.decode("utf-8", "replace")
    if not text:
        return {}
    try:
        return json.loads(text)
    except json.JSONDecodeError:
        return {"raw": text}


class Report:
    def __init__(self) -> None:
        self.passed = 0
        self.failures = []

    def section(self, title: str) -> None:
        print("\n-- %s" % title)

    def expect(self, label: str, ok: bool, detail="") -> bool:
        if ok:
            self.passed += 1
            print("   PASS  %s" % label)
        else:
            rendered = "%s <- %s" % (label, detail) if detail != "" else label
            self.failures.append(rendered)
            print("   FAIL  %s" % rendered)
        return ok


def data_of(body):
    return body.get("data") if isinstance(body, dict) else None


def message_of(body) -> str:
    return (body.get("message") or "") if isinstance(body, dict) else ""


def elements_of(body):
    return (data_of(body) or {}).get("content") or []


def total_of(body):
    return ((data_of(body) or {}).get("page") or {}).get("totalElements")


def money(value):
    return None if value is None else float(Decimal(str(value)).quantize(Decimal("0.01")))


def movement(movements, movement_type, delta):
    for row in movements or []:
        if row.get("movement_type") == movement_type and row.get("delta") == delta:
            return row
    return None


# --------------------------------------------------------------------------- #
# helpers
# --------------------------------------------------------------------------- #
def create_product(api, report, sku, name, stock, price):
    status, body = api.call("POST", "/products", {"sku": sku, "name": name, "stock": stock, "price": price})
    report.expect("create product %s with stock %s" % (sku, stock), status in (200, 201), message_of(body))


def stock_of(api, sku):
    status, body = api.call("GET", "/products/%s" % sku)
    return (data_of(body) or {}).get("stock") if status == 200 else None


def order_detail(api, number):
    status, body = api.call("GET", "/orders/%s" % number)
    return data_of(body) if status == 200 else {}


def create_order(api, report, ctx, number, sku, quantity):
    status, body = api.call(
        "POST",
        "/orders",
        {
            "order_number": number,
            "customer_id": ctx["customer_id"],
            "items": [{"sku": sku, "quantity": quantity}],
        },
    )
    report.expect("create order %s (%s x %s)" % (number, quantity, sku), status in (200, 201), message_of(body))
    return order_detail(api, number)


# --------------------------------------------------------------------------- #
# scenarios
# --------------------------------------------------------------------------- #
def scenario_health(api, report):
    report.section("health")
    status, body = api.call("GET", "/health")
    report.expect("GET /api/health answers 200", status == 200, status)
    report.expect(
        "health payload names the service",
        (data_of(body) or {}).get("service") == "order-ai-business-api",
        data_of(body),
    )


def seed_customer(api, report, ctx):
    report.section("fixtures")
    status, body = api.call(
        "POST", "/customers", {"name": "Smoke Runner", "email": "smoke+%s@example.com" % ctx["run"]}
    )
    report.expect("create customer", status in (200, 201), message_of(body))
    ctx["customer_id"] = (data_of(body) or {}).get("id")
    report.expect("customer has an id", ctx["customer_id"] is not None, data_of(body))


def scenario_order_and_stock(api, report, ctx):
    """Checkout reserves stock, the price is snapshotted, cancelling releases it."""
    report.section("order reserves stock, cancellation releases it")
    sku = "SMOKE-%s-ORD" % ctx["run"]
    number = "ORD-SMOKE-%s-1" % ctx["run"]
    ctx["sku_ord"] = sku
    ctx["order_ord"] = number

    create_product(api, report, sku, "Smoke order product", 20, "9.50")
    detail = create_order(api, report, ctx, number, sku, 5)

    report.expect("stock dropped 20 -> 15", stock_of(api, sku) == 15, stock_of(api, sku))
    report.expect("order starts as PROCESSING", detail.get("status") == "PROCESSING", detail.get("status"))
    report.expect("order reports stock_reserved", detail.get("stock_reserved") is True, detail.get("stock_reserved"))

    lines = detail.get("items") or []
    report.expect("order carries exactly one line", len(lines) == 1, lines)
    if lines:
        line = lines[0]
        report.expect("line quantity is 5", line.get("quantity") == 5, line.get("quantity"))
        report.expect("line snapshots the unit price", money(line.get("unit_price")) == 9.5, line.get("unit_price"))
        report.expect("line total is 47.50", money(line.get("line_total")) == 47.5, line.get("line_total"))
        report.expect("nothing shipped yet", line.get("shipped_quantity") == 0, line.get("shipped_quantity"))
    report.expect("order total is 47.50", money(detail.get("total_amount")) == 47.5, detail.get("total_amount"))

    status, body = api.call("GET", "/products/%s/stock-movements" % sku)
    sale = movement(data_of(body), "SALE", -5)
    report.expect("ledger records SALE -5", sale is not None, data_of(body))
    report.expect("ledger balance after the sale is 15", sale and sale.get("balance_after") == 15, sale)
    report.expect("ledger row points at the order", sale and sale.get("reference") == number, sale)

    status, body = api.call("GET", "/products/%s/orders" % sku)
    report.expect(
        "product lists the order that uses it",
        status == 200 and any(row.get("order_number") == number for row in data_of(body) or []),
        status,
    )

    status, body = api.call("GET", "/products?sku=%s" % sku)
    rows = elements_of(body)
    report.expect("product list exposes order_count", rows and rows[0].get("order_count") == 1, rows[:1])

    # Oversell must be refused without touching the balance.
    status, body = api.call(
        "POST",
        "/orders",
        {
            "order_number": "ORD-SMOKE-%s-oversell" % ctx["run"],
            "customer_id": ctx["customer_id"],
            "items": [{"sku": sku, "quantity": 999}],
        },
    )
    report.expect("oversell is refused with 409", status == 409 and "Insufficient stock" in message_of(body),
                  "%s %s" % (status, message_of(body)))
    report.expect("refused order left stock at 15", stock_of(api, sku) == 15, stock_of(api, sku))

    status, body = api.call("PUT", "/orders/%s" % number, {"status": "CANCELLED"})
    report.expect("cancel the order", status == 200, message_of(body))
    detail = order_detail(api, number)
    report.expect("order is CANCELLED", detail.get("status") == "CANCELLED", detail.get("status"))
    report.expect("stock came back to 20", stock_of(api, sku) == 20, stock_of(api, sku))

    status, body = api.call("GET", "/products/%s/stock-movements" % sku)
    release = movement(data_of(body), "ORDER_CANCELLED", 5)
    report.expect("ledger records ORDER_CANCELLED +5", release is not None, data_of(body))
    report.expect("ledger balance after the release is 20", release and release.get("balance_after") == 20, release)

    # Re-sending the same status is a no-op, which is what makes retries safe: cancel() is not
    # entered again, so stock cannot be released twice.
    status, body = api.call("PUT", "/orders/%s" % number, {"status": "CANCELLED"})
    report.expect("cancelling twice is an idempotent no-op", status == 200, "%s %s" % (status, message_of(body)))
    report.expect("the repeated cancel did not release stock again", stock_of(api, sku) == 20, stock_of(api, sku))
    status, body = api.call("GET", "/products/%s/stock-movements" % sku)
    releases = [row for row in data_of(body) or [] if row.get("movement_type") == "ORDER_CANCELLED"]
    report.expect("the ledger holds a single release", len(releases) == 1, len(releases))

    status, body = api.call("POST", "/shipments",
                            {"order_number": number, "carrier": "SF", "tracking_number": "TRK-%s-X" % ctx["run"]})
    report.expect("cancelled order cannot be shipped",
                  status == 409 and "Cancelled orders cannot be shipped" in message_of(body),
                  "%s %s" % (status, message_of(body)))


def scenario_refund_limits(api, report, ctx):
    """Refund amounts are capped, review is one-shot, and an approved refund blocks deletion."""
    report.section("refund amount cap and one-shot review")
    sku = "SMOKE-%s-REF" % ctx["run"]
    number = "ORD-SMOKE-%s-2" % ctx["run"]
    ctx["sku_ref"] = sku
    ctx["order_ref"] = number

    create_product(api, report, sku, "Smoke refund product", 10, "12.00")
    create_order(api, report, ctx, number, sku, 2)
    report.expect("stock dropped 10 -> 8", stock_of(api, sku) == 8, stock_of(api, sku))

    status, body = api.call(
        "POST",
        "/refund-requests",
        {"order_number": number, "reason": "too much", "amount": 100, "refund_type": REFUND_ONLY},
        {"Idempotency-Key": "smoke-%s-over" % ctx["run"]},
    )
    report.expect("refund above the order total is refused",
                  status == 409 and "exceeds the remaining refundable" in message_of(body),
                  "%s %s" % (status, message_of(body)))

    status, body = api.call(
        "POST",
        "/refund-requests",
        {"order_number": number, "reason": "damaged box", "amount": 5, "refund_type": REFUND_ONLY},
        {"Idempotency-Key": "smoke-%s-ok" % ctx["run"]},
    )
    report.expect("create a 5.00 refund", status in (200, 201), message_of(body))
    refund = data_of(body) or {}
    report.expect("refund is pending manual approval", refund.get("status") == PENDING, refund.get("status"))
    report.expect("refund keeps its type", refund.get("refund_type") == REFUND_ONLY, refund.get("refund_type"))
    report.expect("refund amount is 5.00", money(refund.get("amount")) == 5.0, refund.get("amount"))

    status, body = api.call(
        "POST",
        "/refund-requests",
        {"order_number": number, "reason": "damaged box", "amount": 5, "refund_type": REFUND_ONLY},
        {"Idempotency-Key": "smoke-%s-ok" % ctx["run"]},
    )
    replay = data_of(body) or {}
    report.expect("same idempotency key replays the original refund",
                  status in (200, 201) and replay.get("id") == refund.get("id") and replay.get("replayed") is True,
                  (status, replay.get("id"), replay.get("replayed")))

    detail = order_detail(api, number)
    report.expect("pending refund is reserved on the order",
                  money(detail.get("pending_refund_amount")) == 5.0, detail.get("pending_refund_amount"))
    report.expect("refundable amount drops to 19.00",
                  money(detail.get("refundable_amount")) == 19.0, detail.get("refundable_amount"))

    status, body = api.call(
        "POST",
        "/refund-requests",
        {"order_number": number, "reason": "second try", "amount": 20, "refund_type": REFUND_ONLY},
        {"Idempotency-Key": "smoke-%s-second" % ctx["run"]},
    )
    report.expect("a second refund above the remainder is refused", status == 409,
                  "%s %s" % (status, message_of(body)))

    status, body = api.call("PUT", "/orders/%s" % number, {"status": "CANCELLED"})
    report.expect("order with a pending refund cannot be cancelled",
                  status == 409 and "resolve them before cancelling" in message_of(body),
                  "%s %s" % (status, message_of(body)))
    status, body = api.call("DELETE", "/orders/%s" % number)
    report.expect("order with a pending refund cannot be deleted",
                  status == 409 and "resolve them before deleting" in message_of(body),
                  "%s %s" % (status, message_of(body)))

    refund_id = refund.get("id")
    status, body = api.call("POST", "/refund-requests/%s/approve" % refund_id)
    approved = data_of(body) or {}
    report.expect("approve the refund", status == 200 and approved.get("status") == APPROVED,
                  "%s %s" % (status, message_of(body)))
    report.expect("approval stamps reviewed_at", approved.get("reviewed_at") is not None, approved.get("reviewed_at"))

    status, body = api.call("POST", "/refund-requests/%s/approve" % refund_id)
    report.expect("a processed refund cannot be approved twice",
                  status == 409 and "already processed" in message_of(body),
                  "%s %s" % (status, message_of(body)))
    status, body = api.call("DELETE", "/refund-requests/%s" % refund_id)
    report.expect("a processed refund is retained for audit",
                  status == 409 and "retained for audit" in message_of(body),
                  "%s %s" % (status, message_of(body)))

    detail = order_detail(api, number)
    report.expect("approved amount shows as refunded",
                  money(detail.get("refunded_amount")) == 5.0, detail.get("refunded_amount"))
    report.expect("nothing is pending any more",
                  money(detail.get("pending_refund_amount")) == 0.0, detail.get("pending_refund_amount"))

    status, body = api.call("POST", "/refund-requests/%s/receive" % refund_id,
                            {"items": [{"order_item_id": detail["items"][0]["id"], "accepted_quantity": 1}]})
    report.expect("refund-only requests accept no inventory",
                  status == 409 and "Only approved return-and-refund" in message_of(body),
                  "%s %s" % (status, message_of(body)))


def scenario_return_and_receipt(api, report, ctx):
    """Returned goods only re-enter stock when they are physically accepted."""
    report.section("returned goods restock on receipt, not on approval")
    sku = ctx["sku_ref"]
    number = "ORD-SMOKE-%s-3" % ctx["run"]
    ctx["order_ret"] = number

    detail = create_order(api, report, ctx, number, sku, 3)
    report.expect("stock dropped 8 -> 5", stock_of(api, sku) == 5, stock_of(api, sku))
    line_id = (detail.get("items") or [{}])[0].get("id")

    status, body = api.call(
        "POST",
        "/refund-requests",
        {
            "order_number": number,
            "reason": "two units broken",
            "amount": 10,
            "refund_type": RETURN_AND_REFUND,
            "return_items": [{"order_item_id": line_id, "quantity": 2}],
        },
        {"Idempotency-Key": "smoke-%s-return" % ctx["run"]},
    )
    report.expect("create a return-and-refund request", status in (200, 201), message_of(body))
    refund = data_of(body) or {}
    refund_id = refund.get("id")
    report.expect("request records the return line", len(refund.get("return_items") or []) == 1,
                  refund.get("return_items"))

    status, body = api.call("POST", "/refund-requests/%s/receive" % refund_id,
                            {"items": [{"order_item_id": line_id, "accepted_quantity": 2}]})
    report.expect("goods cannot be received before approval",
                  status == 409 and "Only approved return-and-refund" in message_of(body),
                  "%s %s" % (status, message_of(body)))

    status, body = api.call("POST", "/refund-requests/%s/approve" % refund_id)
    report.expect("approve the return-and-refund", status == 200, message_of(body))
    report.expect("approval alone does not restock", stock_of(api, sku) == 5, stock_of(api, sku))

    status, body = api.call("POST", "/refund-requests/%s/receive" % refund_id,
                            {"items": [{"order_item_id": line_id, "accepted_quantity": 2}]})
    received = data_of(body) or {}
    report.expect("receive the inspected goods", status == 200, message_of(body))
    report.expect("receipt is flagged on the refund", received.get("inventory_received") is True,
                  received.get("inventory_received"))
    report.expect("stock rose 5 -> 7", stock_of(api, sku) == 7, stock_of(api, sku))

    status, body = api.call("GET", "/products/%s/stock-movements" % sku)
    receipt = movement(data_of(body), "RETURN_RECEIVED", 2)
    report.expect("ledger records RETURN_RECEIVED +2", receipt is not None, data_of(body))
    report.expect("ledger balance after the receipt is 7", receipt and receipt.get("balance_after") == 7, receipt)

    status, body = api.call("POST", "/refund-requests/%s/receive" % refund_id,
                            {"items": [{"order_item_id": line_id, "accepted_quantity": 2}]})
    report.expect("receiving twice is refused",
                  status == 409 and "already received" in message_of(body),
                  "%s %s" % (status, message_of(body)))


def scenario_logistics(api, report, ctx):
    """A parcel carries explicit quantities, moves forward only, and rolls the order up."""
    report.section("logistics: partial parcels, forward-only status, order roll-up")
    sku = "SMOKE-%s-SHIP" % ctx["run"]
    order_a = "ORD-SMOKE-%s-4" % ctx["run"]
    order_b = "ORD-SMOKE-%s-5" % ctx["run"]
    order_c = "ORD-SMOKE-%s-6" % ctx["run"]
    ctx["sku_ship"] = sku
    ctx["orders_ship"] = [order_a, order_b, order_c]

    create_product(api, report, sku, "Smoke parcel product", 10, "5.00")
    detail = create_order(api, report, ctx, order_a, sku, 5)
    line_a = (detail.get("items") or [{}])[0].get("id")
    create_order(api, report, ctx, order_b, sku, 2)
    create_order(api, report, ctx, order_c, sku, 2)
    report.expect("stock dropped 10 -> 1 across the three orders", stock_of(api, sku) == 1, stock_of(api, sku))

    tracking_a = "TRK-%s-A" % ctx["run"]
    tracking_b = "TRK-%s-B" % ctx["run"]

    status, body = api.call("POST", "/shipments", {
        "order_number": order_a, "carrier": "SF Express", "tracking_number": tracking_a,
        "estimated_delivery": "2026-12-01", "receiver_name": "Ann",
        "items": [{"order_item_id": line_a, "quantity": 2}],
    })
    parcel_a = data_of(body) or {}
    report.expect("create a partial parcel of 2 units", status in (200, 201), message_of(body))
    report.expect("parcel starts as CREATED", parcel_a.get("status") == "CREATED", parcel_a.get("status"))
    report.expect("parcel opens its timeline", len(parcel_a.get("events") or []) == 1, parcel_a.get("events"))
    report.expect("parcel carries exactly the requested units",
                  (parcel_a.get("items") or [{}])[0].get("quantity") == 2, parcel_a.get("items"))

    detail = order_detail(api, order_a)
    report.expect("a partial parcel keeps the order PROCESSING", detail.get("status") == "PROCESSING",
                  detail.get("status"))
    report.expect("the order tracks the newest parcel",
                  detail.get("tracking_number") == tracking_a, detail.get("tracking_number"))
    report.expect("line reports 2 shipped units",
                  (detail.get("items") or [{}])[0].get("shipped_quantity") == 2, detail.get("items"))

    status, body = api.call("POST", "/shipments", {
        "order_number": order_a, "carrier": "SF Express", "tracking_number": tracking_b,
        "items": [{"order_item_id": line_a, "quantity": 3}],
    })
    parcel_b = data_of(body) or {}
    report.expect("a second parcel is accepted while processing", status in (200, 201), message_of(body))
    detail = order_detail(api, order_a)
    report.expect("the order flips to SHIPPED once fully shipped", detail.get("status") == "SHIPPED",
                  detail.get("status"))

    status, body = api.call("POST", "/shipments", {
        "order_number": order_a, "carrier": "SF Express", "tracking_number": "TRK-%s-C" % ctx["run"],
    })
    report.expect("a third parcel is refused",
                  status == 409 and "already been shipped" in message_of(body),
                  "%s %s" % (status, message_of(body)))

    detail = order_detail(api, order_b)
    line_b = (detail.get("items") or [{}])[0].get("id")
    status, body = api.call("POST", "/shipments", {
        "order_number": order_b, "carrier": "ZTO", "tracking_number": "TRK-%s-D" % ctx["run"],
        "items": [{"order_item_id": line_b, "quantity": 3}],
    })
    report.expect("shipping more than ordered is refused",
                  status == 409 and "exceeds the unshipped quantity" in message_of(body),
                  "%s %s" % (status, message_of(body)))
    status, body = api.call("POST", "/shipments", {
        "order_number": order_b, "carrier": "ZTO", "tracking_number": tracking_b,
    })
    report.expect("a reused tracking number is refused",
                  status == 409 and "Tracking number already exists" in message_of(body),
                  "%s %s" % (status, message_of(body)))
    status, body = api.call("POST", "/shipments", {
        "order_number": order_b, "carrier": "ZTO", "tracking_number": "TRK-%s-D" % ctx["run"],
    })
    parcel_d = data_of(body) or {}
    report.expect("omitting items ships the rest of the order", status in (200, 201), message_of(body))
    report.expect("the remaining 2 units are on the parcel",
                  (parcel_d.get("items") or [{}])[0].get("quantity") == 2, parcel_d.get("items"))

    # A parcel that is still CREATED can be deleted, and that rolls the order back.
    status, body = api.call("POST", "/shipments", {
        "order_number": order_c, "carrier": "YTO", "tracking_number": "TRK-%s-E" % ctx["run"],
    })
    parcel_e = data_of(body) or {}
    report.expect("ship the third order in one parcel", status in (200, 201), message_of(body))
    report.expect("the third order is SHIPPED", order_detail(api, order_c).get("status") == "SHIPPED",
                  order_detail(api, order_c).get("status"))
    status, body = api.call("DELETE", "/shipments/%s" % parcel_e.get("id"))
    report.expect("deleting a CREATED parcel is allowed", status == 200, message_of(body))
    detail = order_detail(api, order_c)
    report.expect("the order rolls back to PROCESSING", detail.get("status") == "PROCESSING", detail.get("status"))
    report.expect("the order tracking number is cleared", not detail.get("tracking_number"),
                  detail.get("tracking_number"))

    # Status only moves forward, and a picked-up parcel can no longer be deleted.
    status, body = api.call("PUT", "/shipments/%s" % parcel_a.get("id"),
                            {"status": "IN_TRANSIT", "location": "Shanghai hub", "description": "Departed"})
    report.expect("move the parcel to IN_TRANSIT", status == 200, message_of(body))
    report.expect("the status change is on the timeline",
                  len((data_of(body) or {}).get("events") or []) == 2, (data_of(body) or {}).get("events"))
    status, body = api.call("PUT", "/shipments/%s" % parcel_a.get("id"), {"status": "CREATED"})
    report.expect("a status cannot move backwards",
                  status == 409 and "cannot change from IN_TRANSIT to CREATED" in message_of(body),
                  "%s %s" % (status, message_of(body)))
    status, body = api.call("DELETE", "/shipments/%s" % parcel_a.get("id"))
    report.expect("a picked-up parcel cannot be deleted",
                  status == 409 and "have not been picked up" in message_of(body),
                  "%s %s" % (status, message_of(body)))

    # A scan without a status change is a pure timeline append.
    status, body = api.call("POST", "/shipments/%s/events" % parcel_d.get("id"),
                            {"location": "Suzhou hub", "description": "Sorted"})
    report.expect("a scan is appended without moving the status",
                  status == 200 and (data_of(body) or {}).get("status") == "CREATED",
                  "%s %s" % (status, message_of(body)))
    report.expect("the scan shows up in the timeline",
                  len((data_of(body) or {}).get("events") or []) == 2, (data_of(body) or {}).get("events"))

    # Delivery only completes the order once every parcel is signed for.
    status, body = api.call("PUT", "/shipments/%s" % parcel_a.get("id"),
                            {"status": "DELIVERED", "location": "Hangzhou", "description": "Signed by Ann"})
    report.expect("deliver the first parcel", status == 200, message_of(body))
    report.expect("delivery stamps delivered_at", (data_of(body) or {}).get("delivered_at") is not None,
                  (data_of(body) or {}).get("delivered_at"))
    report.expect("a half-delivered order is still SHIPPED",
                  order_detail(api, order_a).get("status") == "SHIPPED",
                  order_detail(api, order_a).get("status"))

    status, body = api.call("PUT", "/shipments/%s" % parcel_b.get("id"), {"status": "DELIVERED"})
    report.expect("deliver the second parcel", status == 200, message_of(body))
    detail = order_detail(api, order_a)
    report.expect("the order becomes DELIVERED", detail.get("status") == "DELIVERED", detail.get("status"))
    report.expect("the order summary reports DELIVERED", detail.get("shipment_status") == "DELIVERED",
                  detail.get("shipment_status"))
    report.expect("both parcels are on the order", len(detail.get("shipments") or []) == 2,
                  detail.get("shipments"))

    status, body = api.call("DELETE", "/shipments/%s" % parcel_a.get("id"))
    report.expect("a delivered parcel is kept as a delivery record",
                  status == 409 and "kept as delivery records" in message_of(body),
                  "%s %s" % (status, message_of(body)))

    status, body = api.call("GET", "/orders/%s/shipments" % order_a)
    report.expect("order shipment endpoint lists both parcels",
                  status == 200 and len(data_of(body) or []) == 2, status)

    status, body = api.call("GET", "/shipments?order_number=%s" % order_a)
    report.expect("shipment list filters by order", total_of(body) == 2, total_of(body))

    report.expect("shipping never moved stock again", stock_of(api, sku) == 1, stock_of(api, sku))


def scenario_delete_guards(api, report, ctx):
    report.section("delete guards")
    status, body = api.call("DELETE", "/products/%s" % ctx["sku_ord"])
    report.expect("a product referenced by orders cannot be deleted",
                  status == 409 and "referenced by orders" in message_of(body),
                  "%s %s" % (status, message_of(body)))

    status, body = api.call("GET", "/products?sku=SMOKE-%s-FREE" % ctx["run"])
    report.expect("the throwaway product exists", total_of(body) == 1, total_of(body))


def cleanup(api, report, ctx):
    """Delete what the API allows and assert the records the audit rules keep."""
    report.section("cleanup")
    kept = []

    status, body = api.call("DELETE", "/orders/%s" % ctx["order_ord"])
    report.expect("delete the cancelled order", status == 200, message_of(body))

    for number in ctx["orders_ship"]:
        status, body = api.call("DELETE", "/orders/%s" % number)
        report.expect("delete order %s and its parcels" % number, status == 200, message_of(body))
        status, body = api.call("GET", "/orders/%s/shipments" % number)
        report.expect("parcels went with order %s" % number, status == 404 or total_of(body) in (0, None),
                      "%s %s" % (status, body))

    for number in (ctx["order_ref"], ctx["order_ret"]):
        status, body = api.call("DELETE", "/orders/%s" % number)
        if status == 200:
            report.expect("order %s was unexpectedly deletable" % number, False, "approved refunds must block it")
        else:
            report.expect("order %s is retained because of its approved refund" % number,
                          status == 409 and "resolve them before deleting" in message_of(body),
                          "%s %s" % (status, message_of(body)))
            kept.append(number)

    status, body = api.call("DELETE", "/products/%s" % ctx["sku_ord"])
    report.expect("a product with ledger history cannot be deleted",
                  status == 409 and "stock movements cannot be deleted" in message_of(body),
                  "%s %s" % (status, message_of(body)))

    status, body = api.call("DELETE", "/products/SMOKE-%s-FREE" % ctx["run"])
    report.expect("an unused product is deleted normally", status == 200, message_of(body))

    ctx["retained"] = kept


# --------------------------------------------------------------------------- #
# entry point
# --------------------------------------------------------------------------- #
def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description="HTTP smoke test for the order AI business API.")
    parser.add_argument("--base-url", default=DEFAULT_BASE_URL,
                        help="API root, e.g. http://127.0.0.1:8081 (default: %(default)s)")
    parser.add_argument("--timeout", type=float, default=15.0, help="per-request timeout in seconds")
    parser.add_argument("--keep", action="store_true",
                        help="skip the cleanup pass and leave this run's data in place")
    args = parser.parse_args(argv)

    api = Client(args.base_url, args.timeout)
    report = Report()
    ctx = {"run": uuid.uuid4().hex[:8], "retained": []}

    print("smoke test against %s (run %s)" % (api.base, ctx["run"]))

    scenario_health(api, report)
    seed_customer(api, report, ctx)
    create_product(api, report, "SMOKE-%s-FREE" % ctx["run"], "Smoke throwaway", 0, "1.00")
    scenario_order_and_stock(api, report, ctx)
    scenario_refund_limits(api, report, ctx)
    scenario_return_and_receipt(api, report, ctx)
    scenario_logistics(api, report, ctx)
    scenario_delete_guards(api, report, ctx)
    if not args.keep:
        cleanup(api, report, ctx)

    total = report.passed + len(report.failures)
    print("\n%s" % ("-" * 68))
    if report.failures:
        print("%s of %s checks failed" % (len(report.failures), total))
        for failure in report.failures:
            print("  - %s" % failure)
    else:
        print("all %s checks passed" % total)
    if ctx["retained"]:
        print("retained for audit: %s" % ", ".join(ctx["retained"]))
        print("purge old runs with: mysql -h127.0.0.1 -uroot -p <db> < smoke/cleanup.sql")
    if args.keep:
        print("--keep was set: this run's data was left in place")
    return 1 if report.failures else 0


if __name__ == "__main__":
    sys.exit(main())
