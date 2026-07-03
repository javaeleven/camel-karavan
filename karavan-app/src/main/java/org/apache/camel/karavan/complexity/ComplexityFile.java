package org.apache.camel.karavan.complexity;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.util.HashMap;
import java.util.Map;

@Getter
@Setter
@NoArgsConstructor
@ToString(of = {"fileName", "type", "chars", "routes", "beans", "rests", "complexity", "complexityLines",
        "complexityRoutes", "complexityRests", "complexityBeans", "complexityProcessors",
        "complexityComponentsInt", "complexityComponentsExt", "complexityKamelets",
        "processors", "componentsInt", "componentsExt", "kamelets"})
public class ComplexityFile {

    private String fileName;
    private String error;
    private Type type;
    private Integer chars = 0;
    private Integer routes = 0;
    private Integer beans = 0;
    private Integer rests = 0;
    private boolean isGenerated = false;
    private Complexity complexity = Complexity.easy;
    private Complexity complexityLines = Complexity.easy;
    private Complexity complexityRoutes = Complexity.easy;
    private Complexity complexityRests = Complexity.easy;
    private Complexity complexityBeans = Complexity.easy;
    private Complexity complexityProcessors = Complexity.easy;
    private Complexity complexityComponentsInt = Complexity.easy;
    private Complexity complexityComponentsExt = Complexity.easy;
    private Complexity complexityKamelets = Complexity.easy;
    private Map<String, Integer> processors = new HashMap<>();
    private Map<String, Integer> componentsInt = new HashMap<>();
    private Map<String, Integer> componentsExt = new HashMap<>();
    private Map<String, Integer> kamelets = new HashMap<>();

    public void addProcessor(String component, Integer count) {
        processors.put(component, processors.getOrDefault(component, 0) + count);
    }

    public void addComponentExt(String component, Integer count) {
        componentsExt.put(component, componentsExt.getOrDefault(component, 0) + count);
    }

    public void addComponentInt(String component, Integer count) {
        componentsInt.put(component, componentsInt.getOrDefault(component, 0) + count);
    }

    public void addKamelet(String component, Integer count) {
        kamelets.put(component, kamelets.getOrDefault(component, 0) + count);
    }

    public enum Type {camel, java, docker, kubernetes, properties, other, openapi}
}
