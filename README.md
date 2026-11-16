# AI-Ready Customer Inquiry & Order Automation System

The project is split into three independent applications:

- `python-api/` — FastAPI inquiry analysis service, deterministic classification rules, and tests. It does not own business data.
- `springboot-api/` — Spring Boot proxy for the business API.
- `vue-workbench/` — Vue 2 customer support workbench.

Business rules are deterministic; refund requests are created for human review and never execute refunds automatically.

## Business model

Inventory, orders and refunds are one closed loop, not three CRUD screens:

- **Order is the core.** An order reserves stock when it is created and releases it when it is cancelled.
- **Inventory supports fulfilment.** Every stock change goes through `InventoryService`, which locks the
  product row, refuses negative stock and appends a row to `stock_movements` in the same transaction.
  No controller writes `products.stock` directly.
- **Refund hangs off an order.** One order can have several refund requests, but the sum of pending and
  approved requests never exceeds the order total. Approval only records a human decision — no money
  moves. Stock increases only when returned goods are physically accepted, and only for the accepted
  quantity. Only `PROCESSING`, `SHIPPED` and `DELIVERED` orders accept a refund request: a cancelled
  order never had goods to send back.
- **Logistics fulfils the order.** A parcel (`shipments`) carries explicit lines of an order
  (`shipment_items`) and has its own tracking timeline (`shipment_events`). One order can ship in
  several parcels; the units across non-cancelled parcels can never exceed the ordered quantity.
  Shipping does not touch stock again — checkout already reserved it. A parcel can only be created for
  an order that is still `PROCESSING`, i.e. one that still has units in the warehouse.
- **Inventory rows are never deleted out from under history.** A product referenced by any order line
  is refused (409) and its row carries `order_count`, so the workbench greys the delete button out.
  Products with stock movements are refused for the same reason: the ledger has to stay explainable.

Order status moves forward only: `PROCESSING → SHIPPED → DELIVERED`, with `CANCELLED` allowed from
`PROCESSING` only (and never while a refund request is open or approved, or while a parcel is in
flight). The order turns `SHIPPED` only once **every** unit is on a parcel, so a partially shipped
order stays `PROCESSING` and keeps accepting the next parcel; it becomes `DELIVERED` when every parcel
is delivered. Deleting the last parcel rolls a shipped order back to `PROCESSING`.

## 1. Configure and start the Python API

Open `python-api/` as a PyCharm project. Use its existing `.venv/bin/python` interpreter and the **Order AI API** run configuration. Its working directory must be `python-api/`. Debug mode intentionally runs without Uvicorn `--reload` so breakpoints attach to the request-serving process; restart the service after Python edits. The API loads `python-api/.env` automatically; copy `.env.example` to `.env` and set the MySQL URL there. The database itself must already exist. Without `.env`, local development falls back to SQLite at `python-api/data/app.db`.

Alternative terminal commands from the repository root:

```sh
cd python-api
.venv/bin/uvicorn app.main:app --reload --host 127.0.0.1 --port 8000
```

Business demo data remains in MySQL and is managed by Java. Python API docs: `http://127.0.0.1:8000/docs`.

## 2. Start Spring Boot

Spring Boot connects to the same MySQL database (`order_ai`) as the Python setup and reads connection details and the Python service address from the active `local` profile in `springboot-api/src/main/resources/application-local.yml`. The base `application.yml` activates this profile, so no command-line or IDE environment variables are required. Edit the profile YAML to change these values.

For an existing database, apply the migrations in `springboot-api/src/main/resources/db/migration/` in order: `V2__add_order_created_at.sql`, `V3__order_inventory_refund_lifecycle.sql`, then `V4__inventory_ledger_and_review_audit.sql`. Each runs once.

- **V3** widens legacy `INT` keys to `BIGINT` (foreign keys are dropped and recreated), adds `orders.total_amount` / `stock_reserved`, backfills `order_items` price and name snapshots, adds refund `amount` / `refund_type` / `inventory_received`, and creates `refund_return_items`. Historical orders are backfilled but marked `stock_reserved = false`, so cancelling one never adds stock it never reserved.
- **V4** adds the `stock_movements` ledger and `refund_requests.reviewed_at`.

New databases should use the full schema in `springboot-api/src/main/resources/schema.sql`, which already reflects all of the above.

```sh
cd springboot-api
mvn spring-boot:run
```

The proxy listens on port 8081 and forwards requests to the Python API at port 8000. Override with `BUSINESS_API_URL` if needed.

## 3. Start Vue 2 workbench

```sh
cd vue-workbench
npm install
npm run serve
npm run format      # prettier --write, 100 columns, single quotes, no semicolons
```

Front-end style is enforced by Prettier (`.prettierrc.json`): 100-column lines, two-space indent,
single quotes, no semicolons, one attribute per line when a tag no longer fits. Run `npm run format`
before committing; `npm run format:check` only verifies.

