package org.apache.camel.karavan.model;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ActivityUser {

    private String userName;
    private ActivityType type;
    private Long timeStamp;
    public ActivityUser(String userName) {
        this.userName = userName;
        this.type = ActivityType.HEARTBEAT;
        this.timeStamp = Instant.now().getEpochSecond() * 1000L;
    }

    public ActivityUser(String userName, ActivityType type) {
        this.userName = userName;
        this.type = type;
        this.timeStamp = Instant.now().getEpochSecond() * 1000L;
    }

    public enum ActivityType {
        HEARTBEAT, WORKING
    }
}
