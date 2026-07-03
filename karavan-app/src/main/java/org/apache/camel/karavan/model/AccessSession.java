package org.apache.camel.karavan.model;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@AllArgsConstructor
public class AccessSession {

    public String sessionId;

    public String username;

    public String csrfToken;

    public long createdAtMillis;

    public Instant expiredAt;

    public AccessSession copy() {
        return new AccessSession(sessionId, username, csrfToken, createdAtMillis, expiredAt);
    }
}
