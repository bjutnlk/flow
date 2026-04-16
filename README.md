# Flow Engine

A lightweight, JSON-driven flow execution engine built on Spring Boot. Define processes as JSON node graphs, execute them sequentially with a shared context, and extend with custom handlers.

## Core Design Principle

**All nodes are equal.** The engine's execution loop treats every node identically:

```
resolve inputs → execute handler → store output → route to next
```

There is no hard split between "flow nodes" and "business nodes". Whether a handler branches (condition), iterates (foreach), uploads a file (submit_form), or does nothing (start) — that is purely the handler's internal concern. The engine doesn't know or care.

This means any sequence like `A → B → C → D` works naturally, regardless of what type each node is. You don't need control-flow nodes between capability nodes.

## Architecture

```
JSON Flow Definition
        │
        ▼
┌────────────────────────────────────────────────────────┐
│                     FlowEngine                         │
│                   (@Service bean)                      │
│                                                        │
│  for each node:                                        │
│  ┌──────────────────────────────────────────────────┐  │
│  │ 1. InputResolver                                 │  │
│  │    ${nodeA.resultFile} → FileReference            │  │
│  │    file:abc-123        → FileReference (cloud)    │  │
│  │    ${varName}          → context variable          │  │
│  │    literal             → as-is                     │  │
│  ├──────────────────────────────────────────────────┤  │
│  │ 2. handler.execute(node, context)                │  │
│  │    → HandleResult { output?, nextNodeId? }       │  │
│  ├──────────────────────────────────────────────────┤  │
│  │ 3. Store output under nodeId                     │  │
│  │ 4. Route: explicit override → node.next → stop   │  │
│  └──────────────────────────────────────────────────┘  │
│                                                        │
│  FlowContext                                           │
│  ├── variables: flat key-value pairs                   │
│  ├── nodeOutputs: { nodeId → NodeOutput }              │
│  └── resolvedInputs: current node's resolved inputs    │
└────────────────────────────────────────────────────────┘
```

## Handler Interface

Every handler implements a single method:

```java
public interface NodeHandler {
    String getType();
    HandleResult execute(FlowNode node, FlowContext context);
}
```

`HandleResult` carries:
- **output** (optional) — a typed `NodeOutput` stored for downstream `${nodeId.field}` references
- **nextNodeId** (optional) — routing override; if null, engine follows `node.next`

Any handler can produce output. Any handler can influence routing. There is no forced inheritance hierarchy.

## Built-in Handlers

### Generic (control-flow)

| Type | Behavior |
|------|----------|
| `start` | Entry point, no-op |
| `end` | Terminates the flow |
| `condition` | Evaluates `branches` with operators (`==`, `!=`, `>`, `<`, `>=`, `<=`); supports `${nodeId.field}` in expressions |
| `switch` | Matches a context value against case branches (exact match) |
| `foreach` | Iterates over a list, exposes `item`/`index` vars; outputs `count` and `items` |
| `task` | Copies properties into context variables |
| `log` | Logs a message with `${var}` / `${nodeId.field}` interpolation |

### Capability (business-logic examples)

| Type | Behavior |
|------|----------|
| `submit_form` | Collects inputs as form fields → outputs `result` (STRING) + `receiptFile` (FILE) |
| `aggregate_file` | Merges input files → outputs `mergedFile` (FILE) + `fileCount` (NUMBER) |

## Consecutive Capability Nodes

No flow nodes required between capability nodes. This works:

```json
{
  "startNodeId": "ingest",
  "nodes": [
    {
      "id": "ingest", "type": "submit_form",
      "inputMappings": [{ "name": "rawFile", "source": "file:raw-001", "dataType": "FILE" }],
      "next": "transform"
    },
    {
      "id": "transform", "type": "submit_form",
      "inputMappings": [{ "name": "input", "source": "${ingest.receiptFile}", "dataType": "FILE" }],
      "next": "validate"
    },
    {
      "id": "validate", "type": "submit_form",
      "inputMappings": [{ "name": "data", "source": "${transform.receiptFile}", "dataType": "FILE" }],
      "next": "export"
    },
    {
      "id": "export", "type": "aggregate_file",
      "inputMappings": [
        { "name": "a", "source": "${validate.receiptFile}", "dataType": "FILE" },
        { "name": "b", "source": "${transform.receiptFile}", "dataType": "FILE" }
      ]
    }
  ]
}
```

Trace: `ingest → transform → validate → export` — all capability, no flow nodes.

## Cross-Node Output References

Any node's output is accessible to all downstream nodes via `${nodeId.outputField}`:

```json
{
  "id": "c", "type": "aggregate_file",
  "inputMappings": [
    { "name": "fileFromA", "source": "${a.receiptFile}", "dataType": "FILE" },
    { "name": "statusFromB", "source": "${b.result}", "dataType": "STRING" }
  ]
}
```

## Input Source Formats

| Format | Example | Resolves to |
|--------|---------|-------------|
| `${nodeId.field}` | `${submit.receiptFile}` | Output field from a prior node |
| `file:xxx` | `file:doc-001` | Cloud storage file → `FileReference` |
| `${varName}` | `${applicantName}` | Context variable |
| literal | `hello` | Used as-is |

## Adding Custom Handlers

```java
@Component
public class EmailHandler implements NodeHandler {

    @Override
    public String getType() { return "email"; }

    @Override
    public HandleResult execute(FlowNode node, FlowContext context) {
        String to = (String) context.getResolvedInput("to");
        String body = (String) context.getResolvedInput("body");
        // send email ...
        NodeOutput out = NodeOutput.single("sent", DataType.BOOLEAN, true);
        return HandleResult.output(out);
    }
}
```

The engine auto-discovers all `NodeHandler` beans via Spring DI.

## Build & Test

```bash
mvn clean test
```

Requires Java 17+.
