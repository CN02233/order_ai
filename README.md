# AI-Ready Customer Inquiry & Order Automation System

The project is split into three independent applications:

- `python-api/` — FastAPI inquiry analysis service, deterministic classification rules, and tests. It does not own business data.
- `springboot-api/` — Spring Boot proxy for the business API.
- `vue-workbench/` — Vue 2 customer support workbench.

Business rules are deterministic; refund requests are created for human review and never execute refunds automatically.

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

For an existing database, apply `springboot-api/src/main/resources/db/migration/V2__add_order_created_at.sql` once before starting the updated API. New databases should include `created_at` on `orders` as defined in `springboot-api/src/main/resources/schema.sql`.

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
```

Open `http://localhost:8080`. Vue sends `/api` requests through the dev proxy to Spring Boot. Start the Python API and Spring Boot service first.

## Run Python tests

```sh
cd python-api
.venv/bin/python -m pytest -q
```

## Main API endpoints

- `POST /inquiries` classify and persist an inquiry (requires `Idempotency-Key`)
- `GET /customers/by-email/{email}` and `/customers/by-email/{email}/orders`
- `GET /orders/{order_number}`
- `GET /products/{sku}`
- `POST /refund-requests` creates a pending manual-review request only
- `GET /health`
