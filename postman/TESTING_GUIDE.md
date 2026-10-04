# LedgerFlow - Postman Testing Guide

`LedgerFlow.postman_collection.json` contains 57 requests in 8 folders. Every request has automated assertions,
so you can run the whole thing in the Collection Runner and read a pass/fail report.

## 1. Prerequisites

1. Start the stack: `cp .env.example .env`, then `docker compose up --build`.
2. Wait until `http://localhost:8080/actuator/health` returns `{"status":"UP"}`.
3. The bootstrap admin is created at startup. With `.env.example` its login is
   `admin@ledgerflow.local` / `change-me-admin-password`. If you changed `BOOTSTRAP_ADMIN_*` in `.env`,
   update the `adminEmail` / `adminPassword` collection variables to match.
4. Postman desktop or web (v10+). `postman.com` web needs the Desktop Agent to reach `localhost`.

## 2. Import and configure

1. Postman -> **Import** -> select `LedgerFlow.postman_collection.json`.
2. Open the collection -> **Variables** tab. Check these:

| Variable | Default | Change when |
|---|---|---|
| `baseUrl` | `http://localhost:8080` | app runs elsewhere |
| `password` | `Passw0rd-12345` | never (meets the password policy) |
| `adminEmail` / `adminPassword` | `admin@ledgerflow.local` / `change-me-admin-password` | you changed the bootstrap admin |
| `runId` | empty | leave empty. A pre-request script fills it with a timestamp so user emails are unique on every run |

All other variables (tokens, account ids, transaction ids, idempotency keys) are filled in automatically.

## 3. Run order (important)

The folders depend on each other, so run them top to bottom.

1. Run folders **0, 1, 2** (right-click the folder -> **Run folder**, or run the whole collection and
   pause at folder 3).
2. **Do the manual funding step** (section 4). The service has no deposit endpoint, so a test account has to be funded in the database.
3. Run folders **3, 4, 5, 6, 7**.

Simplest way: **Collection Runner** -> select the collection -> run folders 0-2 -> fund -> run 3-7.
Keep "Persist variables" ON so ids and tokens carry over between runs.

## 4. Manual funding step (folder 3)

1. Send **3.1 Show funding SQL**. Open the **Visualize** tab of the response (or View -> Show Postman Console). It prints:
   ```sql
   UPDATE accounts SET balance = 100.00 WHERE id = '<Alice USD account id>';
   ```
2. Run it with either:
   ```bash
   docker compose exec postgres psql -U ledgerflow -d ledgerflow \
     -c "UPDATE accounts SET balance = 100.00 WHERE id = '<id from Postman>';"
   ```
   or in Adminer (`http://localhost:8081`: System PostgreSQL, Server `postgres`, user/db `ledgerflow`,
   the password from `.env`) -> SQL command.
3. Send **3.2 Verify Alice USD balance is 100.00**. It must pass before continuing.
   Balances are cached for 30 seconds. If you called a balance endpoint before funding, wait 30 s.

## 5. What each folder checks, and the expected result

### Folder 0 - Smoke
| Request | Expect |
|---|---|
| 0.1 Health | 200, `status: UP` |

### Folder 1 - Authentication
| Request | Expect |
|---|---|
| 1.1 / 1.2 Register Alice, Bob | 201, role `ROLE_CUSTOMER`, ids saved |
| 1.3 Duplicate email | 409 `EMAIL_ALREADY_REGISTERED` |
| 1.4 Weak password | 400 `VALIDATION_FAILED`, `errors[]` has `password` |
| 1.5 / 1.6 / 1.7 Login Alice, Bob, Admin | 200, 3-part JWT, `expiresInSeconds` 900, tokens saved. Admin token contains `ROLE_ADMIN` |
| 1.8 Wrong password | 401 `UNAUTHORIZED`, generic message |

### Folder 2 - Accounts
| Request | Expect |
|---|---|
| 2.1-2.3 Open USD (Alice), USD (Bob), EUR (Alice) | 201, balance 0, status ACTIVE, number `LF...`, `Location` header, ids saved |
| 2.4 Lowercase currency | 400, field `currency` |
| 2.5 Currency `ZZZ` | 400 `INVALID_CURRENCY` |
| 2.6 List mine | 200, exactly 2 accounts (USD, EUR) |
| 2.7 Alice reads own account | 200 |
| 2.8 Bob reads Alice's account | 404 (not 403, so ids can't be probed) |
| 2.9 Admin reads Alice's account | 200 |
| 2.10 No token | 401 with `WWW-Authenticate: Bearer` |

### Folder 3 - Funding (manual)
See section 4.

### Folder 4 - Transfers and idempotency
Starting balances: Alice 100.00, Bob 0.00.

| Request | Expect |
|---|---|
| 4.1 Transfer 25.00 | **201**, `COMPLETED`, `Location` header |
| 4.2 Same request again | **200**, header `Idempotent-Replayed: true`, same transaction id |
| 4.3 Same key, amount 26.00 | 422 `IDEMPOTENCY_KEY_REUSED` |
| 4.4a / 4.4b Balances | Alice **75.00**, Bob **25.00** (proves no double debit) |
| 4.5 Transfer 500.00 | 422 `INSUFFICIENT_FUNDS`. A `FAILED` row is stored, balances unchanged |
| 4.6 Replay of 4.5 | 200, status `FAILED`, replay header (a failed key is final) |
| 4.7 Currency EUR between USD accounts | 422 `CURRENCY_MISMATCH` |
| 4.8 Same source and destination | 400 `SAME_ACCOUNT` |
| 4.9 Missing `Idempotency-Key` | 400 problem+json |
| 4.10 Key `short` | 400 `VALIDATION_FAILED` (needs 8-64 chars) |
| 4.11 Key `REVERSAL:abcdef12` | 400 `INVALID_IDEMPOTENCY_KEY` |
| 4.12 Amount 0 | 400, `errors[]` has `amount` |
| 4.13 Bob spends from Alice's account | 404 |
| 4.14a / 4.14b Read transaction | 200 for Alice (initiator) and Bob (destination owner) |
| 4.15 Alice's history | 200, newest first, at least 3 rows including `FAILED` ones |

