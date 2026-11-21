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
import java.util.UUID;

@Path("/api/transfers")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed({"customer", "operator"})
public class TransferResource {
    @Inject BankingService banking;
    @Inject SecurityIdentity identity;

    @POST
    @RolesAllowed("customer")
    public Response create(@HeaderParam("Idempotency-Key") @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._:-]+") String key,
                           @NotNull @Valid Requests.CreateTransfer request, @Context UriInfo uri) {
        TransferResult result = banking.transfer(request, identity.getPrincipal().getName(), key);
        return Response.status(result.replayed() ? 200 : 201)
                .location(uri.getAbsolutePathBuilder().path(result.transfer().id().toString()).build())
                .header("Idempotency-Replayed", result.replayed()).entity(result.transfer()).build();
    }

    @GET @Path("/{id}")
    public Transfer get(@PathParam("id") UUID id) {
        return banking.transfer(id, identity.getPrincipal().getName(), identity.hasRole("operator"));
    }
}
