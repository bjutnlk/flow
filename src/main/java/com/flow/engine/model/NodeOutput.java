package com.flow.engine.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Immutable output produced by a node after execution.
 *
 * <p>Each output entry has a name, a {@link DataType}, and a value.
 * For file / image types the value is typically a {@link FileReference}.
 * Downstream nodes reference outputs via {@code ${nodeId.outputName}}.
 */
public class NodeOutput {

    public enum DataType {
        STRING,
        NUMBER,
        BOOLEAN,
        FILE,
        IMAGE,
        JSON,
        LIST
    }

    private final Map<String, OutputEntry> entries;

    private NodeOutput(Map<String, OutputEntry> entries) {
        this.entries = Collections.unmodifiableMap(entries);
    }

    public Map<String, OutputEntry> getEntries() {
        return entries;
    }

    public OutputEntry getEntry(String name) {
        return entries.get(name);
    }

    public Object getValue(String name) {
        OutputEntry entry = entries.get(name);
        return entry != null ? entry.getValue() : null;
    }

    public boolean has(String name) {
        return entries.containsKey(name);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static NodeOutput single(String name, DataType type, Object value) {
        return builder().add(name, type, value).build();
    }

    public static class OutputEntry {
        private final DataType type;
        private final Object value;

        public OutputEntry(DataType type, Object value) {
            this.type = type;
            this.value = value;
        }

        public DataType getType() {
            return type;
        }

        public Object getValue() {
            return value;
        }

        @Override
        public String toString() {
            if (type == DataType.FILE || type == DataType.IMAGE) {
                return type + "(" + value + ")";
            }
            return String.valueOf(value);
        }
    }

    public static class Builder {
        private final Map<String, OutputEntry> entries = new LinkedHashMap<>();

        public Builder add(String name, DataType type, Object value) {
            entries.put(name, new OutputEntry(type, value));
            return this;
        }

        public Builder addString(String name, String value) {
            return add(name, DataType.STRING, value);
        }

        public Builder addFile(String name, FileReference fileRef) {
            return add(name, DataType.FILE, fileRef);
        }

        public Builder addImage(String name, FileReference fileRef) {
            return add(name, DataType.IMAGE, fileRef);
        }

        public Builder addNumber(String name, Number value) {
            return add(name, DataType.NUMBER, value);
        }

        public NodeOutput build() {
            return new NodeOutput(entries);
        }
    }

    @Override
    public String toString() {
        return "NodeOutput" + entries;
    }
}
