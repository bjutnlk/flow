package com.flow.engine.handler;

import com.flow.engine.model.FlowContext;
import com.flow.engine.model.FlowNode;
import com.flow.engine.resolve.InputResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Switch/case branching — matches a context value against exact-match branches.
 *
 * <p>Configuration:
 * <ul>
 *   <li>{@code properties.expression} — the variable or reference to switch
 *       on (e.g. {@code "status"} or {@code "${submit.result}"})</li>
 *   <li>{@code branches[].condition} — the case value to match</li>
 *   <li>{@code branches[].target} — the node to jump to on match</li>
 *   <li>{@code next} — the default / fallthrough target</li>
 * </ul>
 *
 * <pre>{@code
 * {
 *   "type": "switch",
 *   "properties": { "expression": "status" },
 *   "branches": [
 *     { "condition": "APPROVED",  "target": "ship" },
 *     { "condition": "REJECTED",  "target": "notify-reject" }
 *   ],
 *   "next": "manual-review"
 * }
 * }</pre>
 */
@Component
public class SwitchNodeHandler implements NodeHandler {

    private static final Logger log = LoggerFactory.getLogger(SwitchNodeHandler.class);

    private final InputResolver inputResolver;

    public SwitchNodeHandler(InputResolver inputResolver) {
        this.inputResolver = inputResolver;
    }

    @Override
    public String getType() {
        return "switch";
    }

    @Override
    public HandleResult execute(FlowNode node, FlowContext context) {
        String expression = (String) node.getProperties().get("expression");
        if (expression == null) {
            log.warn("[Switch] No expression defined for node '{}'", node.getId());
            return HandleResult.none();
        }

        Object resolved = inputResolver.resolveSource("${" + expression + "}", null, context);
        if (resolved == null) {
            resolved = inputResolver.resolveSource(expression, null, context);
        }
        String value = resolved != null ? String.valueOf(resolved) : null;
        log.info("[Switch] Node '{}': expression '{}' resolved to '{}'", node.getId(), expression, value);

        List<FlowNode.Branch> branches = node.getBranches();
        if (branches != null && value != null) {
            for (FlowNode.Branch branch : branches) {
                if (value.equals(branch.getCondition())) {
                    log.info("[Switch] Case matched: '{}' → {}", branch.getCondition(), branch.getTargetNodeId());
                    return HandleResult.routeTo(branch.getTargetNodeId());
                }
            }
        }

        log.info("[Switch] No case matched, using default next");
        return HandleResult.none();
    }
}
