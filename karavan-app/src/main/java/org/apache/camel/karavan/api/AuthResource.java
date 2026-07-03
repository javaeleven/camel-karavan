package org.apache.camel.karavan.api;

import io.quarkus.security.Authenticated;
import jakarta.annotation.security.PermitAll;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import org.apache.camel.karavan.cache.KaravanCache;
import org.apache.camel.karavan.model.AccessUser;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@Path("/ui/auth")
@Produces(MediaType.APPLICATION_JSON)
public class AuthResource extends AbstractApiResource {

    private static final String SESSION_ID = "taskId";
    private static final String CSRF = "csrf";

    // platform.* cannot be a @ConfigMapping: the prefix collides with Quarkus's own
    // platform properties (platform.quarkus.*), which fails startup validation.
    @ConfigProperty(name = "platform.auth", defaultValue = "oidc")
    String platformAuth;

    @Inject
    KaravanCache karavanCache;

    @GET
    @Path("/type")
    @Produces(MediaType.TEXT_PLAIN)
    @PermitAll
    public Response authType() throws Exception {
        String authType = platformAuth;
        return Response.ok(authType).build();
    }


    @GET
    @Path("/me")
    @Authenticated
    @Produces("application/json")
    public Response me(@Context SecurityContext sc) {
        var username = getIdentity().getString("username");
        var user = karavanCache.getUser(username);
        // In OIDC (BFF) mode the principal comes from the IdP token and is not in
        // the local user cache; build the response from the SecurityIdentity
        // (username + roles + email) so the SPA still gets a populated user.
        var identity = getIdentity();
        if (user == null) {
            // First SSO login of this principal: provision it into the local user
            // cache so the Access page reflects actual IdP users.
            var provisioned = new AccessUser();
            provisioned.setUsername(username);
            provisioned.setEmail(identity.getString("email"));
            provisioned.setFirstName(identity.getString("firstName"));
            provisioned.setLastName(identity.getString("lastName"));
            provisioned.setRoles(identity.getJsonArray("roles").stream().map(Object::toString).toList());
            provisioned.setStatus(AccessUser.UserStatus.ACTIVE);
            karavanCache.saveUser(provisioned, true);
            return Response.ok(provisioned).build();
        }
        // Backfill names for users provisioned before name claims were wired
        // (or when the IdP profile gains them later).
        String firstName = identity.getString("firstName");
        String lastName = identity.getString("lastName");
        boolean needsBackfill = (isBlank(user.getFirstName()) && !isBlank(firstName))
                || (isBlank(user.getLastName()) && !isBlank(lastName));
        if (needsBackfill) {
            user.setFirstName(isBlank(user.getFirstName()) ? firstName : user.getFirstName());
            user.setLastName(isBlank(user.getLastName()) ? lastName : user.getLastName());
            karavanCache.saveUser(user, true);
        }
        return Response.ok(user).build();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
