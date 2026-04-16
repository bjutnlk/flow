package com.flow.engine.handler;

import com.flow.engine.model.FlowContext;
import com.flow.engine.model.FlowNode;
import com.flow.engine.model.NodeOutput;

/**
 * Strategy interface for node execution.
 *
 * <p>Implementations are registered as Spring beans; the engine discovers them
 * automatically via {@link #getType()} and dispatches each node to the
 * matching handler.
 *
 * <p>A handler can influence routing by returning a specific next-node id
 * from {@link #handle}, or {@code null} to fall back to the node's default
 * {@code next} field.
 *
 * <p>Capability handlers that produce typed outputs (files, strings, images)
 * should override {@link #execute} instead.  The default {@code handle}
 * implementation delegates to {@code execute} and stores the resulting
 * {@link NodeOutput} in the context automatically.
 */
public interface NodeHandler {

    /**
     * The node type this handler is responsible for (e.g. "task", "condition", "submit_form").
     */
    String getType();

    /**
     * Execute the logic for the given node.
     *
     * @param node    the current node definition (read-only by convention)
     * @param context shared mutable context (inputs already resolved)
     * @return an explicit next-node id, or {@code null} to use {@link FlowNode#getNext()}
     */
    String handle(FlowNode node, FlowContext context);

    /**
     * Execute and produce a structured output.  Override this in capability
     * handlers.  The engine will call this method, store the returned output
     * under the node's id, and then call {@link #handle} for routing.
     *
     * @return node output, or {@code null} if this handler produces no output
     */
    default NodeOutput execute(FlowNode node, FlowContext context) {
        return null;
    }
}
