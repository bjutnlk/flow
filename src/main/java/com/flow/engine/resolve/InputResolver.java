package com.flow.engine.resolve;

import com.flow.engine.model.FileReference;
import com.flow.engine.model.FlowContext;
import com.flow.engine.model.InputMapping;
import com.flow.engine.model.NodeOutput;
import com.flow.engine.storage.FileStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves input-mapping source expressions into concrete values.
 *
 * <h3>Source expression formats</h3>
 * <table>
 *   <tr><th>Pattern</th><th>Example</th><th>Meaning</th></tr>
 *   <tr><td>{@code ${nodeId.field}}</td><td>${parseDoc.resultFile}</td>
 *       <td>Output field "resultFile" of node "parseDoc"</td></tr>
 *   <tr><td>{@code ${varName}}</td><td>${orderId}</td>
 *       <td>Top-level context variable</td></tr>
 *   <tr><td>{@code file:xxx}</td><td>file:abc-123</td>
 *       <td>Cloud storage file ID, resolved via FileStorageService</td></tr>
 *   <tr><td>plain literal</td><td>hello</td>
 *       <td>Used as-is</td></tr>
 * </table>
 *
 * <p>Template strings containing multiple {@code ${…}} placeholders are
 * also supported — each placeholder is resolved independently and the
 * result is interpolated back into the string.
 */
@Component
public class InputResolver {

    private static final Logger log = LoggerFactory.getLogger(InputResolver.class);

    /**
     * Matches {@code ${nodeId.field}} or {@code ${varName}}.
     * Group 1 = full key (may contain dots).
     */
    private static final Pattern REF_PATTERN = Pattern.compile("\\$\\{([\\w.]+)}");

    private static final String FILE_PREFIX = "file:";

    private final FileStorageService fileStorageService;

    public InputResolver(FileStorageService fileStorageService) {
        this.fileStorageService = fileStorageService;
    }

    /**
     * Resolve all input mappings for a node and return a name→value map.
     */
    public Map<String, Object> resolve(List<InputMapping> mappings, FlowContext context) {
        Map<String, Object> resolved = new LinkedHashMap<>();
        if (mappings == null || mappings.isEmpty()) {
            return resolved;
        }

        for (InputMapping mapping : mappings) {
            Object value = resolveSource(mapping.getSource(), mapping.getDataType(), context);
            resolved.put(mapping.getName(), value);
            log.debug("Resolved input '{}': {} → {}", mapping.getName(), mapping.getSource(), summarize(value));
        }
        return resolved;
    }

    /**
     * Resolve a single source expression.
     */
    public Object resolveSource(String source, String expectedType, FlowContext context) {
        if (source == null || source.isBlank()) {
            return null;
        }

        // file:xxx → direct cloud storage reference
        if (source.startsWith(FILE_PREFIX)) {
            String fileId = source.substring(FILE_PREFIX.length()).trim();
            return fileStorageService.resolve(fileId);
        }

        // Pure reference: entire source is a single ${...}
        Matcher pureMatcher = REF_PATTERN.matcher(source);
        if (pureMatcher.matches()) {
            return resolveReference(pureMatcher.group(1), context);
        }

        // Template string with embedded ${...} placeholders
        if (source.contains("${")) {
            return resolveTemplate(source, context);
        }

        // Plain literal — try to coerce to the expected type
        return coerceLiteral(source, expectedType);
    }

    /**
     * Resolve a dotted reference like "nodeA.resultFile" or a simple "varName".
     */
    private Object resolveReference(String ref, FlowContext context) {
        int dotIdx = ref.indexOf('.');
        if (dotIdx > 0) {
            String nodeId = ref.substring(0, dotIdx);
            String field = ref.substring(dotIdx + 1);

            // Try node output first
            NodeOutput output = context.getNodeOutput(nodeId);
            if (output != null && output.has(field)) {
                return output.getValue(field);
            }

            // Fall back to nested variable convention "nodeId.field"
            Object flatValue = context.getVariable(ref);
            if (flatValue != null) {
                return flatValue;
            }

            log.warn("Unresolved reference: ${{}}", ref);
            return null;
        }

        // Simple variable
        return context.getVariable(ref);
    }

    /**
     * Interpolate a template string, replacing each ${...} with its resolved
     * value's string representation.
     */
    private String resolveTemplate(String template, FlowContext context) {
        Matcher m = REF_PATTERN.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            Object val = resolveReference(m.group(1), context);
            String replacement = val != null ? toDisplayString(val) : "null";
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private String toDisplayString(Object value) {
        if (value instanceof FileReference fr) {
            return fr.getFileId();
        }
        return String.valueOf(value);
    }

    private Object coerceLiteral(String value, String expectedType) {
        if (expectedType == null) {
            return value;
        }
        return switch (expectedType.toUpperCase()) {
            case "NUMBER" -> {
                try { yield Long.parseLong(value); }
                catch (NumberFormatException e) {
                    try { yield Double.parseDouble(value); }
                    catch (NumberFormatException e2) { yield value; }
                }
            }
            case "BOOLEAN" -> Boolean.parseBoolean(value);
            default -> value;
        };
    }

    private String summarize(Object value) {
        if (value instanceof FileReference fr) {
            return "FileRef(" + fr.getFileId() + ")";
        }
        String s = String.valueOf(value);
        return s.length() > 80 ? s.substring(0, 80) + "…" : s;
    }
}
