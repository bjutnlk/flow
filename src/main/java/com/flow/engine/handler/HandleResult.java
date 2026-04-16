package com.flow.engine.handler;

import com.flow.engine.model.NodeOutput;

/**
 * Result of a handler's execution, carrying both:
 * <ul>
 *   <li>An optional {@link NodeOutput} (typed outputs like files, strings, etc.)</li>
 *   <li>An optional next-node id to override the default {@code node.next} routing</li>
 * </ul>
 *
 * <p>Use the static factory methods for common cases:
 * <pre>
 *   HandleResult.none()                      // no output, follow node.next
 *   HandleResult.output(nodeOutput)          // has output, follow node.next
 *   HandleResult.routeTo("other-node")       // no output, jump to specific node
 *   HandleResult.of(nodeOutput, "other-node")// both
 * </pre>
 */
public class HandleResult {

    private final NodeOutput output;
    private final String nextNodeId;

    private HandleResult(NodeOutput output, String nextNodeId) {
        this.output = output;
        this.nextNodeId = nextNodeId;
    }

    public static HandleResult none() {
        return new HandleResult(null, null);
    }

    public static HandleResult output(NodeOutput output) {
        return new HandleResult(output, null);
    }

    public static HandleResult routeTo(String nextNodeId) {
        return new HandleResult(null, nextNodeId);
    }

    public static HandleResult of(NodeOutput output, String nextNodeId) {
        return new HandleResult(output, nextNodeId);
    }

    public NodeOutput getOutput() {
        return output;
    }

    public boolean hasOutput() {
        return output != null;
    }

    public String getNextNodeId() {
        return nextNodeId;
    }

    public boolean hasExplicitRoute() {
        return nextNodeId != null;
    }
}
