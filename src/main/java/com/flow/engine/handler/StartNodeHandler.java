package com.flow.engine.handler;

import com.flow.engine.model.FlowContext;
import com.flow.engine.model.FlowNode;
import com.flow.engine.model.InputMapping;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Flow start node — the entry point of every flow.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Receive flow-level input parameters via {@code inputMappings}
 *       and place them into the context as variables.</li>
 *   <li>Properties declared on the start node are also written into
 *       the context (static defaults).</li>
 * </ul>
 *
 * <p>JSON example:
 * <pre>{@code
 * {
 *   "id": "start",
 *   "type": "start",
 *   "name": "Begin",
 *   "inputMappings": [
 *     { "name": "orderId",  "source": "${orderId}",  "dataType": "STRING" },
 *     { "name": "document", "source": "file:doc-001", "dataType": "FILE" }
 *   ]
 * }
 * }</pre>
 */
@Component
public class StartNodeHandler implements NodeHandler {

    private static final Logger log = LoggerFactory.getLogger(StartNodeHandler.class);

    @Override
    public String getType() {
        return "start";
    }

    @Override
    public HandleResult execute(FlowNode node, FlowContext context) {
        log.info("[Start] Flow '{}' started at node '{}'", context.getFlowId(), node.getId());

        if (node.getProperties() != null) {
            node.getProperties().forEach(context::setVariable);
        }

        return HandleResult.none();
    }
}
