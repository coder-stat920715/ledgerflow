# LedgerFlow

Spring Boot 3 / Java 21 service for accounts, idempotent transfers and an immutable double-entry ledger.
Design documents and diagrams are in [ARCHITECTURE.md](ARCHITECTURE.md).

## Run with Docker

```bash
cp .env.example .env        # then edit the secrets
docker compose up --build
```

| URL | What |
|---|---|
| http://localhost:8080/swagger-ui.html | API docs (dev profile) |
| http://localhost:8080/actuator/health | Health |
| http://localhost:8081 | Adminer (System: PostgreSQL, Server: postgres) |

## Build and test

```bash
mvn test       # unit tests (no Docker needed)
mvn verify     # + integration tests (Testcontainers, needs Docker)
```

## Try it

```bash
curl -s -X POST localhost:8080/api/v1/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"admin@ledgerflow.local","password":"change-me-admin-password"}'

curl -s -X POST localhost:8080/api/v1/transfers \
  -H "Authorization: Bearer $TOKEN" -H "Idempotency-Key: $(uuidgen)" \
  -H 'Content-Type: application/json' \
  -d '{"sourceAccountId":"...","destinationAccountId":"...","amount":25.00,"currency":"USD"}'
```

## Known gap

There is no deposit or funding endpoint: new accounts start at zero. A production system would add a staff
deposit flow or an external payment-gateway integration. The integration tests fund accounts with direct SQL.
