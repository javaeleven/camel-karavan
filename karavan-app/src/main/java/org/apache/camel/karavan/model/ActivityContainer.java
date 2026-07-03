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
public class ActivityContainer {

    private String userName;
    private String containerName;
    private Instant timeStamp;

    public ActivityContainer(String userName, String containerName) {
        this.userName = userName;
        this.containerName = containerName;
        this.timeStamp = Instant.now();
    }
}
