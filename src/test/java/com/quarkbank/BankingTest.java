package com.quarkbank;

import com.quarkbank.api.Requests.CreateTransfer;
import com.quarkbank.application.BankingService;
import com.quarkbank.domain.BankingModels.TransferResult;
import com.quarkbank.shared.BankingException;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.*;
import javax.sql.DataSource;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class BankingTest {
    private static final UUID ALICE = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID BOB = UUID.fromString("10000000-0000-0000-0000-000000000002");
    private static final UUID USD = UUID.fromString("10000000-0000-0000-0000-000000000004");
    @Inject DataSource dataSource;
    @Inject BankingService banking;

    @BeforeEach
    void resetTestDatabase() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            assertEquals("bank_test", connection.getCatalog(), "Tests may reset only bank_test");
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute("TRUNCATE ledger_entries, transfers, journals, accounts");
                try (var fixture = getClass().getResourceAsStream("/fixtures/accounts.sql")) {
                    statement.execute(new String(Objects.requireNonNull(fixture).readAllBytes(), StandardCharsets.UTF_8));
                }
                connection.commit();
            }
        }
    }

    @Test @TestSecurity(user = "alice", roles = "customer")
    void transferCommitsBothBalancesAndBalancedPostings() throws Exception {
        String id = send("first", "125.25").then().statusCode(201).header("Idempotency-Replayed", "false").extract().path("id");
        assertBalance(ALICE, "874.75");
        assertBalance(BOB, "375.25");
        assertEquals(2, scalar("SELECT count(*) FROM ledger_entries WHERE journal_id = '" + id + "'"));
        assertEquals(0, scalar("SELECT sum(signed_amount) FROM ledger_entries WHERE journal_id = '" + id + "'"));
        given().get("/api/transfers/" + id).then().statusCode(200).body("id", equalTo(id));
    }

    @Test @TestSecurity(user = "alice", roles = "customer")
    void retryAfterSpendingEntireBalanceReturnsOriginalTransfer() throws Exception {
        var original = send("retry", "1000.00").then().statusCode(201).extract().jsonPath().getMap("$");
        var retried = send("retry", "1000.00").then().statusCode(200).header("Idempotency-Replayed", "true").extract().jsonPath().getMap("$");
        assertEquals(original, retried);
        assertEquals(1, scalar("SELECT count(*) FROM transfers"));
        assertBalance(ALICE, "0.00");
    }

    @Test @TestSecurity(user = "alice", roles = "customer")
    void sameKeyWithDifferentPayloadIsRejected() {
        send("conflict", "10.00").then().statusCode(201);
        send("conflict", "11.00").then().statusCode(409).body("code", equalTo("IDEMPOTENCY_CONFLICT"));
    }

    @Test @TestSecurity(user = "alice", roles = "customer")
    void insufficientFundsLeavesNoFinancialChangesAndKeyCanBeRetried() throws Exception {
        send("funds", "1000.01").then().statusCode(422).body("code", equalTo("INSUFFICIENT_FUNDS"));
        assertBalance(ALICE, "1000.00");
        assertBalance(BOB, "250.00");
        assertEquals(0, scalar("SELECT count(*) FROM transfers"));
        send("funds", "10.00").then().statusCode(201);
    }

    @Test @TestSecurity(user = "alice", roles = "customer")
    void customerCannotDebitAnotherCustomer() {
        given().contentType("application/json").header("Idempotency-Key", "ownership")
                .body(request(BOB, ALICE, "1.00", "PEN")).post("/api/transfers")
                .then().statusCode(404);
    }

    @Test @TestSecurity(user = "alice", roles = "customer")
    void customerSeesOnlyOwnAccountsAndCannotReadOtherLedger() {
        given().get("/api/accounts").then().statusCode(200).body("size()", equalTo(1)).body("[0].ownerId", equalTo("alice"));
        given().get("/api/accounts/" + BOB).then().statusCode(404);
        given().get("/api/accounts/" + BOB + "/entries").then().statusCode(404);
    }

    @Test
    void anonymousRequestsAreRejected() {
        given().get("/api/accounts").then().statusCode(401);
        send("anonymous", "1.00").then().statusCode(401);
    }

    @Test @TestSecurity(user = "alice", roles = "customer")
    void validationRejectsMissingKeyFractionsZeroAndNullBody() {
        given().contentType("application/json").body(request(ALICE, BOB, "1.00", "PEN")).post("/api/transfers").then().statusCode(400);
        send("fraction", "0.001").then().statusCode(400);
        send("zero", "0.00").then().statusCode(400);
        given().contentType("application/json").header("Idempotency-Key", "null").body("null").post("/api/transfers").then().statusCode(400);
    }

    @Test @TestSecurity(user = "alice", roles = "customer")
    void crossCurrencyTransferIsRejected() {
        given().contentType("application/json").header("Idempotency-Key", "currency")
                .body(request(ALICE, USD, "1.00", "PEN")).post("/api/transfers")
                .then().statusCode(422).body("code", equalTo("CURRENCY_MISMATCH"));
    }

    @Test @TestSecurity(user = "operator", roles = "operator")
    void operatorOpensZeroBalanceAccount() {
        given().contentType("application/json").body(Map.of("ownerId", "new-customer", "currency", "USD"))
                .post("/api/accounts").then().statusCode(201).body("balance", equalTo(0.0f));
        send("operator-debit", "1.00").then().statusCode(403);
    }

    @Test @TestSecurity(user = "alice", roles = "customer")
    void customerCannotOpenAccounts() {
        given().contentType("application/json").body(Map.of("ownerId", "alice", "currency", "PEN"))
                .post("/api/accounts").then().statusCode(403);
    }

    @Test
    void simultaneousWithdrawalsCannotOverdraw() throws Exception {
        List<Integer> outcomes = concurrent(20, index -> {
            try {
                banking.transfer(request(ALICE, BOB, "100.00", "PEN"), "alice", "concurrent-" + index);
                return 201;
            } catch (BankingException exception) {
                assertEquals("INSUFFICIENT_FUNDS", exception.code());
                return exception.status();
            }
        });
        assertEquals(10, outcomes.stream().filter(status -> status == 201).count());
        assertEquals(10, outcomes.stream().filter(status -> status == 422).count());
        assertBalance(ALICE, "0.00");
        assertBalance(BOB, "1250.00");
        assertEquals(10, scalar("SELECT count(*) FROM transfers"));
    }

    @Test
    void simultaneousRetriesPostExactlyOnce() throws Exception {
        List<TransferResult> outcomes = concurrent(12, index -> banking.transfer(request(ALICE, BOB, "100.00", "PEN"), "alice", "same-key"));
        assertEquals(1, outcomes.stream().filter(result -> !result.replayed()).count());
        assertEquals(1, outcomes.stream().map(result -> result.transfer().id()).distinct().count());
        assertBalance(ALICE, "900.00");
        assertBalance(BOB, "350.00");
        assertEquals(1, scalar("SELECT count(*) FROM transfers"));
    }

    @Test
    void oppositeDirectionsDoNotDeadlock() throws Exception {
        concurrent(20, index -> index % 2 == 0
                ? banking.transfer(request(ALICE, BOB, "5.00", "PEN"), "alice", "opposite-" + index)
                : banking.transfer(request(BOB, ALICE, "5.00", "PEN"), "bob", "opposite-" + index));
        assertBalance(ALICE, "1000.00");
        assertBalance(BOB, "250.00");
        assertEquals(20, scalar("SELECT count(*) FROM transfers"));
    }

    @Test
    void ledgerHistoryCannotBeEdited() throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            assertThrows(SQLException.class, () -> statement.executeUpdate("UPDATE ledger_entries SET signed_amount = 7"));
            assertThrows(SQLException.class, () -> statement.executeUpdate("DELETE FROM ledger_entries"));
        }
    }

    @Test
    void databaseRejectsUnbalancedJournalAtCommit() throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.execute("INSERT INTO journals (id, currency, kind) VALUES ('90000000-0000-0000-0000-000000000001', 'PEN', 'OPENING')");
            statement.execute("INSERT INTO ledger_entries (id, journal_id, account_id, signed_amount) VALUES "
                    + "('90000000-0000-0000-0000-000000000002', '90000000-0000-0000-0000-000000000001', '" + ALICE + "', -10),"
                    + "('90000000-0000-0000-0000-000000000003', '90000000-0000-0000-0000-000000000001', '" + BOB + "', 9)");
            SQLException failure = assertThrows(SQLException.class, connection::commit);
            assertEquals("23514", failure.getSQLState());
            connection.rollback();
        }
        assertBalance(ALICE, "1000.00");
    }

    @Test
    void databaseRejectsBalanceWithoutLedgerMovement() throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.execute("UPDATE accounts SET balance = 500 WHERE id = '" + ALICE + "'");
            assertThrows(SQLException.class, connection::commit);
            connection.rollback();
        }
        assertBalance(ALICE, "1000.00");
    }

    @Test
    void databaseRejectsTransferAttachedToOpeningJournal() throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.execute("INSERT INTO transfers (id, principal_id, idempotency_key, source_account_id, destination_account_id, amount, currency) VALUES "
                    + "('20000000-0000-0000-0000-000000000001', 'alice', 'invalid-journal', '" + ALICE + "', '" + BOB + "', 1000, 'PEN')");
            SQLException failure = assertThrows(SQLException.class, connection::commit);
            assertEquals("23514", failure.getSQLState());
            connection.rollback();
        }
        assertEquals(0, scalar("SELECT count(*) FROM transfers"));
    }

    private io.restassured.response.Response send(String key, String amount) {
        return given().contentType("application/json").header("Idempotency-Key", key)
                .body(request(ALICE, BOB, amount, "PEN")).post("/api/transfers");
    }

    private CreateTransfer request(UUID source, UUID destination, String amount, String currency) {
        return new CreateTransfer(source, destination, new BigDecimal(amount), currency);
    }

    private void assertBalance(UUID id, String expected) {
        assertEquals(0, banking.account(id, "operator", true).balance().compareTo(new BigDecimal(expected)));
    }

    private long scalar(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getLong(1);
        }
    }

    private <T> List<T> concurrent(int count, java.util.function.IntFunction<T> operation) throws Exception {
        try (ExecutorService executor = Executors.newFixedThreadPool(count)) {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (int index = 0; index < count; index++) {
                final int task = index;
                futures.add(executor.submit(() -> { start.await(); return operation.apply(task); }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) results.add(future.get(20, TimeUnit.SECONDS));
            return results;
        }
    }
}
