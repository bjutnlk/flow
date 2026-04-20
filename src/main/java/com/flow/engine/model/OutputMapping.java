package com.flow.engine.model;

/**
 * Declares a named output slot that a node produces.
 *
 * <p>Configured in JSON under {@code outputMappings}.  After the handler
 * executes, the engine stores the declared output in the context so that
 * downstream nodes can reference it via {@code ${nodeId.name}}.
 */
public class OutputMapping {

    private String name;
    private String dataType;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDataType() {
        return dataType;
    }

    public void setDataType(String dataType) {
        this.dataType = dataType;
    }

    @Override
    public String toString() {
        return name + (dataType != null ? " (" + dataType + ")" : "");
    }
}
