package com.quarkbank.domain;

import com.quarkbank.shared.BankingException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Set;

public final class Money {
    public static final BigDecimal MAX = new BigDecimal("99999999999999999.99");
    private static final Set<String> CURRENCIES = Set.of("PEN", "USD");

    private Money() {}

    public static BigDecimal positive(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0 || amount.compareTo(MAX) > 0) {
            throw new BankingException(400, "INVALID_AMOUNT", "El monto debe ser positivo y tener hasta 17 enteros y 2 decimales.");
        }
        try {
            return amount.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException exception) {
            throw new BankingException(400, "INVALID_AMOUNT", "El monto no puede tener fracciones menores a un centavo.");
        }
    }

    public static String currency(String currency) {
        if (!CURRENCIES.contains(currency == null ? "" : currency)) {
            throw new BankingException(400, "UNSUPPORTED_CURRENCY", "Las monedas habilitadas son PEN y USD.");
        }
        return currency;
    }
}
