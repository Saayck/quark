package com.quarkbank.api;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.UUID;

public final class Requests {
    private Requests() {}

    public record OpenAccount(@NotBlank @Size(max = 200) String ownerId,
                              @NotNull @Pattern(regexp = "PEN|USD") String currency) {}

    public record CreateTransfer(@NotNull UUID sourceAccountId, @NotNull UUID destinationAccountId,
                                 @NotNull @DecimalMin("0.01") @Digits(integer = 17, fraction = 2) BigDecimal amount,
                                 @NotNull @Pattern(regexp = "PEN|USD") String currency) {}
}
