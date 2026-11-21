package com.quarkbank.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public final class BankingModels {
    private BankingModels() {}

    public record Account(UUID id, String ownerId, String currency, String kind, BigDecimal balance, Instant createdAt) {}
    public record Transfer(UUID id, String principalId, String idempotencyKey, UUID sourceAccountId,
                           UUID destinationAccountId, BigDecimal amount, String currency, Instant createdAt) {}
    public record Posting(UUID id, UUID journalId, UUID accountId, BigDecimal signedAmount, Instant createdAt) {}
    public record TransferResult(Transfer transfer, boolean replayed) {}
}
