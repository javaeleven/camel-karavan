package org.apache.camel.karavan.complexity;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Getter
@Setter
@NoArgsConstructor
public class ComplexityRoute {

    private String routeId;
    private String routeDescription;
    private String nodePrefixId;
    private String routeTemplateRef;
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private Boolean isTemplated;
    private String fileName;
    private Complexity complexityProcessors = Complexity.easy;
    private Complexity complexityComponentsInt = Complexity.easy;
    private Complexity complexityComponentsExt = Complexity.easy;
    private Complexity complexityKamelets = Complexity.easy;
    private List<ComplexityComponent> consumers = new ArrayList<>();
    private List<ComplexityComponent> producers = new ArrayList<>();
    private Map<String, Integer> processors = new HashMap<>();
    private Map<String, Integer> componentsInt = new HashMap<>();
    private Map<String, Integer> componentsExt = new HashMap<>();
    private Map<String, Integer> kamelets = new HashMap<>();

    public void addProcessor(String component) {
        processors.put(component, processors.getOrDefault(component, 0) + 1);
    }

    public void addComponentInt(String component) {
        componentsInt.put(component, componentsInt.getOrDefault(component, 0) + 1);
    }

    public void addComponentExt(String component) {
        componentsExt.put(component, componentsExt.getOrDefault(component, 0) + 1);
    }

    public void addKamelet(String component) {
        kamelets.put(component, kamelets.getOrDefault(component, 0) + 1);
    }

    public void addProducer(ComplexityComponent producer) {
        this.producers.add(producer);
    }

    public void addConsumer(ComplexityComponent producer) {
        this.consumers.add(producer);
    }

    public Boolean getTemplated() {
        return isTemplated;
    }

    public void setTemplated(Boolean templated) {
        isTemplated = templated;
    }
}