### Folder 5 - Staff operations
| Request | Expect |
|---|---|
| 5.1 Customer freezes account | 403 `FORBIDDEN` |
| 5.2 Admin freezes Bob's account | 200 `FROZEN` |
| 5.3 Transfer into frozen account | 422 `ACCOUNT_NOT_ACTIVE` |
| 5.4 Admin unfreezes | 200 `ACTIVE` |
| 5.5 Customer reverses | 403 |
| 5.6 Admin reverses transfer 4.1 | **201**, type `REVERSAL`, `reversalOfId` = original, money flows Bob -> Alice |
| 5.7 Reverse again | 409 `INVALID_STATE_TRANSITION` |
| 5.8 Original transaction | status `REVERSED` |
| 5.9a / 5.9b Balances | Alice **100.00**, Bob **0.00** |
| 5.10 Alice's ledger | 2 entries, newest first: CREDIT balance_after 100, DEBIT balance_after 75 |

### Folder 6 - Closing rules
| Request | Expect |
|---|---|
| 6.1 Close account with balance | 409 `ACCOUNT_NOT_EMPTY` |
| 6.2 Close empty EUR account | 200 `CLOSED` |
| 6.3 Re-open closed account | 409 `ACCOUNT_CLOSED` |

### Folder 7 - Security
| Request | Expect |
|---|---|
| 7.1 Tampered token | 401 |
| 7.2 Customer reads `/actuator/prometheus` | 403 |
| 7.3 Admin reads `/actuator/prometheus` | 200, Prometheus text |
| 7.4 Bob reads Alice's ledger | 404 |
| 7.5 Unknown route | 404 problem+json |

**A fully green run is 57 requests with all tests passing.**

## 6. Verifying the database directly (optional)

```bash
docker compose exec postgres psql -U ledgerflow -d ledgerflow
```
```sql
-- debits must equal credits
SELECT entry_type, SUM(amount) FROM ledger_entries GROUP BY entry_type;

-- the ledger is append-only: both of these must fail
UPDATE ledger_entries SET amount = amount + 1;
DELETE FROM ledger_entries;

-- statuses after the full run
SELECT type, status, amount FROM transactions ORDER BY created_at;
```
After a full run you should see: the 25.00 TRANSFER as `REVERSED`, its REVERSAL as `COMPLETED`, and
`FAILED` rows for the insufficient-funds, currency-mismatch and frozen-account attempts.

## 7. Concurrency test (cannot be done in Postman)

The Collection Runner is sequential, so it cannot test simultaneous requests. Use this shell script instead.
It fires 10 parallel 10.00 transfers from an account that holds only 50.00. Exactly 5 or fewer may succeed,
the balance must never go negative, and money must be conserved.

```bash
# 1. Set values from Postman (collection variables): aliceToken, aliceUsdId, bobUsdId
TOKEN="<aliceToken>"; FROM="<aliceUsdId>"; TO="<bobUsdId>"; BASE=http://localhost:8080

# 2. Make sure Alice's account holds exactly 50.00:
#    UPDATE accounts SET balance = 50.00 WHERE id = '<aliceUsdId>';   (and Bob's = 0)

# 3. Fire 10 transfers in parallel
seq 10 | xargs -P 10 -I{} curl -s -o /dev/null -w "%{http_code}\n" \
  -X POST "$BASE/api/v1/transfers" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Idempotency-Key: race-$(date +%s%N)-{}" \
  -H "Content-Type: application/json" \
  -d "{\"sourceAccountId\":\"$FROM\",\"destinationAccountId\":\"$TO\",\"amount\":10.00,\"currency\":\"USD\"}" \
  | sort | uniq -c
```
Expected output: some `201` (at most 5), the rest `422` (insufficient funds) and/or `409` (lock busy, retry).
Then check `SELECT id, balance FROM accounts WHERE id IN ('<from>','<to>');` - the two balances must sum to 50.00 and neither may be negative.

The same-key variant tests idempotency under a race: send 10 parallel requests with an identical
`Idempotency-Key`. Exactly one is created (201); the others return 200 with the same transaction id (or 409 if the lock was busy).

## 8. Troubleshooting

| Symptom | Cause / fix |
|---|---|
| 1.7 fails with 401 | Admin credentials differ. Update `adminEmail` / `adminPassword` variables, and check the app log for `Created bootstrap admin` |
| 3.2 fails | The funding SQL has not been run, or run against the wrong id. Re-run 3.1 and copy the id it prints |
| 4.4a shows 100 instead of 75 | Stale 30 s balance cache from an earlier read. It is evicted on every transfer, so check that Redis is healthy (`/actuator/health`) |
| Many tests fail with 401 | Token expired (15 min lifetime). Re-run folder 1 (logins 1.5-1.7) |
| 4.x responds 503 | Redis is down. Transfers fail closed without the distributed lock |
| Anything fails on a re-run | Run again from folder 1. `runId` stays set between runs, so clear the `runId` variable to create fresh users, and re-fund the new Alice account |
| 7.3 fails | Prometheus endpoint is exposed only when `management.endpoints.web.exposure` includes it (it does by default) |

## 9. Re-running from scratch

1. Clear the `runId` collection variable (new users and accounts are created on the next run).
2. Run folders 0-2, fund the new Alice USD account (folder 3), run folders 3-7.
