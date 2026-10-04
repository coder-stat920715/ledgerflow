# LedgerFlow - Architecture

LedgerFlow is a stateless Spring Boot 3 / Java 21 service for customer accounts, money transfers and an immutable double-entry ledger. PostgreSQL is the system of record. Redis provides caching and distributed locking.

## Table of Contents
1. [Architectural Principles](#1-architectural-principles)
2. [High-Level Design](#2-high-level-design)
3. [Low-Level Design](#3-low-level-design)
4. [Concurrency, Idempotency & Consistency](#4-concurrency-idempotency--consistency)
5. [Caching Strategy](#5-caching-strategy)
6. [Security Model](#6-security-model)
7. [Index Rationale](#7-index-rationale)

---

## 1. Architectural Principles

| Principle | Application |
|---|---|
| Layered / Clean | `controller -> service -> repository`. Controllers only know DTOs, services own transactions and business rules, and entities never leave the service layer. |
| Stateless | No HTTP session. Identity is carried in a signed JWT, so any instance can serve any request. |
| Money correctness | `NUMERIC(19,4)` amounts, `CHECK (balance >= 0)`, optimistic `@Version`, an ordered Redis lock per transfer, and an append-only ledger. |
| Idempotent writes | A unique `(initiated_by, idempotency_key)` constraint makes retried transfers return the original result. |
| Fail loudly | A global `@RestControllerAdvice` returns RFC 7807 `application/problem+json` for every error. |
| Observable | Actuator health and metrics, Prometheus scrape endpoint, and a correlation id on every request. |

---

## 2. High-Level Design

### 2.1 System Architecture (C4 Container Diagram)

```mermaid
C4Container
    title Container Diagram - LedgerFlow Platform

    Person(customer, "Customer", "Registers, opens accounts, transfers money")
    Person(staff, "Teller / Admin", "Manages accounts, freezes, reverses transactions")

    System_Boundary(platform, "LedgerFlow Platform") {
        Container(gateway, "API Gateway", "Nginx / Cloud Gateway", "TLS termination, rate limiting, routing, request id injection")
        Container(api, "LedgerFlow Service", "Java 21, Spring Boot 3", "REST API, JWT validation, RBAC, business logic, ledger posting")
        ContainerDb(pg, "PostgreSQL", "PostgreSQL 16", "Users, accounts, transactions, ledger entries")
        ContainerDb(redis, "Redis", "Redis 7", "Balance and lookup cache, distributed transfer locks")
    }

    System_Ext(monitor, "Prometheus / Grafana", "Metrics scraping and dashboards")
    System_Ext(docs, "Swagger UI", "OpenAPI 3 interactive documentation")

    Rel(customer, gateway, "Uses", "HTTPS / JSON")
    Rel(staff, gateway, "Uses", "HTTPS / JSON")
    Rel(gateway, api, "Forwards requests", "HTTP")
    Rel(api, pg, "Reads / writes", "JDBC (HikariCP)")
    Rel(api, redis, "Caches, locks", "RESP (Lettuce)")
    Rel(monitor, api, "Scrapes /actuator/prometheus", "HTTP")
    Rel(docs, api, "Reads /v3/api-docs", "HTTP")
```

### 2.2 Data Flow & Sequence Diagram (Transfer Request)

```mermaid
sequenceDiagram
    autonumber
    actor C as Client
    participant G as API Gateway
    participant F as Security Filter Chain
    participant TC as TransactionController
    participant TS as TransactionService
    participant L as Redis (Locks)
    participant DB as PostgreSQL
    participant R as Redis (Cache)

    C->>G: POST /api/v1/transfers (Bearer JWT, Idempotency-Key)
    G->>F: Forward (+ X-Request-Id)
    F->>F: Validate signature, expiry, issuer
    F->>F: Map roles to authorities, check @PreAuthorize
    alt Invalid or missing token
        F-->>C: 401 problem+json
    end
    F->>TC: Authenticated request
    TC->>TC: Bean Validation on TransferRequest
    TC->>TS: transfer(command, principal)
    TS->>DB: SELECT transaction by (user, idempotency_key)
    alt Key already used
        DB-->>TS: existing transaction
        TS-->>C: 200 original result (replay)
    end
    TS->>L: Acquire lock(minAccountId) then lock(maxAccountId)
    alt Lock not acquired in time
        L-->>TS: failure
        TS-->>C: 409 problem+json (retry later)
    end
    TS->>DB: BEGIN
    TS->>DB: INSERT transaction (PENDING -> PROCESSING)
    TS->>DB: Load both accounts, verify owner, status, currency, funds
    TS->>DB: UPDATE balances (optimistic version check)
    TS->>DB: INSERT ledger entries (DEBIT + CREDIT)
    TS->>DB: UPDATE transaction (COMPLETED)
    TS->>DB: COMMIT
    TS->>R: Evict balance and account caches
    TS->>L: Release both locks
    TS-->>TC: TransactionResponse
    TC-->>C: 201 Created
```

### 2.3 Infrastructure Architecture

```mermaid
flowchart TB
    internet([Internet])

    subgraph PUB["Public Subnet / DMZ"]
        lb["Load Balancer<br/>TLS termination"]
        gw["API Gateway<br/>rate limit, WAF rules"]
    end

    subgraph APP["Private App Subnet"]
        direction LR
        app1["LedgerFlow<br/>instance 1"]
        app2["LedgerFlow<br/>instance 2"]
        appN["LedgerFlow<br/>instance N"]
    end

    subgraph CACHE["Private Cache Subnet"]
        rprimary[("Redis Primary")]
        rreplica[("Redis Replica")]
        rprimary -. replication .-> rreplica
    end

    subgraph DATA["Private Data Subnet (no internet route)"]
        pgbouncer["PgBouncer<br/>connection pooler"]
        pgprimary[("PostgreSQL Primary<br/>writes")]
        pgreplica1[("PostgreSQL Replica 1<br/>reads / reporting")]
        pgreplica2[("PostgreSQL Replica 2<br/>reads / reporting")]
        pgbouncer --> pgprimary
        pgprimary -. streaming replication .-> pgreplica1
        pgprimary -. streaming replication .-> pgreplica2
    end

    subgraph OPS["Operations Subnet"]
        prom["Prometheus"]
        graf["Grafana"]
        backup[("Backups / WAL archive")]
        prom --> graf
    end

    internet --> lb --> gw
    gw --> app1 & app2 & appN
    app1 & app2 & appN --> rprimary
    app1 & app2 & appN --> pgbouncer
    pgprimary -. WAL archive .-> backup
    prom -. scrape .-> app1 & app2 & appN
```

**Network rules**
- Only the load balancer is internet-facing. App, cache and data subnets have no public IPs.
- Security groups allow: gateway to app on 8080, app to Redis on 6379, app to PgBouncer on 5432, and Prometheus to app on the management port.
- The service itself uses a single JDBC datasource that points at the primary through the pooler. Replicas serve reporting and analytics, and failover is handled at the infrastructure layer (Patroni or a managed service).
- Redis is a cache and lock store only. Losing it degrades performance and fails transfers closed (see section 4), but it never loses money data.

---

## 3. Low-Level Design

### 3.1 Entity Relationship Diagram

```mermaid
erDiagram
    USERS ||--o{ USER_ROLES : "has"
    USERS ||--o{ ACCOUNTS : "owns"
    USERS ||--o{ TRANSACTIONS : "initiates"
    ACCOUNTS ||--o{ TRANSACTIONS : "source of"
    ACCOUNTS ||--o{ TRANSACTIONS : "destination of"
    ACCOUNTS ||--o{ LEDGER_ENTRIES : "posted to"
    TRANSACTIONS ||--|{ LEDGER_ENTRIES : "produces"
    TRANSACTIONS |o--o| TRANSACTIONS : "reversed by"

    USERS {
        uuid id PK
        varchar_254 email UK "lowercase, unique"
        varchar_100 password_hash "BCrypt"
        varchar_120 full_name
        boolean enabled "default true"
        timestamptz created_at
        timestamptz updated_at
        bigint version "optimistic lock"
    }

    USER_ROLES {
        uuid user_id PK, FK "-> users.id, ON DELETE CASCADE"
        varchar_30 role PK "ROLE_CUSTOMER | ROLE_TELLER | ROLE_ADMIN"
    }

    ACCOUNTS {
        uuid id PK
        varchar_20 account_number UK "unique, immutable"
        uuid owner_id FK "-> users.id"
        char_3 currency "ISO 4217"
        numeric_19_4 balance "CHECK balance >= 0"
        varchar_20 status "ACTIVE | FROZEN | CLOSED"
        timestamptz created_at
        timestamptz updated_at
        bigint version "optimistic lock"
    }

    TRANSACTIONS {
        uuid id PK
        varchar_36 reference UK "public reference"
        varchar_64 idempotency_key "UK with initiated_by"
        uuid initiated_by FK "-> users.id"
        uuid source_account_id FK "-> accounts.id"
        uuid destination_account_id FK "-> accounts.id"
        numeric_19_4 amount "CHECK amount > 0"
        char_3 currency
        varchar_20 type "TRANSFER | REVERSAL"
        varchar_20 status "PENDING | PROCESSING | COMPLETED | FAILED | REVERSED"
        varchar_255 description
        varchar_255 failure_reason
        uuid reversal_of FK "-> transactions.id, nullable"
        timestamptz created_at
        timestamptz updated_at
        timestamptz completed_at
        bigint version "optimistic lock"
    }

    LEDGER_ENTRIES {
        uuid id PK
        uuid transaction_id FK "-> transactions.id"
        uuid account_id FK "-> accounts.id"
        varchar_10 entry_type "DEBIT | CREDIT"
        numeric_19_4 amount "CHECK amount > 0"
        numeric_19_4 balance_after
        timestamptz created_at "append-only, no updates"
    }
```

**Constraints beyond the diagram**
- `accounts`: `CHECK (balance >= 0)`, `CHECK (status IN ('ACTIVE','FROZEN','CLOSED'))`, `CHECK (currency = upper(currency))`.
- `transactions`: `CHECK (amount > 0)`, `CHECK (source_account_id <> destination_account_id)`, `UNIQUE (initiated_by, idempotency_key)`, and a status `CHECK` matching the state machine.
- `ledger_entries`: a trigger rejects `UPDATE` and `DELETE`, so history cannot be rewritten even by a bug.
- Foreign keys use `ON DELETE RESTRICT`, except `user_roles`, which uses `CASCADE`.

### 3.2 Class & Layered Architecture Diagram

```mermaid
classDiagram
    direction LR

    namespace security {
        class SecurityConfig {
            +SecurityFilterChain filterChain()
            +JwtEncoder jwtEncoder()
            +JwtDecoder jwtDecoder()
            +JwtAuthenticationConverter jwtAuthConverter()
            +PasswordEncoder passwordEncoder()
        }
        class TokenService {
            +String issueAccessToken(UserPrincipal)
        }
        class AppUserDetailsService {
            +UserDetails loadUserByUsername(String)
        }
        class RequestIdFilter {
            +doFilterInternal()
        }
    }

    namespace controller {
        class AuthController {
            +register(RegisterRequest)
            +login(LoginRequest)
        }
        class AccountController {
            +open(OpenAccountRequest)
            +get(UUID)
            +listMine(Pageable)
            +updateStatus(UUID, UpdateStatusRequest)
        }
        class TransactionController {
            +transfer(TransferRequest, idempotencyKey)
            +get(UUID)
            +history(UUID, Pageable)
            +reverse(UUID)
        }
    }

    namespace service {
        class AuthService {
            +register(RegisterRequest) UserResponse
            +login(LoginRequest) TokenResponse
        }
        class AccountService {
            +open(OpenAccountRequest, UserPrincipal) AccountResponse
            +get(UUID, UserPrincipal) AccountResponse
            +listMine(UserPrincipal, Pageable) Page
            +updateStatus(UUID, AccountStatus) AccountResponse
        }
        class TransactionService {
            +transfer(TransferCommand, UserPrincipal) TransactionResponse
            +reverse(UUID, UserPrincipal) TransactionResponse
            +history(UUID, UserPrincipal, Pageable) Page
        }
        class LedgerService {
            +post(Transaction, Account, Account)
        }
        class DistributedLockService {
            +withLocks(List~String~, Supplier~T~) T
        }
    }

    namespace repository {
        class UserRepository
        class AccountRepository
        class TransactionRepository
        class LedgerEntryRepository
    }

    namespace mapper {
        class UserMapper
        class AccountMapper
        class TransactionMapper
    }

    namespace domain {
        class User
        class Account
        class Transaction
        class LedgerEntry
    }

    namespace exception {
        class GlobalExceptionHandler {
            +handleValidation()
            +handleNotFound()
            +handleBusiness()
            +handleAuth()
            +handleUnexpected()
        }
        class BusinessException
        class ResourceNotFoundException
    }

    SecurityConfig ..> TokenService : configures encoder
    RequestIdFilter ..> SecurityConfig : registered in chain
    AuthController --> AuthService
    AccountController --> AccountService
    TransactionController --> TransactionService
    AuthService --> UserRepository
    AuthService --> TokenService
    AuthService --> UserMapper
    AppUserDetailsService --> UserRepository
    AccountService --> AccountRepository
    AccountService --> AccountMapper
    TransactionService --> TransactionRepository
    TransactionService --> AccountRepository
    TransactionService --> LedgerService
    TransactionService --> DistributedLockService
    TransactionService --> TransactionMapper
    LedgerService --> LedgerEntryRepository
    UserRepository ..> User
    AccountRepository ..> Account
    TransactionRepository ..> Transaction
    LedgerEntryRepository ..> LedgerEntry
    BusinessException <|-- ResourceNotFoundException
    GlobalExceptionHandler ..> BusinessException : translates
```

**Layer rules**
- Controllers never touch repositories or entities.
- Services are the only transactional boundary (`@Transactional`).
- Mappers are the only place where entities become DTOs.
- Exceptions propagate to `GlobalExceptionHandler`. Services never build HTTP responses.

### 3.3 State Machine - Transaction Lifecycle

```mermaid
stateDiagram-v2
    [*] --> PENDING : transfer request accepted

    PENDING --> PROCESSING : locks acquired, validation started
    PENDING --> FAILED : validation error (frozen account, currency mismatch)

    PROCESSING --> COMPLETED : balances updated, ledger posted
    PROCESSING --> FAILED : insufficient funds or conflict

    COMPLETED --> REVERSED : reversal posted (TELLER / ADMIN only)

    FAILED --> [*]
    COMPLETED --> [*] : terminal unless reversed
    REVERSED --> [*]

    note right of PROCESSING
        Runs inside one DB transaction
        while both account locks are held
    end note

    note right of REVERSED
        Creates a new REVERSAL transaction
        linked through reversal_of.
        Original ledger entries are never modified.
    end note
```

| From | To | Trigger | Guard |
|---|---|---|---|
| PENDING | PROCESSING | Locks acquired | Both accounts exist |
| PENDING | FAILED | Validation | Account not ACTIVE, or currency mismatch |
| PROCESSING | COMPLETED | Ledger posted | Balance sufficient, versions match |
| PROCESSING | FAILED | Business or concurrency error | none |
| COMPLETED | REVERSED | Reversal request | Role TELLER/ADMIN, destination can cover the amount |

Any other transition throws `InvalidStateTransitionException` (HTTP 409).

---

## 4. Concurrency, Idempotency & Consistency

1. **Idempotency.** `Idempotency-Key` is required on `POST /transfers`. The service first looks up `(initiated_by, idempotency_key)`. On a hit it returns the stored transaction. If two identical requests race past the lookup, the unique constraint rejects the second insert, and the service catches that and returns the winner's result.
2. **Lock ordering.** For a transfer between A and B, the lock keys are sorted (`min(id)` first), so two opposite transfers (A to B and B to A) can never deadlock.
3. **Lock implementation.** `SET key token NX PX ttl` for acquire, and a Lua compare-and-delete script for release, so a slow holder can never release a lock it no longer owns.
4. **Fail closed.** If Redis is unreachable, the transfer is rejected with 503. Money movement never proceeds without the lock.
5. **Last line of defence.** Locks reduce contention but correctness does not depend on them. `@Version` on accounts and `CHECK (balance >= 0)` in the database both reject any conflicting or overdrawing write.
6. **Atomicity.** Balance updates, ledger rows and the status change commit in one database transaction. Cache eviction happens after commit.
7. **Invariant.** For every completed transaction, the sum of CREDIT entries equals the sum of DEBIT entries, and each account's `balance` equals its latest `balance_after`.

---

## 5. Caching Strategy

| Cache name | Key | TTL | Evicted when |
|---|---|---|---|
| `accounts` | account id | 5 min | Account status change, any transfer touching it |
| `account-balances` | account id | 30 s | Any transfer touching it |
| `user-accounts` | user id | 2 min | Account opened or status changed |

- Cache values are DTOs (JSON serialised), never JPA entities.
- Transfers always read balances from the database under lock. The cache serves read endpoints only.
- A cache failure falls back to the database through a custom `CacheErrorHandler`.

---

## 6. Security Model

| Endpoint group | Access |
|---|---|
| `POST /api/v1/auth/**` | Public |
| `/actuator/health/**`, `/v3/api-docs/**`, `/swagger-ui/**` | Public |
| `/actuator/**` (others) | `ROLE_ADMIN` |
| `POST /api/v1/accounts`, `GET /api/v1/accounts/mine`, `POST /api/v1/transfers` | `ROLE_CUSTOMER`, `ROLE_TELLER`, `ROLE_ADMIN` |
| `GET /api/v1/accounts/{id}`, transaction history | Owner, or `ROLE_TELLER` / `ROLE_ADMIN` |
| `PATCH /api/v1/accounts/{id}/status` | `ROLE_TELLER`, `ROLE_ADMIN` |
| `POST /api/v1/transactions/{id}/reverse` | `ROLE_TELLER`, `ROLE_ADMIN` |

- **Passwords:** BCrypt (strength 12).
- **Tokens:** HS256-signed JWT (Nimbus), 15-minute lifetime, `sub` = user id, `roles` claim, `iss` validated. The signing secret comes from the environment and is never committed.
- **Session policy:** `STATELESS`, CSRF disabled (bearer-token API), and security headers enabled.
- **Ownership checks:** performed in the service layer, in addition to role checks on the endpoint.
- **Errors:** 401 (`AuthenticationEntryPoint`) and 403 (`AccessDeniedHandler`) both return RFC 7807 bodies.

---

## 7. Index Rationale

| Index | Access pattern served |
|---|---|
| `accounts(account_number)` UNIQUE | Lookup by public account number |
| `accounts(owner_id, status)` | "My active accounts" |
| `transactions(initiated_by, idempotency_key)` UNIQUE | Idempotent replay lookup |
| `transactions(reference)` UNIQUE | Lookup by public reference |
| `transactions(source_account_id, created_at DESC)` | Outgoing history, paged |
| `transactions(destination_account_id, created_at DESC)` | Incoming history, paged |
| `transactions(status, created_at)` | Operational monitoring of stuck PENDING/PROCESSING rows |
| `ledger_entries(account_id, created_at DESC)` | Account statement |
| `ledger_entries(transaction_id)` | Ledger rows for one transaction |
| `users(email)` UNIQUE | Login lookup |
