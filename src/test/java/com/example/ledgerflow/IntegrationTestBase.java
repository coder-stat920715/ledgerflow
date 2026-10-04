package com.example.ledgerflow;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Shared real PostgreSQL and Redis (one pair per JVM). Helpers drive the public HTTP API;
 * the only direct SQL is funding accounts, because the service has no deposit endpoint.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class IntegrationTestBase {

    protected static final String PASSWORD = "Passw0rd-12345";

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    static {
        POSTGRES.start();
        REDIS.start();
    }

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected JdbcTemplate jdbc;

    protected record TestUser(UUID id, String email, String token) {
    }

    protected TestUser registerAndLogin() throws Exception {
        String email = "user-" + UUID.randomUUID() + "@example.com";
        MvcResult registered = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", email, "password", PASSWORD, "fullName", "Test User"))))
                .andExpect(status().isCreated())
                .andReturn();
        UUID id = UUID.fromString(json(registered).get("id").asText());
        return new TestUser(id, email, login(email));
    }

    protected String login(String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", email, "password", PASSWORD))))
                .andExpect(status().isOk())
                .andReturn();
        return json(result).get("accessToken").asText();
    }

    protected UUID openAccount(TestUser user, String currency, String funding) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/accounts")
                        .header("Authorization", "Bearer " + user.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("currency", currency))))
                .andExpect(status().isCreated())
                .andReturn();
        UUID id = UUID.fromString(json(result).get("id").asText());
        if (new BigDecimal(funding).signum() > 0) {
            jdbc.update("UPDATE accounts SET balance = ? WHERE id = ?", new BigDecimal(funding), id);
        }
        return id;
    }

    protected MvcResult transfer(TestUser user, UUID from, UUID to, String amount, String idempotencyKey)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sourceAccountId", from);
        body.put("destinationAccountId", to);
        body.put("amount", new BigDecimal(amount));
        body.put("currency", "USD");
        body.put("description", "integration test");
        return mockMvc.perform(post("/api/v1/transfers")
                        .header("Authorization", "Bearer " + user.token())
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    protected BigDecimal balanceOf(UUID accountId) {
        return jdbc.queryForObject("SELECT balance FROM accounts WHERE id = ?", BigDecimal.class, accountId);
    }

    protected String statusOf(String transactionId) {
        return jdbc.queryForObject("SELECT status FROM transactions WHERE id = ?", String.class,
                UUID.fromString(transactionId));
    }

    protected JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    protected static String newKey() {
        return "key-" + UUID.randomUUID();
    }
}
