package com.flow.engine.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flow.engine.exception.FlowException;
import com.flow.engine.exception.HandlerNotFoundException;
import com.flow.engine.exception.NodeNotFoundException;
import com.flow.engine.handler.HandleResult;
import com.flow.engine.handler.NodeHandler;
import com.flow.engine.model.*;
import com.flow.engine.resolve.InputResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Core flow execution engine (Spring {@code @Service} bean).
 *
 * <p>The engine treats every node identically in its execution loop:
 * <ol>
 *   <li><b>Resolve inputs</b> — if the node has {@code inputMappings},
 *       resolve source expressions into concrete values.</li>
 *   <li><b>Execute</b> — dispatch to the matching {@link NodeHandler}.
 *       The handler returns a {@link HandleResult} carrying an optional
 *       {@link NodeOutput} and an optional routing override.</li>
 *   <li><b>Store output</b> — if the handler produced output, store it
 *       under the node's id for downstream {@code ${nodeId.field}} references.</li>
 *   <li><b>Route</b> — use the handler's explicit route if provided,
 *       otherwise follow {@code node.next}.</li>
 * </ol>
 *
 * <p>There is no category distinction — whether a node does condition
 * branching, file processing, or logging is purely the handler's concern.
 * Any node can produce output, and any node can influence routing.
 */
@Service
public class FlowEngine {

    private static final Logger log = LoggerFactory.getLogger(FlowEngine.class);
    private static final int MAX_STEPS = 1000;

    private final ObjectMapper objectMapper;
    private final InputResolver inputResolver;
    private final Map<String, NodeHandler> handlerRegistry = new ConcurrentHashMap<>();

    public FlowEngine(ObjectMapper objectMapper,
                      InputResolver inputResolver,
                      List<NodeHandler> handlers) {
        this.objectMapper = objectMapper;
        this.inputResolver = inputResolver;
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

    // ---- execution ----------------------------------------------------------

    public FlowResult execute(FlowDefinition definition) {
        return execute(definition, new FlowContext(definition.getId()));
    }

    public FlowResult execute(FlowDefinition definition, FlowContext context) {
        Map<String, FlowNode> nodeMap = definition.toNodeMap();
        List<String> trace = new ArrayList<>();
        String currentNodeId = definition.getStartNodeId();

        log.info("▶ Starting flow '{}' (start node: '{}')", definition.getId(), currentNodeId);

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

                // 1. Resolve input mappings (any node can have them)
                resolveInputs(node, context);

                // 2. Execute the handler
                HandleResult result = handler.execute(node, context);

                // 3. Store output if produced (any node can produce output)
                if (result.hasOutput()) {
                    context.setNodeOutput(currentNodeId, result.getOutput());
                    publishOutputAsVariables(currentNodeId, result.getOutput(), context);
                    log.debug("Node '{}' produced output: {}", currentNodeId, result.getOutput());
                }

                // 4. Route: explicit override → node.next → stop
                currentNodeId = result.hasExplicitRoute()
                        ? result.getNextNodeId()
                        : node.getNext();

                context.clearResolvedInputs();
            }

            if (steps >= MAX_STEPS) {
                log.warn("Flow '{}' exceeded maximum step limit ({})", definition.getId(), MAX_STEPS);
                return FlowResult.failure(definition.getId(), context.getAllVariables(), trace,
                        "Exceeded maximum step limit: " + MAX_STEPS);
            }

            log.info("✔ Flow '{}' completed successfully in {} step(s)", definition.getId(), steps);
            return FlowResult.success(definition.getId(), context.getAllVariables(), trace);

        } catch (FlowException e) {
            log.error("✘ Flow '{}' failed at node '{}': {}", definition.getId(), e.getNodeId(), e.getMessage());
            return FlowResult.failure(definition.getId(), context.getAllVariables(), trace, e.getMessage());
        } catch (Exception e) {
            log.error("✘ Flow '{}' failed unexpectedly: {}", definition.getId(), e.getMessage(), e);
            return FlowResult.failure(definition.getId(), context.getAllVariables(), trace, e.getMessage());
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
        log.debug("Resolved {} input(s) for node '{}'", resolved.size(), node.getId());
    }

    private void publishOutputAsVariables(String nodeId, NodeOutput output, FlowContext context) {
        output.getEntries().forEach((name, entry) -> {
            context.setVariable(nodeId + "." + name, entry.getValue());
        });
    }
}
