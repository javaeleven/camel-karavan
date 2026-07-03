package org.apache.camel.karavan.model;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class PodEvent {

    private String id;
    private String containerName;
    private String reason;
    private String note;
    private String creationTimestamp;
}
