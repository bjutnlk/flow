package com.flow.engine.handler;

import com.flow.engine.model.FlowContext;
import com.flow.engine.model.FlowNode;
import com.flow.engine.resolve.InputResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Switch/judgment flow node — evaluates an expression against branches
 * and routes to the matching target.
 *
 * <h3>Supported operators in branch conditions</h3>
 * <ul>
 *   <li>{@code ==} — equals (string or numeric)</li>
 *   <li>{@code !=} — not equals</li>
 *   <li>{@code >}  — greater than (numeric)</li>
 *   <li>{@code <}  — less than (numeric)</li>
 *   <li>{@code >=} — greater than or equal (numeric)</li>
 *   <li>{@code <=} — less than or equal (numeric)</li>
 * </ul>
 *
 * <h3>Branch condition format</h3>
 * <p>Each branch condition is either:
 * <ul>
 *   <li>An exact-match value: {@code "APPROVED"} — compared to the
 *       expression result via {@code ==}</li>
 *   <li>A comparison expression: {@code "> 100"}, {@code "== APPROVED"},
 *       {@code "<= 50.5"} — the operator is parsed from the condition
 *       and applied against the resolved expression value.</li>
 * </ul>
 *
 * <h3>JSON example</h3>
 * <pre>{@code
 * {
 *   "type": "switch",
 *   "properties": { "expression": "amount" },
 *   "branches": [
 *     { "condition": "> 1000",   "target": "high-value" },
 *     { "condition": "<= 1000",  "target": "normal" },
 *     { "condition": "== VIP",   "target": "vip-path" }
 *   ],
 *   "next": "default-path"
 * }
 * }</pre>
 */
@Component
public class SwitchNodeHandler implements NodeHandler {

    private static final Logger log = LoggerFactory.getLogger(SwitchNodeHandler.class);
    private static final String[] OPERATORS = {"==", "!=", ">=", "<=", ">", "<"};

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
        log.info("[Switch] Node '{}': '{}' = '{}'", node.getId(), expression, value);

        List<FlowNode.Branch> branches = node.getBranches();
        if (branches == null || value == null) {
            return HandleResult.none();
        }

        for (FlowNode.Branch branch : branches) {
            if (matchCondition(value, resolved, branch.getCondition())) {
                log.info("[Switch] Matched: '{}' → {}", branch.getCondition(), branch.getTargetNodeId());
                return HandleResult.routeTo(branch.getTargetNodeId());
            }
        }

        log.info("[Switch] No branch matched, using default next");
        return HandleResult.none();
    }

    private boolean matchCondition(String valueStr, Object valueObj, String condition) {
        if (condition == null || condition.isBlank()) {
            return false;
        }

        String trimmed = condition.trim();

        for (String op : OPERATORS) {
            if (trimmed.startsWith(op)) {
                String operand = trimmed.substring(op.length()).trim();
                return compare(valueStr, valueObj, op, operand);
            }
        }

        return valueStr.equals(trimmed);
    }

    private boolean compare(String valueStr, Object valueObj, String op, String operand) {
        if (isNumeric(valueObj) && isNumericString(operand)) {
            double a = toDouble(valueObj);
            double b = Double.parseDouble(operand);
            return switch (op) {
                case "==" -> a == b;
                case "!=" -> a != b;
                case ">"  -> a > b;
                case "<"  -> a < b;
                case ">=" -> a >= b;
                case "<=" -> a <= b;
                default   -> false;
            };
        }

        return switch (op) {
            case "==" -> valueStr.equals(operand);
            case "!=" -> !valueStr.equals(operand);
            default   -> false;
        };
    }

    private boolean isNumeric(Object obj) {
        if (obj instanceof Number) return true;
        if (obj instanceof String s) return isNumericString(s);
        return false;
    }

    private boolean isNumericString(String s) {
        try {
            Double.parseDouble(s);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private double toDouble(Object obj) {
        if (obj instanceof Number n) return n.doubleValue();
        return Double.parseDouble(String.valueOf(obj));
    }
}
