package org.apache.camel.karavan.service;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.karavan.cache.KaravanCache;
import org.apache.camel.karavan.model.AccessSession;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;

@Slf4j
@Singleton
public class AuthService {

    public static final int SESSION_MAX_AGE = 12 * 60 * 60;
    // Re-exported from KaravanConstants (single source of truth) so existing
    // imports keep working; new code should use KaravanConstants directly.
    public static final String ROLE_ADMIN = org.apache.camel.karavan.KaravanConstants.ROLE_ADMIN;
    public static final String ROLE_DEVELOPER = org.apache.camel.karavan.KaravanConstants.ROLE_DEVELOPER;
    public static final String ROLE_USER = org.apache.camel.karavan.KaravanConstants.ROLE_USER;
    public static final String USER_ADMIN = org.apache.camel.karavan.KaravanConstants.USER_ADMIN;
    public static final String USER_DEVELOPER = org.apache.camel.karavan.KaravanConstants.USER_DEVELOPER;
    public static final String DEFAULT_EMAIL_SUFFIX = "@platform.platform";
    static final SecureRandom RNG = new SecureRandom();
    @Inject
    KaravanCache karavanCache;

    public static List<String> getAllRoles() {
        return org.apache.camel.karavan.KaravanConstants.getAllRoles();
    }


    public AccessSession createSession(String username) throws Exception {
        String sessionId = random(32);
        String csrf = random(16);
        var createdAt = Instant.now();
        return new AccessSession(sessionId, username, csrf, createdAt.toEpochMilli(), Instant.now().plus(SESSION_MAX_AGE, ChronoUnit.SECONDS));
    }

    public AccessSession createAndSaveSession(String username, boolean persist) throws Exception {
        var session = createSession(username);
        karavanCache.saveAccessSession(session, persist);
        return session;
    }

    public String random(int bytes) {
        byte[] b = new byte[bytes];
        RNG.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
}

