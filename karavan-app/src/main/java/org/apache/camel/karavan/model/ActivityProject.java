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
public class ActivityProject {

    private String userName;
    private String projectId;
    private Instant timeStamp;
    private ActivityCommand command;

    public static ActivityProject createAdd(String userName, String projectId) {
        return new ActivityProject(userName, projectId, Instant.now(), ActivityCommand.ADD);
    }

    public static ActivityProject createDelete() {
        return new ActivityProject(null, null, Instant.now(), ActivityCommand.DELETE);
    }
}
