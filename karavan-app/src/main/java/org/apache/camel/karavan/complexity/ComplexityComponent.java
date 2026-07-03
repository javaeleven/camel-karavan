package org.apache.camel.karavan.complexity;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.HashMap;
import java.util.Map;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ComplexityComponent {

    private String id;
    private String name;
    private Map<String, String> parameters = new HashMap<>();
}
