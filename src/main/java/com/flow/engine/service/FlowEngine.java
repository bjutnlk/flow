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
 * <h3>Spring auto-wiring</h3>
 * <p>All {@link NodeHandler} beans in the application context are auto-collected
 * by Spring via constructor injection ({@code List<NodeHandler>}).  No manual
 * registration is needed — just annotate your handler with {@code @Component}
 * and it will be discovered.
 *
 * <h3>DAG execution</h3>
 * <p>Supports both linear chains ({@code next: "b"}) and fork-join DAGs:
 * <ul>
 *   <li><b>Fork</b> — {@code next: ["b", "d"]} activates multiple branches.</li>
 *   <li><b>Join</b> — {@code waitFor: ["b", "d"]} blocks until all listed
 *       predecessors have completed.</li>
 * </ul>
 *
 * <h3>Execution recording</h3>
 * <p>The engine builds an {@link ExecutionLog} during the run and calls
 * {@link ExecutionRecorder#save} exactly once after the flow completes.
 * There are no per-node callbacks during execution.
 */
@Service
public class FlowEngine {

    private static final Logger log = LoggerFactory.getLogger(FlowEngine.class);
    private static final int MAX_STEPS = 1000;

    public static final String MIN_VERSION = "1.0";
    public static final String CURRENT_VERSION = "2.0";

    private final ObjectMapper objectMapper;
    private final InputResolver inputResolver;
    private final ExecutionRecorder executionRecorder;
    private final Map<String, NodeHandler> handlerRegistry = new ConcurrentHashMap<>();

    /**
     * Spring injects all {@link NodeHandler} beans automatically via
     * the {@code List<NodeHandler>} parameter.
     */
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
        try { return Integer.parseInt(s.trim()); }
        catch (NumberFormatException e) { return 0; }
    }

    // ---- execution ----------------------------------------------------------

    public FlowResult execute(FlowDefinition definition) {
        return execute(definition, new FlowContext(definition.getId()));
    }

    /**
     * Execute a flow definition with the given context.
     *
     * <p>Uses a ready-queue approach to support both linear chains and
     * fork-join DAGs.  A node becomes "ready" when all its {@code waitFor}
     * predecessors have completed.
     */
    public FlowResult execute(FlowDefinition definition, FlowContext context) {
        validateVersion(definition);

        Map<String, FlowNode> nodeMap = definition.toNodeMap();
        List<String> trace = new ArrayList<>();
        Set<String> completedNodes = new LinkedHashSet<>();
        Deque<String> readyQueue = new ArrayDeque<>();

        ExecutionLog executionLog = new ExecutionLog(
                definition.getId(), definition.getName(), definition.getVersion());

        log.info("▶ Starting flow '{}' v{} (executionId={}, start node: '{}')",
                definition.getId(), definition.getVersion(),
                executionLog.getExecutionId(), definition.getStartNodeId());

        readyQueue.add(definition.getStartNodeId());
        int steps = 0;

        try {
            while (!readyQueue.isEmpty() && !context.isTerminated() && steps < MAX_STEPS) {
                String currentNodeId = readyQueue.poll();

                if (completedNodes.contains(currentNodeId)) {
                    continue;
                }

                FlowNode node = nodeMap.get(currentNodeId);
                if (node == null) {
                    throw new NodeNotFoundException(definition.getId(), currentNodeId);
                }

                // Join check: are all waitFor predecessors completed?
                if (node.isJoin() && !completedNodes.containsAll(node.getWaitFor())) {
                    // Not ready yet — re-enqueue at the back
                    readyQueue.addLast(currentNodeId);
                    // Prevent infinite spin: if the queue only contains this node, it's a deadlock
                    if (readyQueue.size() == 1) {
                        List<String> missing = new ArrayList<>(node.getWaitFor());
                        missing.removeAll(completedNodes);
                        throw new FlowException(
                                "Deadlock: node '" + currentNodeId + "' waits for " + missing
                                        + " but they are not reachable",
                                definition.getId(), currentNodeId);
                    }
                    continue;
                }

                steps++;
                context.setCurrentNodeId(currentNodeId);
                trace.add(currentNodeId);

                NodeHandler handler = handlerRegistry.get(node.getType());
                if (handler == null) {
                    throw new HandlerNotFoundException(definition.getId(), node.getType());
                }

                // Build node execution log
                NodeExecutionLog nodeLog = new NodeExecutionLog(
                        node.getId(), node.getType(), node.getName(), steps);

                resolveInputs(node, context);
                nodeLog.setInputSnapshot(context.getResolvedInputs());

                try {
                    HandleResult result = handler.execute(node, context);

                    Map<String, Object> outputSnapshot = null;
                    if (result.hasOutput()) {
                        context.setNodeOutput(currentNodeId, result.getOutput());
                        publishOutputAsVariables(currentNodeId, result.getOutput(), context);
                        outputSnapshot = toOutputSnapshot(result.getOutput());
                    }

                    nodeLog.markSuccess(outputSnapshot);
                    completedNodes.add(currentNodeId);

                    // Determine next node(s)
                    if (result.hasExplicitRoute()) {
                        readyQueue.add(result.getNextNodeId());
                    } else if (node.getNext() != null) {
                        for (String nxt : node.getNext()) {
                            if (!completedNodes.contains(nxt)) {
                                readyQueue.add(nxt);
                            }
                        }
                    }

                } catch (Exception e) {
                    nodeLog.markFailed(e.getMessage());
                    executionLog.addNodeLog(nodeLog);
                    throw e;
                }

                executionLog.addNodeLog(nodeLog);
                context.clearResolvedInputs();
            }

            if (steps >= MAX_STEPS) {
                String msg = "Exceeded maximum step limit: " + MAX_STEPS;
                log.warn("Flow '{}' {}", definition.getId(), msg);
                executionLog.markFailed(msg);
                executionRecorder.save(executionLog);
                return FlowResult.failure(definition.getId(), context.getAllVariables(),
                        trace, msg, executionLog.getExecutionId());
            }

            log.info("✔ Flow '{}' completed successfully in {} step(s)", definition.getId(), steps);
            executionLog.markSuccess();
            executionRecorder.save(executionLog);
            return FlowResult.success(definition.getId(), context.getAllVariables(),
                    trace, executionLog.getExecutionId());

        } catch (FlowException e) {
            log.error("✘ Flow '{}' failed at node '{}': {}", definition.getId(), e.getNodeId(), e.getMessage());
            executionLog.markFailed(e.getMessage());
            executionRecorder.save(executionLog);
            return FlowResult.failure(definition.getId(), context.getAllVariables(),
                    trace, e.getMessage(), executionLog.getExecutionId());
        } catch (Exception e) {
            log.error("✘ Flow '{}' failed unexpectedly: {}", definition.getId(), e.getMessage(), e);
            executionLog.markFailed(e.getMessage());
            executionRecorder.save(executionLog);
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
        output.getEntries().forEach((name, entry) ->
                context.setVariable(nodeId + "." + name, entry.getValue()));
    }

    private Map<String, Object> toOutputSnapshot(NodeOutput output) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        output.getEntries().forEach((name, entry) ->
                snapshot.put(name, entry.toString()));
        return snapshot;
    }
}
