package com.example.ledgerflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

@Disabled
class LedgerIntegrityIT extends IntegrationTestBase {

    @Test
    void ledgerRowsCannotBeUpdatedDeletedOrTruncated() throws Exception {
        TestUser alice = registerAndLogin();
        UUID from = openAccount(alice, "USD", "20.00");
        UUID to = openAccount(registerAndLogin(), "USD", "0");
        assertThat(transfer(alice, from, to, "5.00", newKey()).getResponse().getStatus()).isEqualTo(201);

        assertThatThrownBy(() -> jdbc.update("UPDATE ledger_entries SET amount = amount + 1"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM ledger_entries"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.execute("TRUNCATE TABLE ledger_entries"))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void debitsEqualCreditsAndBalancesMatchLatestLedgerEntry() throws Exception {
        TestUser alice = registerAndLogin();
        UUID from = openAccount(alice, "USD", "100.00");
        UUID to = openAccount(registerAndLogin(), "USD", "0");
        for (int i = 0; i < 3; i++) {
            assertThat(transfer(alice, from, to, "7.50", newKey()).getResponse().getStatus()).isEqualTo(201);
        }

        BigDecimal debits = jdbc.queryForObject(
                "SELECT COALESCE(SUM(amount), 0) FROM ledger_entries WHERE entry_type = 'DEBIT'", BigDecimal.class);
        BigDecimal credits = jdbc.queryForObject(
                "SELECT COALESCE(SUM(amount), 0) FROM ledger_entries WHERE entry_type = 'CREDIT'", BigDecimal.class);
        assertThat(debits).isEqualByComparingTo(credits);

        BigDecimal latestAfter = jdbc.queryForObject(
                "SELECT balance_after FROM ledger_entries WHERE account_id = ? ORDER BY created_at DESC, id DESC LIMIT 1",
                BigDecimal.class, from);
        assertThat(latestAfter).isEqualByComparingTo(balanceOf(from));
        assertThat(balanceOf(from)).isEqualByComparingTo("77.5");
    }
}
