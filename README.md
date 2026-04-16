# Flow Engine

A lightweight, JSON-driven flow execution engine built on Spring Boot. Define business processes as JSON, execute them node-by-node through a shared context, and extend behavior with custom handlers.

## Architecture

```
┌─────────────────────────────────────────────────────┐
│                    FlowEngine                       │
│                  (@Service bean)                    │
│                                                     │
│  ┌──────────┐   parse JSON    ┌────────────────┐   │
│  │  JSON    │ ──────────────► │ FlowDefinition │   │
│  └──────────┘                 │   └─ FlowNode  │   │
│                               │   └─ FlowNode  │   │
│                               └────────┬───────┘   │
│                                        │            │
│  ┌──────────┐   execute()              ▼            │
│  │ FlowCtx  │◄────────────── node-by-node walk     │
│  │ (shared  │                          │            │
│  │  vars)   │                          ▼            │
│  └──────────┘               ┌──────────────────┐   │
│                             │  NodeHandler     │   │
│                             │  registry        │   │
│                             │  (type → bean)   │   │
│                             └──────────────────┘   │
│                                                     │
│  Result: FlowResult { success, variables, trace }   │
└─────────────────────────────────────────────────────┘
```

## Core Concepts

| Concept | Description |
|---------|-------------|
| **FlowDefinition** | Top-level object parsed from JSON; contains nodes and a start-node reference |
| **FlowNode** | A single step — has an `id`, `type`, `properties`, optional `next` and `branches` |
| **FlowContext** | Shared variable bag passed through every node; nodes read/write variables here |
| **FlowEngine** | Spring `@Service` bean — parses JSON, resolves handlers, walks the node graph |
| **NodeHandler** | Strategy interface — one implementation per node type (`task`, `condition`, `log`, …) |
| **FlowResult** | Immutable outcome — success/failure, final variables snapshot, execution trace |

## Built-in Node Types

| Type | Handler | Behavior |
|------|---------|----------|
| `start` | `StartNodeHandler` | No-op entry point, logs flow start |
| `end` | `EndNodeHandler` | Terminates the flow |
| `task` | `TaskNodeHandler` | Copies all `properties` into context as variables |
| `condition` | `ConditionNodeHandler` | Evaluates branches (`==`, `!=`, `>`, `<`, `>=`, `<=`) against context; routes to matching target |
| `log` | `LogNodeHandler` | Logs a message with `${var}` interpolation from context |

## JSON Flow Format

```json
{
  "id": "order-flow",
  "name": "Order Processing",
  "startNodeId": "start",
  "nodes": [
    { "id": "start", "type": "start", "name": "Begin", "next": "validate" },
    { "id": "validate", "type": "task", "name": "Validate",
      "properties": { "orderStatus": "VALIDATED" }, "next": "check" },
    { "id": "check", "type": "condition", "name": "Check Amount",
      "branches": [
        { "condition": "amount > 1000", "target": "manager" },
        { "condition": "amount <= 1000", "target": "auto" }
      ]
    },
    { "id": "manager", "type": "task", "name": "Manager Approval",
      "properties": { "approver": "manager" }, "next": "end" },
    { "id": "auto", "type": "task", "name": "Auto Approve",
      "properties": { "approver": "system" }, "next": "end" },
    { "id": "end", "type": "end", "name": "Done" }
  ]
}
```

## Usage

```java
@Service
public class OrderService {

    private final FlowEngine flowEngine;

    public OrderService(FlowEngine flowEngine) {
        this.flowEngine = flowEngine;
    }

    public void processOrder(Order order) {
        // 1. Parse flow from JSON (string, classpath stream, etc.)
        FlowDefinition flow = flowEngine.parse(
            getClass().getResourceAsStream("/flows/order-flow.json"));

        // 2. Create context with shared variables
        FlowContext ctx = new FlowContext(flow.getId());
        ctx.setVariable("amount", order.getAmount());
        ctx.setVariable("orderId", order.getId());

        // 3. Execute
        FlowResult result = flowEngine.execute(flow, ctx);

        // 4. Inspect result
        if (result.isSuccess()) {
            String status = (String) result.getVariables().get("orderStatus");
            System.out.println("Done: " + status);
            System.out.println("Trace: " + result.getExecutionTrace());
        }
    }
}
```

## Extending with Custom Handlers

Implement `NodeHandler` and register it as a Spring `@Component`:

```java
@Component
public class EmailNodeHandler implements NodeHandler {

    @Override
    public String getType() {
        return "email";
    }

    @Override
    public String handle(FlowNode node, FlowContext context) {
        String to = (String) node.getProperties().get("to");
        String body = (String) node.getProperties().get("body");
        // send email ...
        context.setVariable("emailSent", true);
        return null; // follow default next
    }
}
```

The engine auto-discovers all `NodeHandler` beans via Spring DI.

## Build & Test

```bash
mvn clean test
```

Requires Java 17+.
