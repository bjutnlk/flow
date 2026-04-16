package com.flow.engine.handler;

import com.flow.engine.model.FlowContext;
import com.flow.engine.model.FlowNode;

/**
 * Strategy interface for node execution.
 *
 * <p>Every node — regardless of whether it performs business logic, controls
 * routing, or both — is processed by a single {@link #execute} method.
 * The returned {@link HandleResult} carries:
 * <ul>
 *   <li>An optional {@link com.flow.engine.model.NodeOutput} that the engine
 *       stores under the node's id for downstream references.</li>
 *   <li>An optional next-node id to override the default {@code node.next}.</li>
 * </ul>
 *
 * <p>Implementations are registered as Spring beans; the engine discovers
 * them via {@link #getType()} and dispatches each node to the matching handler.
 */
public interface NodeHandler {

    /**
     * The node type this handler is responsible for
     * (e.g. "start", "condition", "submit_form").
     */
    String getType();

    /**
     * Execute the node's logic.
     *
     * @param node    the current node definition
     * @param context shared mutable context (inputs already resolved)
     * @return result containing optional output and optional routing override
     */
    HandleResult execute(FlowNode node, FlowContext context);
}
