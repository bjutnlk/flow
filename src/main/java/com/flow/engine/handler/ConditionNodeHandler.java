package com.flow.engine.handler;

import com.flow.engine.model.FlowContext;
import com.flow.engine.model.FlowNode;
import com.flow.engine.model.NodeOutput;
import com.flow.engine.resolve.InputResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Evaluates branches defined on the node to decide which path to take.
 *
 * <p>Expression left-hand side supports both flat context variables
 * ({@code amount}) and node output references ({@code ${nodeA.status}}).
 * Supported operators: {@code ==}, {@code !=}, {@code >}, {@code <},
 * {@code >=}, {@code <=}.
 *
 * <p>The first matching branch wins; if none match, falls back to
 * {@link FlowNode#getNext()}.
 */
@Component
public class ConditionNodeHandler implements NodeHandler {

    private static final Logger log = LoggerFactory.getLogger(ConditionNodeHandler.class);

    private final InputResolver inputResolver;

    public ConditionNodeHandler(InputResolver inputResolver) {
        this.inputResolver = inputResolver;
    }

    @Override
    public String getType() {
        return "condition";
    }

    @Override
    public HandleResult execute(FlowNode node, FlowContext context) {
        log.info("[Condition] Evaluating node '{}' ({})", node.getId(), node.getName());

        List<FlowNode.Branch> branches = node.getBranches();
        if (branches == null || branches.isEmpty()) {
            log.warn("[Condition] No branches defined for node '{}'", node.getId());
            return HandleResult.none();
        }

        for (FlowNode.Branch branch : branches) {
            if (evaluate(branch.getCondition(), context)) {
                log.info("[Condition] Branch matched: '{}' → {}", branch.getCondition(), branch.getTargetNodeId());
                return HandleResult.routeTo(branch.getTargetNodeId());
            }
        }

        log.info("[Condition] No branch matched, using default next");
        return HandleResult.none();
    }

    private boolean evaluate(String expression, FlowContext context) {
        if (expression == null || expression.isBlank()) {
            return false;
        }

        String[] operators = {"==", "!=", ">=", "<=", ">", "<"};
        for (String op : operators) {
            int idx = expression.indexOf(op);
            if (idx > 0) {
                String lhs = expression.substring(0, idx).trim();
                String rhs = expression.substring(idx + op.length()).trim();
                Object actualValue = resolveValue(lhs, context);
                return compare(actualValue, rhs, op);
            }
        }

        Object flag = resolveValue(expression.trim(), context);
        return Boolean.TRUE.equals(flag) || "true".equalsIgnoreCase(String.valueOf(flag));
    }

    /**
     * Resolve the left-hand side of an expression.  Supports dotted
     * references like {@code nodeA.status} (node output) and simple
     * variable names like {@code amount}.
     */
    private Object resolveValue(String ref, FlowContext context) {
        if (ref.contains(".")) {
            Object resolved = inputResolver.resolveSource("${" + ref + "}", null, context);
            if (resolved != null) {
                return resolved;
            }
        }
        return context.getVariable(ref);
    }

    private boolean compare(Object actual, String literal, String operator) {
        if (actual == null) {
            return "!=".equals(operator);
        }

        String actualStr = String.valueOf(actual);

        if (actual instanceof Number) {
            try {
                double a = ((Number) actual).doubleValue();
                double b = Double.parseDouble(literal);
                return switch (operator) {
                    case "==" -> a == b;
                    case "!=" -> a != b;
                    case ">"  -> a > b;
                    case "<"  -> a < b;
                    case ">=" -> a >= b;
                    case "<=" -> a <= b;
                    default   -> false;
                };
            } catch (NumberFormatException ignored) {
            }
        }

        return switch (operator) {
            case "==" -> actualStr.equals(literal);
            case "!=" -> !actualStr.equals(literal);
            default   -> false;
        };
    }
}
