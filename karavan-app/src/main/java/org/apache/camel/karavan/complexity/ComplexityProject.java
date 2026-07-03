package org.apache.camel.karavan.complexity;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@ToString(of = {"projectId", "lastUpdateDate", "complexityRoute", "complexityRest", "complexityJava",
        "complexityFiles", "files", "routes", "rests", "dependencies"})
public class ComplexityProject {

    private String projectId;
    private String type;
    private Long lastUpdateDate = 0L;
    private Complexity complexityRoute = Complexity.easy;
    private Complexity complexityRest = Complexity.easy;
    private Complexity complexityJava = Complexity.easy;
    private Complexity complexityFiles = Complexity.easy;
    private List<ComplexityFile> files = new ArrayList<>();
    private List<ComplexityRoute> routes = new ArrayList<>();
    private Integer rests = 0;
    private boolean exposesOpenApi = false;
    private List<String> dependencies = new ArrayList<>();

    public void addFile(ComplexityFile file) {
        this.files.add(file);
    }
}
