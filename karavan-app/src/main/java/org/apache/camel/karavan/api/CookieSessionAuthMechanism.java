package org.apache.camel.karavan.api;

import io.quarkus.security.identity.IdentityProviderManager;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.request.AuthenticationRequest;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.quarkus.vertx.http.runtime.security.ChallengeData;
import io.quarkus.vertx.http.runtime.security.HttpAuthenticationMechanism;
import io.smallrye.mutiny.Uni;
import io.vertx.ext.web.RoutingContext;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.karavan.cache.KaravanCache;

import java.util.Set;

/**
 * Internal session-cookie authentication for machine callers — primarily build
 * containers, which receive a BUILDER_SESSION_ID and call back with it as the
 * "taskId" cookie. Runs before OIDC (Priority 1) but ONLY claims requests that
 * carry a valid session cookie; everything else defers (nullItem) so the OIDC
 * BFF flow handles browsers. Never issues a challenge — machine callers don't
 * need one and browsers must get the OIDC redirect/401.
 */
@Slf4j
@ApplicationScoped
@Priority(1) // run before OIDC, but defer unless a valid session cookie is present
@RequiredArgsConstructor(onConstructor_ = {@Inject})
public class CookieSessionAuthMechanism implements HttpAuthenticationMechanism {

    static final String SESSION_COOKIE = "taskId";

    private final KaravanCache karavanCache;

    @Override
    public Uni<SecurityIdentity> authenticate(RoutingContext ctx, IdentityProviderManager idpm) {
        try {
            var cookie = ctx.request().getCookie(SESSION_COOKIE);
            if (cookie == null) {
                return Uni.createFrom().nullItem();
            }
            var session = karavanCache.getAccessSession(cookie.getValue());
            if (session == null) {
                return Uni.createFrom().nullItem();
            }
            var builder = QuarkusSecurityIdentity.builder();
            builder.setPrincipal(session::getUsername);
            builder.setAnonymous(false);
            var user = karavanCache.getUser(session.getUsername());
            if (user != null && user.getRoles() != null) {
                user.getRoles().forEach(builder::addRole);
            }
            builder.addAttribute("csrf", session.getCsrfToken());
            return Uni.createFrom().item(builder.build());
        } catch (Exception e) {
            log.error("Error while authenticating session: {}", e.getMessage());
            return Uni.createFrom().nullItem();
        }
    }

    @Override
    public Uni<ChallengeData> getChallenge(RoutingContext ctx) {
        // Always defer: the OIDC mechanism owns the browser challenge.
        return Uni.createFrom().nullItem();
    }

    @Override
    public Set<Class<? extends AuthenticationRequest>> getCredentialTypes() {
        return Set.of();
    }
}