Open `http://localhost:8080`. Vue sends `/api` requests through the dev proxy to Spring Boot (default `http://localhost:8081`; override with `BUSINESS_API_URL` when testing another instance). The workbench renders create/edit as modal dialogs: customer, product and order references are chosen from dropdowns, stock changes go through the dedicated adjustment endpoint, refund return lines are picked from the selected order's items, and a shipment is created by choosing an order and confirming how many units of each line go into the parcel.

## Database migrations

`springboot-api/src/main/resources/schema.sql` is the full schema for a brand-new database. An existing
database is upgraded by applying the files in `src/main/resources/db/migration/` in order
(`V2` … `V5`); there is no Flyway dependency, so run them with the MySQL client. `V5` also backfills one
parcel per order that already carried a free-text tracking number.

## Run backend tests

```sh
cd springboot-api
mvn test
```

The tests run against in-memory H2 with `ddl-auto: create-drop` (`src/test/resources/application-test.yml`) and cover stock reservation, insufficient-stock rollback, cancellation release, the status machine, order deletion (stock release, child cascade, open-refund guard), cumulative refund limits, the refundable-order whitelist, manual review, partial goods receipt, idempotency, the whole logistics flow (full/partial shipment, processing-only parcel creation, over-shipment refusal, forward-only parcel status, order delivery roll-up, parcel deletion rollback, cascade delete), and customer deletion (allowed only while the customer owns no order).

## Run frontend tests

The workbench carries two layers, both in `vue-workbench`. They complement the backend suites: the
Jest run proves the browser refuses illegal options before a request is ever sent, and the Playwright
run proves the rendered page actually behaves that way.

```sh
cd vue-workbench
npm install

npm run test:unit                     # Jest + Vue Test Utils, no backend required
PW_CHANNEL=chrome npm run test:e2e    # Playwright, needs the API on 8081
```

`test:unit` mounts `App.vue` with a stubbed HTTP client and covers what the UI must not allow: the
logistics order dropdown offers only `PROCESSING`, the refund dropdown offers
`PROCESSING/SHIPPED/DELIVERED` and never `CANCELLED`, editing keeps the record's current order
visible, the parcel status machine never moves backwards, shipment quantities prefill with the
remaining units, an inventory row linked to orders keeps its delete button disabled, and every column
header resolves to a translation instead of a raw field name.

`test:e2e` drives a real browser. It starts the dev server on 8082 itself, reuses two fixture products
and resets their stock, seeds one customer plus three orders (processing, fully shipped, cancelled),
asserts the dropdown options and prefilled quantities, then deletes the parcels, orders and customer
it created. The fixture products stay because their stock movements are append-only and a product with
movements cannot be deleted by design; they are reused rather than recreated, so the catalogue does
not grow. Options:

```sh
PW_CHANNEL=chrome npx playwright test --headed   # watch the clicks
PW_CHANNEL=chrome npx playwright test -g "库存"   # one test only
PW_CHANNEL=chrome npx playwright test --ui       # step through, time-travel debugging
BUSINESS_API_URL=http://127.0.0.1:8091 PW_CHANNEL=chrome npm run test:e2e   # other instance
```

`PW_CHANNEL=chrome` reuses an installed Chrome instead of Playwright's bundled build; drop it once
`npx playwright install chromium` has run (required in CI). Failures leave a trace under
`test-results/`, replayable with `npx playwright show-trace test-results/<case>/trace.zip`.

## Run the smoke test

The H2 tests prove the rules but never touch a real database, so they cannot catch a broken migration,
a missing foreign key or a transaction that silently commits. `smoke/smoke_test.py` drives a **running**
API over HTTP and walks the whole loop: checkout reserves stock, cancellation releases it, refund amounts
are capped, review is one-shot, returned goods restock only on receipt, parcels move forward only, and
deleting records respects the audit rules.

```sh
# with the API already running on 8081
python3 smoke/smoke_test.py

# against another instance
python3 smoke/smoke_test.py --base-url http://127.0.0.1:8091
SMOKE_BASE_URL=http://127.0.0.1:8091 python3 smoke/smoke_test.py
```

It needs nothing but Python 3 — no third-party packages. Exit code is `0` when every check passes and `1`
otherwise, so it can gate a deploy. `--keep` skips the cleanup pass if you want to inspect the data.

Two things survive each run **on purpose**, and the script asserts that they do, because they are the
business rules: an approved refund is retained for audit (its order cannot be deleted) and a product with
stock movements keeps its ledger (it cannot be deleted). Purge the remains of earlier runs on a
development database with:

```sh
mysql -h127.0.0.1 -uroot -p order_ai < smoke/cleanup.sql
```

That script deletes by name prefix (`ORD-SMOKE-*`, `SMOKE-*`, `smoke+*@example.com`), so only point it at
a database whose contents you can identify.

## Run Python tests

```sh
cd python-api
.venv/bin/python -m pytest -q
```

## Main API endpoints

Management APIs are paginated and filterable; all responses use the `{status, message, data, timestamp}` envelope.

