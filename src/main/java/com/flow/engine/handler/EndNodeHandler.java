package com.flow.engine.handler;

import com.flow.engine.model.FlowContext;
import com.flow.engine.model.FlowNode;
import com.flow.engine.model.NodeOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Flow end node — terminates execution and collects return values.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Terminate the flow.</li>
 *   <li>Collect return values declared in {@code properties.returnValues}
 *       — a map of output-name → source-expression.  Each source is
 *       resolved from the context (supports {@code ${nodeId.field}},
 *       {@code ${varName}}, etc.).</li>
 *   <li>The collected return values are stored as the end node's output,
 *       accessible in FlowResult via the execution trace.</li>
 * </ul>
 *
 * <p>JSON example:
 * <pre>{@code
 * {
 *   "id": "end",
 *   "type": "end",
 *   "name": "Done",
 *   "properties": {
 *     "returnValues": {
 *       "status": "${processNode.result}",
 *       "outputFile": "${mergeNode.mergedFile}"
 *     }
 *   }
 * }
 * }</pre>
 */
@Component
public class EndNodeHandler implements NodeHandler {

    private static final Logger log = LoggerFactory.getLogger(EndNodeHandler.class);

    @Override
    public String getType() {
        return "end";
    }

    @Override
    public HandleResult execute(FlowNode node, FlowContext context) {
        log.info("[End] Flow '{}' ending at node '{}'", context.getFlowId(), node.getId());

        NodeOutput output = collectReturnValues(node, context);

        context.terminate();

        return output != null ? HandleResult.output(output) : HandleResult.none();
    }

    @SuppressWarnings("unchecked")
    private NodeOutput collectReturnValues(FlowNode node, FlowContext context) {
        if (node.getProperties() == null) {
            return null;
        }

        Object rv = node.getProperties().get("returnValues");
        if (!(rv instanceof Map)) {
            return null;
        }

        Map<String, Object> returnDefs = (Map<String, Object>) rv;
        if (returnDefs.isEmpty()) {
            return null;
        }

        NodeOutput.Builder builder = NodeOutput.builder();
        for (Map.Entry<String, Object> entry : returnDefs.entrySet()) {
            String name = entry.getKey();
            String sourceExpr = String.valueOf(entry.getValue());

            Object resolved = resolveSimple(sourceExpr, context);
            builder.add(name, NodeOutput.DataType.STRING, resolved);
        }

        NodeOutput output = builder.build();
        log.info("[End] Return values: {}", output);
        return output;
    }

    private Object resolveSimple(String expr, FlowContext context) {
        if (expr == null) return null;
        String trimmed = expr.trim();
        if (trimmed.startsWith("${") && trimmed.endsWith("}")) {
            String ref = trimmed.substring(2, trimmed.length() - 1);
            int dot = ref.indexOf('.');
            if (dot > 0) {
                Object val = context.getNodeOutputValue(ref.substring(0, dot), ref.substring(dot + 1));
                if (val != null) return val;
            }
            Object val = context.getVariable(ref);
            return val != null ? val : expr;
        }
        return context.getVariable(trimmed, expr);
    }
}
