package com.flow.engine.model;

/**
 * Maps an input parameter of a node to a value source.
 *
 * <p>Configured in the JSON flow definition under each node's
 * {@code inputMappings} array.  The engine resolves the {@code source}
 * expression before the node executes.
 *
 * <h3>Source expression formats</h3>
 * <ul>
 *   <li>{@code ${nodeId.outputName}} — references a specific output field
 *       of a previously executed node</li>
 *   <li>{@code ${nodeId}} — shorthand for the single / default output</li>
 *   <li>{@code ${varName}} — references a top-level context variable</li>
 *   <li>{@code file:xxx-yyy-zzz} — a cloud-storage file ID, resolved
 *       at runtime via {@link com.flow.engine.storage.FileStorageService}</li>
 *   <li>plain literal — used as-is</li>
 * </ul>
 */
public class InputMapping {

    private String name;
    private String source;
    private String dataType;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getDataType() {
        return dataType;
    }

    public void setDataType(String dataType) {
        this.dataType = dataType;
    }

    @Override
    public String toString() {
        return name + " ← " + source + (dataType != null ? " (" + dataType + ")" : "");
    }
}
