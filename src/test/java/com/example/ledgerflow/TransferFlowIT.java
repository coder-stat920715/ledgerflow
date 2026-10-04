package com.example.ledgerflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

@Disabled
class TransferFlowIT extends IntegrationTestBase {

    @Test
    void transferMovesMoneyPostsLedgerAndReplaysIdempotently() throws Exception {
        TestUser alice = registerAndLogin();
        TestUser bob = registerAndLogin();
        UUID from = openAccount(alice, "USD", "100.00");
        UUID to = openAccount(bob, "USD", "0");
        String key = newKey();

        MvcResult first = transfer(alice, from, to, "25.00", key);
        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        assertThat(json(first).get("status").asText()).isEqualTo("COMPLETED");

        MvcResult replay = transfer(alice, from, to, "25.00", key);
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getHeader("Idempotent-Replayed")).isEqualTo("true");
        assertThat(json(replay).get("id").asText()).isEqualTo(json(first).get("id").asText());

        assertThat(balanceOf(from)).isEqualByComparingTo("75");
        assertThat(balanceOf(to)).isEqualByComparingTo("25");

        Integer ledgerRows = jdbc.queryForObject(
                "SELECT count(*) FROM ledger_entries WHERE transaction_id = ?", Integer.class,
                UUID.fromString(json(first).get("id").asText()));
        assertThat(ledgerRows).isEqualTo(2);

        mockMvc.perform(get("/api/v1/accounts/{id}/balance", from)
                        .header("Authorization", "Bearer " + alice.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(75.0));
    }

    @Test
    void insufficientFundsReturns422AndKeepsAFailedRecord() throws Exception {
        TestUser alice = registerAndLogin();
        TestUser bob = registerAndLogin();
        UUID from = openAccount(alice, "USD", "10.00");
        UUID to = openAccount(bob, "USD", "0");
        String key = newKey();

        MvcResult result = transfer(alice, from, to, "25.00", key);

        assertThat(result.getResponse().getStatus()).isEqualTo(422);
        assertThat(result.getResponse().getContentType()).contains("application/problem+json");
        assertThat(json(result).get("code").asText()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(balanceOf(from)).isEqualByComparingTo("10");
        assertThat(balanceOf(to)).isEqualByComparingTo("0");

        String status = jdbc.queryForObject(
                "SELECT status FROM transactions WHERE idempotency_key = ?", String.class, key);
        assertThat(status).isEqualTo("FAILED");

        MvcResult replay = transfer(alice, from, to, "25.00", key);
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(replay).get("status").asText()).isEqualTo("FAILED");
    }

    @Test
    void sameKeyWithDifferentPayloadIsRejected() throws Exception {
        TestUser alice = registerAndLogin();
        TestUser bob = registerAndLogin();
        UUID from = openAccount(alice, "USD", "100.00");
        UUID to = openAccount(bob, "USD", "0");
        String key = newKey();

        assertThat(transfer(alice, from, to, "5.00", key).getResponse().getStatus()).isEqualTo(201);
        MvcResult reuse = transfer(alice, from, to, "6.00", key);

        assertThat(reuse.getResponse().getStatus()).isEqualTo(422);
        assertThat(json(reuse).get("code").asText()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(balanceOf(from)).isEqualByComparingTo("95");
    }

    @Test
    void concurrentTransfersNeverOverdrawOrLoseMoney() throws Exception {
        TestUser alice = registerAndLogin();
        TestUser bob = registerAndLogin();
        UUID from = openAccount(alice, "USD", "50.00");
        UUID to = openAccount(bob, "USD", "0");

        int attempts = 10;
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < attempts; i++) {
                Callable<Integer> call = () -> transfer(alice, from, to, "10.00", newKey())
                        .getResponse().getStatus();
                futures.add(pool.submit(call));
            }
            int succeeded = 0;
            for (Future<Integer> future : futures) {
                if (future.get() == 201) {
                    succeeded++;
                }
            }

            assertThat(succeeded).isBetween(1, 5);
            BigDecimal moved = new BigDecimal(succeeded * 10);
            assertThat(balanceOf(from)).isEqualByComparingTo(new BigDecimal("50").subtract(moved));
            assertThat(balanceOf(to)).isEqualByComparingTo(moved);
            assertThat(balanceOf(from).signum()).isGreaterThanOrEqualTo(0);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void customerCannotSpendFromOrReadSomeoneElsesAccount() throws Exception {
        TestUser alice = registerAndLogin();
        TestUser bob = registerAndLogin();
        UUID aliceAccount = openAccount(alice, "USD", "100.00");
        UUID bobAccount = openAccount(bob, "USD", "100.00");

        MvcResult theft = transfer(bob, aliceAccount, bobAccount, "10.00", newKey());
        assertThat(theft.getResponse().getStatus()).isEqualTo(404);
        assertThat(balanceOf(aliceAccount)).isEqualByComparingTo("100");

        mockMvc.perform(get("/api/v1/accounts/{id}", aliceAccount)
                        .header("Authorization", "Bearer " + bob.token()))
                .andExpect(status().isNotFound());
    }

    @Test
    void tellerReversalRestoresBalancesExactlyOnce() throws Exception {
        TestUser alice = registerAndLogin();
        TestUser bob = registerAndLogin();
        TestUser teller = registerAndLogin();
        jdbc.update("INSERT INTO user_roles (user_id, role) VALUES (?, 'ROLE_TELLER')", teller.id());
        String tellerToken = login(teller.email());

        UUID from = openAccount(alice, "USD", "100.00");
        UUID to = openAccount(bob, "USD", "0");
        String transactionId = json(transfer(alice, from, to, "40.00", newKey())).get("id").asText();

        mockMvc.perform(post("/api/v1/transactions/{id}/reverse", transactionId)
                        .header("Authorization", "Bearer " + alice.token()))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/transactions/{id}/reverse", transactionId)
                        .header("Authorization", "Bearer " + tellerToken))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("REVERSAL"))
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        assertThat(statusOf(transactionId)).isEqualTo("REVERSED");
        assertThat(balanceOf(from)).isEqualByComparingTo("100");
        assertThat(balanceOf(to)).isEqualByComparingTo("0");

        mockMvc.perform(post("/api/v1/transactions/{id}/reverse", transactionId)
                        .header("Authorization", "Bearer " + tellerToken))
                .andExpect(status().isConflict());
        assertThat(balanceOf(from)).isEqualByComparingTo("100");
    }

    @Test
    void protectedEndpointsRejectMissingTokensWithProblemJson() throws Exception {
        mockMvc.perform(get("/api/v1/accounts/mine"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void transferWithoutIdempotencyKeyIsABadRequest() throws Exception {
        TestUser alice = registerAndLogin();
        UUID from = openAccount(alice, "USD", "10.00");
        UUID to = openAccount(registerAndLogin(), "USD", "0");

        mockMvc.perform(post("/api/v1/transfers")
                        .header("Authorization", "Bearer " + alice.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "sourceAccountId", from, "destinationAccountId", to,
                                "amount", "1.00", "currency", "USD"))))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void invalidBodyReturnsFieldErrors() throws Exception {
        TestUser alice = registerAndLogin();

        mockMvc.perform(post("/api/v1/accounts")
                        .header("Authorization", "Bearer " + alice.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currency\":\"usd\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("currency"));
    }

    @Test
    void wrongPasswordIsAGeneric401() throws Exception {
        TestUser alice = registerAndLogin();

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", alice.email(), "password", "wrong-password-1"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }
}