- `POST /inquiries` classify and persist an inquiry (requires `Idempotency-Key`)
- `GET /api/customers?limit=200` — customer dropdown for order forms
- `GET /customers/by-email/{email}` and `/customers/by-email/{email}/orders`
- `DELETE /api/customers/{id}` — remove a customer that never ordered; refused (409) while any order still belongs to it, because the order keeps its customer for history
- `GET /api/orders`, `POST /api/orders` — list/create; create reserves stock and snapshots prices
- `GET /api/orders/{order_number}` — items (with `shipped_quantity`), shipments, refunds, pending/refunded/refundable amounts
- `PUT /api/orders/{order_number}` — advance status or cancel
- `DELETE /api/orders/{order_number}` — hard-delete an order and its children (parcels, refunds, items); refused (409) while a pending/approved refund is open, otherwise releases any still-reserved stock first. The stock ledger is kept for audit.
- `GET /api/orders/{order_number}/shipments` — parcels of one order
- `GET /api/shipments` — list parcels; filters `order_number`, `tracking_number`, `carrier`, `status`, `created_from`, `created_to`
- `POST /api/shipments` — create a parcel, `{order_number, carrier, tracking_number, estimated_delivery, receiver_name, note, items}`; `order_number` must still be `PROCESSING`, and omitting `items` ships every unshipped unit. The order turns `SHIPPED` once nothing is left in the warehouse.
- `GET /api/shipments/{id}` — parcel with its order lines and tracking timeline
- `PUT /api/shipments/{id}` — carrier/tracking correction, or a forward-only status change (`CREATED → PICKED_UP → IN_TRANSIT → DELIVERED`, `EXCEPTION`, `CANCELLED`); `location`/`description` are stored on the event it creates
- `POST /api/shipments/{id}/events` — append one tracking scan, optionally with a status
- `DELETE /api/shipments/{id}` — remove a parcel that has not been picked up; deleting the last one rolls the order back to `PROCESSING`
- `GET /api/products`, `POST /api/products` — catalogue; the create payload is the opening balance, and each list row carries `order_count` (order lines pointing at the product) so the UI knows the row is not deletable
- `PUT /api/products/{sku}` — catalogue edit only; a stock change here is rejected
- `DELETE /api/products/{sku}` — refused (409) while any order line references the product or when it has stock movements
- `GET /api/products/{sku}/orders` — orders that bought this product
- `GET /api/products/{sku}/stock-movements` — append-only stock ledger
- `POST /api/products/{sku}/stock-adjustments` — manual correction, `{delta, note}`, reason required
- `GET /api/refund-requests`, `POST /api/refund-requests` — list/create (requires `Idempotency-Key`)
- `GET /api/refund-requests/{id}` — refund with order summary and return lines
- `POST /api/refund-requests/{id}/approve` / `reject` — manual decision only
- `POST /api/refund-requests/{id}/receive` — accept returned goods; only accepted quantities re-enter stock
- `GET /api/health`

## Example requests

All request/response bodies below are the `data` part of the standard envelope.

Create an order (`customer_id` is the numeric customer id, not an email; each line uses `sku` + `quantity`):

```sh
curl -X POST http://127.0.0.1:8081/api/orders \
  -H 'Content-Type: application/json' \
  -d '{"order_number":"ORD-DEMO-1","customer_id":1,"items":[{"sku":"MUG-001","quantity":2}]}'
```

Create a refund request (`Idempotency-Key` is required; `return_items` is only for `RETURN_AND_REFUND`,
and `order_item_id` comes from the order detail's `items[].id`):

```sh
curl -X POST http://127.0.0.1:8081/api/refund-requests \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: refund-demo-1' \
  -d '{"order_number":"ORD-DEMO-1","reason":"arrived damaged","amount":12.99,"refund_type":"RETURN_AND_REFUND","return_items":[{"order_item_id":11,"quantity":1}]}'
```

Ship a full order (`items` omitted) or a partial one (`order_item_id` comes from the order detail's
`items[].id`; the sum per line may not exceed `quantity - shipped_quantity`):

```sh
curl -X POST http://127.0.0.1:8081/api/shipments \
  -H 'Content-Type: application/json' \
  -d '{"order_number":"ORD-DEMO-1","carrier":"SF Express","tracking_number":"SF1234567890","estimated_delivery":"2026-11-20","items":[{"order_item_id":11,"quantity":1}]}'
```

Then move it along (each call appends a tracking event):

```sh
curl -X PUT http://127.0.0.1:8081/api/shipments/1 \
  -H 'Content-Type: application/json' \
  -d '{"status":"DELIVERED","location":"Hangzhou","description":"Signed by customer"}'
```

Paginated lists (`GET /api/orders`, `/api/products`, `/api/refund-requests`, `/api/shipments`) return a stable DTO:
`data.content` is the page of rows and `data.page` holds `size`, `number`, `totalElements` and `totalPages`.

