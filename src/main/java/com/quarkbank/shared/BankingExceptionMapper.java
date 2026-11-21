package com.quarkbank.shared;

import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

@Provider
public class BankingExceptionMapper implements ExceptionMapper<BankingException> {
    public record Error(String code, String message) {}

    @Override
    public Response toResponse(BankingException exception) {
        return Response.status(exception.status())
                .entity(new Error(exception.code(), exception.getMessage())).build();
    }
}
