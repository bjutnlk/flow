package com.flow.engine.handler;

import com.flow.engine.model.FlowContext;
import com.flow.engine.model.FlowNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Evaluates branches defined on the node to decide which path to take.
 *
 * <p>Each branch carries a simple expression evaluated against context
 * variables.  Supported operators: {@code ==}, {@code !=}, {@code >},
 * {@code <}, {@code >=}, {@code <=}.  The left-hand side is a context
 * variable name; the right-hand side is a literal value.
 *
 * <p>The first matching branch wins; if none match, falls back to
 * {@link FlowNode#getNext()}.
 */
@Component
public class ConditionNodeHandler implements NodeHandler {

    private static final Logger log = LoggerFactory.getLogger(ConditionNodeHandler.class);

    @Override
    public String getType() {
        return "condition";
    }

    @Override
    public String handle(FlowNode node, FlowContext context) {
        log.info("[Condition] Evaluating node '{}' ({})", node.getId(), node.getName());

        List<FlowNode.Branch> branches = node.getBranches();
        if (branches == null || branches.isEmpty()) {
            log.warn("[Condition] No branches defined for node '{}'", node.getId());
            return null;
        }

        for (FlowNode.Branch branch : branches) {
            if (evaluate(branch.getCondition(), context)) {
                log.info("[Condition] Branch matched: '{}' → {}", branch.getCondition(), branch.getTargetNodeId());
                return branch.getTargetNodeId();
            }
        }

        log.info("[Condition] No branch matched, using default next");
        return null;
    }

    private boolean evaluate(String expression, FlowContext context) {
        if (expression == null || expression.isBlank()) {
            return false;
        }

        String[] operators = {"==", "!=", ">=", "<=", ">", "<"};
        for (String op : operators) {
            int idx = expression.indexOf(op);
            if (idx > 0) {
                String varName = expression.substring(0, idx).trim();
                String literal = expression.substring(idx + op.length()).trim();
                Object actualValue = context.getVariable(varName);
                return compare(actualValue, literal, op);
            }
        }

        Object flag = context.getVariable(expression.trim());
        return Boolean.TRUE.equals(flag) || "true".equalsIgnoreCase(String.valueOf(flag));
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
                // fall through to string comparison
            }
        }

        return switch (operator) {
            case "==" -> actualStr.equals(literal);
            case "!=" -> !actualStr.equals(literal);
            default   -> false;
        };
    }
}
