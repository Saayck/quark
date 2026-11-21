package com.quarkbank.infrastructure;

import com.quarkbank.domain.BankingModels.*;
import jakarta.enterprise.context.ApplicationScoped;
import java.math.BigDecimal;
import java.sql.*;
import java.time.Instant;
import java.util.*;

@ApplicationScoped
public class BankRepository {
    public List<Account> accounts(Connection connection, String ownerId) throws SQLException {
        String sql = "SELECT * FROM accounts WHERE kind = 'CUSTOMER'" + (ownerId == null ? "" : " AND owner_id = ?") + " ORDER BY id LIMIT 100";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            if (ownerId != null) statement.setString(1, ownerId);
            try (ResultSet rows = statement.executeQuery()) {
                List<Account> result = new ArrayList<>();
                while (rows.next()) result.add(account(rows));
                return result;
            }
        }
    }

    public Optional<Account> account(Connection connection, UUID id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM accounts WHERE id = ?")) {
            statement.setObject(1, id);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(account(rows)) : Optional.empty();
            }
        }
    }

    // PostgreSQL locks rows in the same UUID order even for transfers in opposite directions.
    public Map<UUID, Account> lockAccounts(Connection connection, UUID source, UUID destination) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM accounts WHERE id IN (?, ?) ORDER BY id FOR UPDATE")) {
            statement.setObject(1, source);
            statement.setObject(2, destination);
            try (ResultSet rows = statement.executeQuery()) {
                Map<UUID, Account> result = new HashMap<>();
                while (rows.next()) {
                    Account account = account(rows);
                    result.put(account.id(), account);
                }
                return result;
            }
        }
    }

    public void openAccount(Connection connection, Account account) throws SQLException {
        execute(connection, "INSERT INTO accounts (id, owner_id, currency, kind, balance, created_at) VALUES (?, ?, ?, 'CUSTOMER', 0, ?)",
                account.id(), account.ownerId(), account.currency(), Timestamp.from(account.createdAt()));
    }

    public Optional<Transfer> transfer(Connection connection, UUID id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM transfers WHERE id = ?")) {
            statement.setObject(1, id);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(transfer(rows)) : Optional.empty();
            }
        }
    }

    public Optional<Transfer> transferByKey(Connection connection, String principal, String key) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM transfers WHERE principal_id = ? AND idempotency_key = ?")) {
            statement.setString(1, principal);
            statement.setString(2, key);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(transfer(rows)) : Optional.empty();
            }
        }
    }

    public void journal(Connection connection, UUID id, String currency, Instant createdAt) throws SQLException {
        execute(connection, "INSERT INTO journals (id, currency, kind, created_at) VALUES (?, ?, 'TRANSFER', ?)", id, currency, Timestamp.from(createdAt));
    }

    public boolean reserveTransfer(Connection connection, Transfer transfer) throws SQLException {
        return execute(connection, """
                INSERT INTO transfers (id, principal_id, idempotency_key, source_account_id, destination_account_id, amount, currency, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (principal_id, idempotency_key) DO NOTHING
                """, transfer.id(), transfer.principalId(), transfer.idempotencyKey(), transfer.sourceAccountId(), transfer.destinationAccountId(),
                transfer.amount(), transfer.currency(), Timestamp.from(transfer.createdAt())) == 1;
    }

    public void balance(Connection connection, UUID id, BigDecimal balance) throws SQLException {
        execute(connection, "UPDATE accounts SET balance = ? WHERE id = ?", balance, id);
    }

    public void post(Connection connection, UUID journalId, UUID accountId, BigDecimal signedAmount, Instant at) throws SQLException {
        execute(connection, "INSERT INTO ledger_entries (id, journal_id, account_id, signed_amount, created_at) VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID(), journalId, accountId, signedAmount, Timestamp.from(at));
    }

    public List<Posting> postings(Connection connection, UUID accountId, int limit, int offset) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM ledger_entries WHERE account_id = ? ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?")) {
            statement.setObject(1, accountId);
            statement.setInt(2, limit);
            statement.setInt(3, offset);
            try (ResultSet rows = statement.executeQuery()) {
                List<Posting> result = new ArrayList<>();
                while (rows.next()) result.add(new Posting(rows.getObject("id", UUID.class), rows.getObject("journal_id", UUID.class),
                        rows.getObject("account_id", UUID.class), rows.getBigDecimal("signed_amount"), rows.getTimestamp("created_at").toInstant()));
                return result;
            }
        }
    }

    private Account account(ResultSet rows) throws SQLException {
        return new Account(rows.getObject("id", UUID.class), rows.getString("owner_id"), rows.getString("currency"),
                rows.getString("kind"), rows.getBigDecimal("balance"), rows.getTimestamp("created_at").toInstant());
    }

    private Transfer transfer(ResultSet rows) throws SQLException {
        return new Transfer(rows.getObject("id", UUID.class), rows.getString("principal_id"), rows.getString("idempotency_key"),
                rows.getObject("source_account_id", UUID.class), rows.getObject("destination_account_id", UUID.class),
                rows.getBigDecimal("amount"), rows.getString("currency"), rows.getTimestamp("created_at").toInstant());
    }

    private int execute(Connection connection, String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
            return statement.executeUpdate();
        }
    }
}
