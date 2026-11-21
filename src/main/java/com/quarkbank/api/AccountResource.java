package com.quarkbank.api;

import com.quarkbank.application.BankingService;
import com.quarkbank.domain.BankingModels.*;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import java.util.*;

@Path("/api/accounts")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({"customer", "operator"})
public class AccountResource {
    @Inject BankingService banking;
    @Inject SecurityIdentity identity;

    @GET
    public List<Account> list() { return banking.accounts(principal(), operator()); }

    @POST
    @RolesAllowed("operator")
    public Response open(@NotNull @Valid Requests.OpenAccount request, @Context UriInfo uri) {
        Account account = banking.open(request);
        return Response.created(uri.getAbsolutePathBuilder().path(account.id().toString()).build()).entity(account).build();
    }

    @GET @Path("/{id}")
    public Account get(@PathParam("id") UUID id) { return banking.account(id, principal(), operator()); }

    @GET @Path("/{id}/entries")
    public List<Posting> entries(@PathParam("id") UUID id, @QueryParam("limit") @DefaultValue("50") @Min(1) @Max(100) int limit,
                                @QueryParam("offset") @DefaultValue("0") @Min(0) int offset) {
        return banking.postings(id, principal(), operator(), limit, offset);
    }

    private String principal() { return identity.getPrincipal().getName(); }
    private boolean operator() { return identity.hasRole("operator"); }
}
