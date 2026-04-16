package com.flow.engine.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flow.engine.exception.FlowException;
import com.flow.engine.exception.HandlerNotFoundException;
import com.flow.engine.exception.NodeNotFoundException;
import com.flow.engine.exception.UnsupportedVersionException;
import com.flow.engine.handler.HandleResult;
import com.flow.engine.handler.NodeHandler;
import com.flow.engine.model.*;
import com.flow.engine.recorder.ExecutionLog;
import com.flow.engine.recorder.ExecutionRecorder;
import com.flow.engine.recorder.NodeExecutionLog;
import com.flow.engine.resolve.InputResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Core flow execution engine (Spring {@code @Service} bean).
 *
 * <p>Execution lifecycle:
 * <ol>
 *   <li><b>Version check</b> — rejects flow definitions whose version
 *       is below {@link #MIN_VERSION}.</li>
 *   <li><b>Record flow start</b> — creates an {@link ExecutionLog}.</li>
 *   <li>For each node:
 *     <ol>
 *       <li>Record node start → resolve inputs → execute handler →
 *           store output → record node complete → route.</li>
 *     </ol>
 *   </li>
 *   <li><b>Record flow complete</b> — finalizes the execution log.</li>
 * </ol>
 *
 * <p>The execution log captures every node's status, duration, input/output
 * snapshots, and error messages.  The {@link FlowResult#getExecutionId()}
 * can be used to retrieve the full log from the {@link ExecutionRecorder}.
 */
@Service
public class FlowEngine {

    private static final Logger log = LoggerFactory.getLogger(FlowEngine.class);
    private static final int MAX_STEPS = 1000;

    /**
     * Minimum supported flow definition version (inclusive).
     * Flow definitions with a version below this will be rejected.
     */
    public static final String MIN_VERSION = "1.0";

    /**
     * Current / latest known version.
     */
    public static final String CURRENT_VERSION = "2.0";

    private final ObjectMapper objectMapper;
    private final InputResolver inputResolver;
    private final ExecutionRecorder executionRecorder;
    private final Map<String, NodeHandler> handlerRegistry = new ConcurrentHashMap<>();

    public FlowEngine(ObjectMapper objectMapper,
                      InputResolver inputResolver,
                      ExecutionRecorder executionRecorder,
                      List<NodeHandler> handlers) {
        this.objectMapper = objectMapper;
        this.inputResolver = inputResolver;
        this.executionRecorder = executionRecorder;
        handlers.forEach(h -> {
            handlerRegistry.put(h.getType(), h);
            log.debug("Registered handler for node type '{}'", h.getType());
        });
        log.info("FlowEngine initialized with {} handler(s): {}", handlerRegistry.size(), handlerRegistry.keySet());
    }

    // ---- parsing ------------------------------------------------------------

    public FlowDefinition parse(String json) {
        try {
            return objectMapper.readValue(json, FlowDefinition.class);
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to parse flow JSON", e);
        }
    }

    public FlowDefinition parse(InputStream inputStream) {
        try {
            return objectMapper.readValue(inputStream, FlowDefinition.class);
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to parse flow JSON from stream", e);
        }
    }

    // ---- version validation -------------------------------------------------

    /**
     * Check if the flow definition version is supported.
     *
     * @throws UnsupportedVersionException if version is below MIN_VERSION
     */
    public void validateVersion(FlowDefinition definition) {
        String version = definition.getVersion();
        if (version == null || version.isBlank()) {
            throw new UnsupportedVersionException(
                    definition.getId(), "(not set)", MIN_VERSION);
        }
        if (compareVersions(version, MIN_VERSION) < 0) {
            throw new UnsupportedVersionException(
                    definition.getId(), version, MIN_VERSION);
        }
    }

    /**
     * Compare two dot-separated version strings numerically.
     * Returns negative if a &lt; b, zero if equal, positive if a &gt; b.
     */
    public static int compareVersions(String a, String b) {
        String[] aParts = a.split("\\.");
        String[] bParts = b.split("\\.");
        int len = Math.max(aParts.length, bParts.length);
        for (int i = 0; i < len; i++) {
            int aN = i < aParts.length ? parseSegment(aParts[i]) : 0;
            int bN = i < bParts.length ? parseSegment(bParts[i]) : 0;
            if (aN != bN) return Integer.compare(aN, bN);
        }
        return 0;
    }

    private static int parseSegment(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ---- execution ----------------------------------------------------------

    public FlowResult execute(FlowDefinition definition) {
        return execute(definition, new FlowContext(definition.getId()));
    }

    public FlowResult execute(FlowDefinition definition, FlowContext context) {
        // 0. Version validation
        validateVersion(definition);

        Map<String, FlowNode> nodeMap = definition.toNodeMap();
        List<String> trace = new ArrayList<>();
        String currentNodeId = definition.getStartNodeId();

        // 1. Start recording
        ExecutionLog executionLog = new ExecutionLog(
                definition.getId(), definition.getName(), definition.getVersion());
        executionRecorder.onFlowStart(executionLog);

        log.info("▶ Starting flow '{}' v{} (executionId={}, start node: '{}')",
                definition.getId(), definition.getVersion(),
                executionLog.getExecutionId(), currentNodeId);

        int steps = 0;
        try {
            while (currentNodeId != null && !context.isTerminated() && steps < MAX_STEPS) {
                steps++;
                FlowNode node = nodeMap.get(currentNodeId);
                if (node == null) {
                    throw new NodeNotFoundException(definition.getId(), currentNodeId);
                }

                context.setCurrentNodeId(currentNodeId);
                trace.add(currentNodeId);

                NodeHandler handler = handlerRegistry.get(node.getType());
                if (handler == null) {
                    throw new HandlerNotFoundException(definition.getId(), node.getType());
                }

                // 2. Record node start
                NodeExecutionLog nodeLog = new NodeExecutionLog(
                        node.getId(), node.getType(), node.getName(), steps);

                // Resolve inputs
                resolveInputs(node, context);
                nodeLog.setInputSnapshot(context.getResolvedInputs());

                executionRecorder.onNodeStart(executionLog, nodeLog);

                try {
                    // 3. Execute handler
                    HandleResult result = handler.execute(node, context);

                    // 4. Store output
                    Map<String, Object> outputSnapshot = null;
                    if (result.hasOutput()) {
                        context.setNodeOutput(currentNodeId, result.getOutput());
                        publishOutputAsVariables(currentNodeId, result.getOutput(), context);
                        outputSnapshot = toOutputSnapshot(result.getOutput());
                    }

                    // 5. Record node success
                    nodeLog.markSuccess(outputSnapshot);
                    executionLog.addNodeLog(nodeLog);
                    executionRecorder.onNodeComplete(executionLog, nodeLog);

                    // 6. Route
                    currentNodeId = result.hasExplicitRoute()
                            ? result.getNextNodeId()
                            : node.getNext();

                } catch (Exception e) {
                    // Record node failure
                    nodeLog.markFailed(e.getMessage());
                    executionLog.addNodeLog(nodeLog);
                    executionRecorder.onNodeComplete(executionLog, nodeLog);
                    throw e;
                }

                context.clearResolvedInputs();
            }

            if (steps >= MAX_STEPS) {
                String msg = "Exceeded maximum step limit: " + MAX_STEPS;
                log.warn("Flow '{}' {}", definition.getId(), msg);
                executionLog.markFailed(msg);
                executionRecorder.onFlowComplete(executionLog);
                return FlowResult.failure(definition.getId(), context.getAllVariables(),
                        trace, msg, executionLog.getExecutionId());
            }

            // Flow succeeded
            log.info("✔ Flow '{}' completed successfully in {} step(s)", definition.getId(), steps);
            executionLog.markSuccess();
            executionRecorder.onFlowComplete(executionLog);
            return FlowResult.success(definition.getId(), context.getAllVariables(),
                    trace, executionLog.getExecutionId());

        } catch (FlowException e) {
            log.error("✘ Flow '{}' failed at node '{}': {}", definition.getId(), e.getNodeId(), e.getMessage());
            executionLog.markFailed(e.getMessage());
            executionRecorder.onFlowComplete(executionLog);
            return FlowResult.failure(definition.getId(), context.getAllVariables(),
                    trace, e.getMessage(), executionLog.getExecutionId());
        } catch (Exception e) {
            log.error("✘ Flow '{}' failed unexpectedly: {}", definition.getId(), e.getMessage(), e);
            executionLog.markFailed(e.getMessage());
            executionRecorder.onFlowComplete(executionLog);
            return FlowResult.failure(definition.getId(), context.getAllVariables(),
                    trace, e.getMessage(), executionLog.getExecutionId());
        }
    }

    // ---- handler management -------------------------------------------------

    public void registerHandler(NodeHandler handler) {
        handlerRegistry.put(handler.getType(), handler);
        log.info("Registered handler for node type '{}'", handler.getType());
    }

    public boolean hasHandler(String nodeType) {
        return handlerRegistry.containsKey(nodeType);
    }

    // ---- internal -----------------------------------------------------------

    private void resolveInputs(FlowNode node, FlowContext context) {
        List<InputMapping> mappings = node.getInputMappings();
        if (mappings == null || mappings.isEmpty()) {
            return;
        }
        Map<String, Object> resolved = inputResolver.resolve(mappings, context);
        context.setResolvedInputs(resolved);
    }

    private void publishOutputAsVariables(String nodeId, NodeOutput output, FlowContext context) {
        output.getEntries().forEach((name, entry) -> {
            context.setVariable(nodeId + "." + name, entry.getValue());
        });
    }

    private Map<String, Object> toOutputSnapshot(NodeOutput output) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        output.getEntries().forEach((name, entry) -> {
            snapshot.put(name, entry.toString());
        });
        return snapshot;
    }
}
