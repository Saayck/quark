package com.quarkbank.application;

import com.quarkbank.api.Requests.*;
import com.quarkbank.domain.BankingModels.*;
import com.quarkbank.domain.Money;
import com.quarkbank.infrastructure.BankRepository;
import com.quarkbank.shared.BankingException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

@ApplicationScoped
public class BankingService {
    @Inject DataSource dataSource;
    @Inject BankRepository repository;

    @Transactional
    public Account open(OpenAccount request) {
        Account account = new Account(UUID.randomUUID(), request.ownerId(), Money.currency(request.currency()), "CUSTOMER", new BigDecimal("0.00"), Instant.now().truncatedTo(ChronoUnit.MICROS));
        try (Connection connection = dataSource.getConnection()) {
            repository.openAccount(connection, account);
            return account;
        } catch (SQLException exception) { throw databaseFailure(exception); }
    }

    public List<Account> accounts(String principal, boolean operator) {
        try (Connection connection = dataSource.getConnection()) {
            return repository.accounts(connection, operator ? null : principal);
        } catch (SQLException exception) { throw databaseFailure(exception); }
    }

    public Account account(UUID id, String principal, boolean operator) {
        try (Connection connection = dataSource.getConnection()) {
            return visibleAccount(connection, id, principal, operator);
        } catch (SQLException exception) { throw databaseFailure(exception); }
    }

    public List<Posting> postings(UUID id, String principal, boolean operator, int limit, int offset) {
        try (Connection connection = dataSource.getConnection()) {
            visibleAccount(connection, id, principal, operator);
            return repository.postings(connection, id, limit, offset);
        } catch (SQLException exception) { throw databaseFailure(exception); }
    }

    public Transfer transfer(UUID id, String principal, boolean operator) {
        try (Connection connection = dataSource.getConnection()) {
            Transfer transfer = repository.transfer(connection, id).orElseThrow(BankingService::notFound);
            if (!operator && !transfer.principalId().equals(principal)) throw notFound();
            return transfer;
        } catch (SQLException exception) { throw databaseFailure(exception); }
    }

    @Transactional
    public TransferResult transfer(CreateTransfer request, String principal, String key) {
        BigDecimal amount = Money.positive(request.amount());
        String currency = Money.currency(request.currency());
        if (request.sourceAccountId().equals(request.destinationAccountId())) {
            throw new BankingException(422, "SAME_ACCOUNT", "Las cuentas de origen y destino deben ser diferentes.");
        }
        try (Connection connection = dataSource.getConnection()) {
            Optional<Transfer> previous = repository.transferByKey(connection, principal, key);
            if (previous.isPresent()) return replay(previous.get(), request);

            Map<UUID, Account> locked = repository.lockAccounts(connection, request.sourceAccountId(), request.destinationAccountId());
            Account source = locked.get(request.sourceAccountId());
            Account destination = locked.get(request.destinationAccountId());
            if (source == null || !source.ownerId().equals(principal) || !source.kind().equals("CUSTOMER")) throw notFound();
            if (destination == null || !destination.kind().equals("CUSTOMER")) throw notFound();

            // Recheck after waiting for account locks: a concurrent retry may have committed.
            previous = repository.transferByKey(connection, principal, key);
            if (previous.isPresent()) return replay(previous.get(), request);
            if (!source.currency().equals(currency) || !destination.currency().equals(currency)) {
                throw new BankingException(422, "CURRENCY_MISMATCH", "Ambas cuentas deben tener la moneda de la transferencia.");
            }
            if (source.balance().compareTo(amount) < 0) throw new BankingException(422, "INSUFFICIENT_FUNDS", "Saldo insuficiente.");
            if (destination.balance().add(amount).compareTo(Money.MAX) > 0) throw new BankingException(422, "BALANCE_LIMIT", "La cuenta de destino supera el saldo permitido.");

            Transfer transfer = new Transfer(UUID.randomUUID(), principal, key, source.id(), destination.id(), amount, currency, Instant.now().truncatedTo(ChronoUnit.MICROS));
            if (!repository.reserveTransfer(connection, transfer)) {
                return replay(repository.transferByKey(connection, principal, key).orElseThrow(), request);
            }
            repository.journal(connection, transfer.id(), currency, transfer.createdAt());
            repository.balance(connection, source.id(), source.balance().subtract(amount));
            repository.balance(connection, destination.id(), destination.balance().add(amount));
            repository.post(connection, transfer.id(), source.id(), amount.negate(), transfer.createdAt());
            repository.post(connection, transfer.id(), destination.id(), amount, transfer.createdAt());
            return new TransferResult(transfer, false);
        } catch (SQLException exception) { throw databaseFailure(exception); }
    }

    private TransferResult replay(Transfer transfer, CreateTransfer request) {
        if (!transfer.sourceAccountId().equals(request.sourceAccountId()) || !transfer.destinationAccountId().equals(request.destinationAccountId())
                || transfer.amount().compareTo(request.amount()) != 0 || !transfer.currency().equals(request.currency())) {
            throw new BankingException(409, "IDEMPOTENCY_CONFLICT", "La clave de idempotencia ya se usó con otros datos.");
        }
        return new TransferResult(transfer, true);
    }

    private Account visibleAccount(Connection connection, UUID id, String principal, boolean operator) throws SQLException {
        Account account = repository.account(connection, id).orElseThrow(BankingService::notFound);
        if (!operator && !account.ownerId().equals(principal)) throw notFound();
        return account;
    }

    private static BankingException notFound() { return new BankingException(404, "NOT_FOUND", "Recurso no encontrado."); }
    private static IllegalStateException databaseFailure(SQLException cause) { return new IllegalStateException("No se pudo completar la operación bancaria.", cause); }
}
