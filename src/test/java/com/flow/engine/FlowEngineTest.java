package com.flow.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flow.engine.exception.UnsupportedVersionException;
import com.flow.engine.handler.*;
import com.flow.engine.handler.capability.AggregateFileHandler;
import com.flow.engine.handler.capability.SubmitFormHandler;
import com.flow.engine.model.*;
import com.flow.engine.recorder.ExecutionLog;
import com.flow.engine.recorder.InMemoryExecutionRecorder;
import com.flow.engine.recorder.NodeExecutionLog;
import com.flow.engine.resolve.InputResolver;
import com.flow.engine.service.FlowEngine;
import com.flow.engine.storage.InMemoryFileStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FlowEngineTest {

    private FlowEngine engine;
    private InMemoryFileStorageService fileStorage;
    private InMemoryExecutionRecorder recorder;

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper();
        fileStorage = new InMemoryFileStorageService();
        recorder = new InMemoryExecutionRecorder();
        InputResolver resolver = new InputResolver(fileStorage);

        List<NodeHandler> handlers = List.of(
                new StartNodeHandler(),
                new EndNodeHandler(),
                new TaskNodeHandler(),
                new ConditionNodeHandler(resolver),
                new SwitchNodeHandler(resolver),
                new ForEachNodeHandler(resolver),
                new LogNodeHandler(resolver),
                new SubmitFormHandler(fileStorage),
                new AggregateFileHandler(fileStorage)
        );
        engine = new FlowEngine(mapper, resolver, recorder, handlers);
    }

    // ---- prevNodes graph resolution -----------------------------------------

    @Test
    void resolve_buildNextFromPrevNodes() {
        String json = """
                { "version": "2.0", "id": "g", "name": "G",
                  "nodes": [
                    { "id": "a", "type": "task", "name": "A" },
                    { "id": "b", "type": "task", "name": "B", "prevNodes": ["a"] },
                    { "id": "c", "type": "end",  "name": "C", "prevNodes": ["b"] }
                  ] }
                """;
        FlowDefinition def = engine.parse(json);

        assertEquals("a", def.getStartNodeId(), "start auto-detected as node with no prevNodes");

        FlowNode a = def.toNodeMap().get("a");
        assertEquals(List.of("b"), a.getNext(), "a.next computed from b.prevNodes=[a]");

        FlowNode b = def.toNodeMap().get("b");
        assertEquals(List.of("c"), b.getNext());

        FlowResult result = engine.execute(def);
        assertTrue(result.isSuccess());
        assertEquals(List.of("a", "b", "c"), result.getExecutionTrace());
    }

    @Test
    void resolve_forkDetected_fromMultiplePrevNodes() {
        String json = """
                { "version": "2.0", "id": "fork", "name": "Fork",
                  "nodes": [
                    { "id": "a", "type": "task", "name": "A" },
                    { "id": "b", "type": "task", "name": "B", "prevNodes": ["a"] },
                    { "id": "c", "type": "task", "name": "C", "prevNodes": ["a"] },
                    { "id": "d", "type": "end",  "name": "D", "prevNodes": ["b", "c"] }
                  ] }
                """;
        FlowDefinition def = engine.parse(json);

        FlowNode a = def.toNodeMap().get("a");
        assertTrue(a.getNext().contains("b"));
        assertTrue(a.getNext().contains("c"));
        assertTrue(a.isFork());

        FlowNode d = def.toNodeMap().get("d");
        assertTrue(d.isJoin());
        assertEquals(List.of("b", "c"), d.getWaitFor());
    }

    @Test
    void resolve_startNodeAutoDetected() {
        String json = """
                { "version": "2.0", "id": "auto", "name": "Auto",
                  "nodes": [
                    { "id": "x", "type": "task", "name": "X" },
                    { "id": "y", "type": "end",  "name": "Y", "prevNodes": ["x"] }
                  ] }
                """;
        FlowDefinition def = engine.parse(json);
        assertEquals("x", def.getStartNodeId());
    }

    @Test
    void resolve_explicitStartNodePreserved() {
        String json = """
                { "version": "2.0", "id": "exp", "name": "Exp", "startNodeId": "y",
                  "nodes": [
                    { "id": "x", "type": "task", "name": "X" },
                    { "id": "y", "type": "end",  "name": "Y", "prevNodes": ["x"] }
                  ] }
                """;
        FlowDefinition def = engine.parse(json);
        assertEquals("y", def.getStartNodeId(), "explicit startNodeId not overwritten");
    }

    @Test
    void resolve_explicitNextNotOverwritten() {
        String json = """
                { "version": "2.0", "id": "compat", "name": "Compat", "startNodeId": "a",
                  "nodes": [
                    { "id": "a", "type": "task", "name": "A", "next": "b" },
                    { "id": "b", "type": "end",  "name": "B" }
                  ] }
                """;
        FlowDefinition def = engine.parse(json);
        assertEquals(List.of("b"), def.toNodeMap().get("a").getNext());

        FlowResult result = engine.execute(def);
        assertTrue(result.isSuccess());
    }

    // ---- fork-join via prevNodes --------------------------------------------

    @Test
    void forkJoin_viaPrevNodes_A_B_D_C() {
        String json = """
                { "version": "2.0", "id": "fj", "name": "FJ",
                  "nodes": [
                    { "id": "a", "type": "task", "name": "A",
                      "properties": { "origin": "a" } },
                    { "id": "b", "type": "task", "name": "B",
                      "prevNodes": ["a"],
                      "properties": { "bResult": "fromB" } },
                    { "id": "d", "type": "task", "name": "D",
                      "prevNodes": ["a"],
                      "properties": { "dResult": "fromD" } },
                    { "id": "c", "type": "log", "name": "C",
                      "prevNodes": ["b", "d"],
                      "properties": { "message": "${bResult} + ${dResult}" } },
                    { "id": "e", "type": "end", "name": "E",
                      "prevNodes": ["c"] }
                  ] }
                """;
        FlowResult result = engine.execute(engine.parse(json));

        assertTrue(result.isSuccess());
        assertEquals("fromB", result.getVariables().get("bResult"));
        assertEquals("fromD", result.getVariables().get("dResult"));

        int cIdx = result.getExecutionTrace().indexOf("c");
        int bIdx = result.getExecutionTrace().indexOf("b");
        int dIdx = result.getExecutionTrace().indexOf("d");
        assertTrue(cIdx > bIdx, "c must come after b");
        assertTrue(cIdx > dIdx, "c must come after d");

        assertEquals("fromB + fromD", result.getVariables().get("_lastLog"));
    }

    @Test
    void forkJoin_withFiles_viaPrevNodes() {
        InputStream is = getClass().getResourceAsStream("/flows/fork-join-flow.json");
        assertNotNull(is);

        FlowDefinition def = engine.parse(is);
        FlowContext ctx = new FlowContext(def.getId());
        ctx.setVariable("dataSource", "API");

        FlowResult result = engine.execute(def, ctx);

        assertTrue(result.isSuccess());
        assertTrue(result.getExecutionTrace().contains("branch-b"));
        assertTrue(result.getExecutionTrace().contains("branch-d"));

        int mergeIdx = result.getExecutionTrace().indexOf("merge");
        assertTrue(mergeIdx > result.getExecutionTrace().indexOf("branch-b"));
        assertTrue(mergeIdx > result.getExecutionTrace().indexOf("branch-d"));

        assertTrue(result.getVariables().get("merge.mergedFile") instanceof FileReference);
        assertEquals(2, result.getVariables().get("merge.fileCount"));
    }

    @Test
    void forkJoin_threePaths_viaPrevNodes() {
        String json = """
                { "version": "2.0", "id": "fj3", "name": "FJ3",
                  "nodes": [
                    { "id": "root",  "type": "task", "name": "Root" },
                    { "id": "p1",    "type": "task", "name": "P1", "prevNodes": ["root"], "properties": {"p1":"done"} },
                    { "id": "p2",    "type": "task", "name": "P2", "prevNodes": ["root"], "properties": {"p2":"done"} },
                    { "id": "p3",    "type": "task", "name": "P3", "prevNodes": ["root"], "properties": {"p3":"done"} },
                    { "id": "merge", "type": "task", "name": "Merge", "prevNodes": ["p1","p2","p3"],
                      "properties": {"merged": true} },
                    { "id": "end",   "type": "end",  "name": "End", "prevNodes": ["merge"] }
                  ] }
                """;
        FlowResult result = engine.execute(engine.parse(json));

        assertTrue(result.isSuccess());
        assertEquals("done", result.getVariables().get("p1"));
        assertEquals("done", result.getVariables().get("p2"));
        assertEquals("done", result.getVariables().get("p3"));
        assertEquals(true, result.getVariables().get("merged"));
    }

    // ---- version validation -------------------------------------------------

    @Test
    void versionMissing_throwsUnsupported() {
        String json = """
                { "id": "nv", "name": "X", "startNodeId": "e",
                  "nodes": [{ "id": "e", "type": "end", "name": "E" }] }
                """;
        assertThrows(UnsupportedVersionException.class, () -> engine.execute(engine.parse(json)));
    }

    @Test
    void versionTooOld_throwsUnsupported() {
        String json = """
                { "version": "0.5", "id": "old", "name": "X", "startNodeId": "e",
                  "nodes": [{ "id": "e", "type": "end", "name": "E" }] }
                """;
        assertThrows(UnsupportedVersionException.class, () -> engine.execute(engine.parse(json)));
    }

    @Test
    void versionMinimum_isAccepted() {
        String json = """
                { "version": "1.0", "id": "min", "name": "X", "startNodeId": "e",
                  "nodes": [{ "id": "e", "type": "end", "name": "E" }] }
                """;
        assertTrue(engine.execute(engine.parse(json)).isSuccess());
    }

    @Test
    void versionCompare() {
        assertEquals(0, FlowEngine.compareVersions("1.0", "1.0"));
        assertTrue(FlowEngine.compareVersions("2.0", "1.0") > 0);
        assertTrue(FlowEngine.compareVersions("0.9", "1.0") < 0);
        assertTrue(FlowEngine.compareVersions("1.0.1", "1.0") > 0);
        assertTrue(FlowEngine.compareVersions("1.2", "1.10") < 0);
    }

    // ---- recording ----------------------------------------------------------

    @Test
    void recording_savedOnceAtEnd_onSuccess() {
        String json = """
                { "version": "2.0", "id": "rec", "name": "Rec", "startNodeId": "s",
                  "nodes": [
                    { "id": "s", "type": "start", "name": "S", "next": "t" },
                    { "id": "t", "type": "task",  "name": "T", "properties": {"x":1}, "next": "e" },
                    { "id": "e", "type": "end",   "name": "E" }
                  ] }
                """;
        FlowResult result = engine.execute(engine.parse(json));

        assertTrue(result.isSuccess());
        ExecutionLog log = recorder.getExecutionLog(result.getExecutionId());
        assertNotNull(log);
        assertEquals(ExecutionLog.Status.SUCCESS, log.getStatus());
        assertEquals(3, log.getTotalNodes());
        assertEquals(3, log.getSuccessNodes());
    }

    @Test
    void recording_capturesNodeDetails() {
        String json = """
                { "version": "2.0", "id": "det", "name": "Det", "startNodeId": "s",
                  "nodes": [
                    { "id": "s", "type": "start", "name": "Start", "next": "t" },
                    { "id": "t", "type": "task",  "name": "Work",  "properties": {"k":"v"}, "next": "e" },
                    { "id": "e", "type": "end",   "name": "End" }
                  ] }
                """;
        FlowResult result = engine.execute(engine.parse(json));
        ExecutionLog log = recorder.getExecutionLog(result.getExecutionId());

        List<NodeExecutionLog> nodeLogs = log.getNodeExecutionLogs();
        assertEquals(3, nodeLogs.size());
        assertEquals("s", nodeLogs.get(0).getNodeId());
        assertEquals("start", nodeLogs.get(0).getNodeType());
    }

    @Test
    void recording_onFailure() {
        String json = """
                { "version": "2.0", "id": "fail", "name": "Fail", "startNodeId": "a",
                  "nodes": [{ "id": "a", "type": "task", "name": "A", "next": "missing" }] }
                """;
        FlowResult result = engine.execute(engine.parse(json));

        assertFalse(result.isSuccess());
        ExecutionLog log = recorder.getExecutionLog(result.getExecutionId());
        assertEquals(ExecutionLog.Status.FAILED, log.getStatus());
    }

    @Test
    void recording_capturesNodeFailure() {
        engine.registerHandler(new NodeHandler() {
            @Override public String getType() { return "bomb"; }
            @Override public HandleResult execute(FlowNode node, FlowContext context) {
                throw new RuntimeException("boom!");
            }
        });

        String json = """
                { "version": "2.0", "id": "bomb", "name": "Bomb", "startNodeId": "b",
                  "nodes": [{ "id": "b", "type": "bomb", "name": "B" }] }
                """;
        FlowResult result = engine.execute(engine.parse(json));

        ExecutionLog log = recorder.getExecutionLog(result.getExecutionId());
        assertEquals(1, log.getFailedNodes());
        assertEquals("boom!", log.getNodeExecutionLogs().get(0).getErrorMessage());
    }

    @Test
    void recording_capturesInputOutputSnapshots() {
        fileStorage.seed("doc-001", "app.pdf", "application/pdf", "data".getBytes());

        String json = """
                { "version": "2.0", "id": "snap", "name": "Snap", "startNodeId": "form",
                  "nodes": [
                    { "id": "form", "type": "submit_form", "name": "Form",
                      "inputMappings": [
                        { "name": "user", "source": "${userName}", "dataType": "STRING" },
                        { "name": "doc",  "source": "file:doc-001", "dataType": "FILE" }
                      ], "next": "e" },
                    { "id": "e", "type": "end", "name": "E" }
                  ] }
                """;
        FlowContext ctx = new FlowContext("snap");
        ctx.setVariable("userName", "Alice");

        FlowResult result = engine.execute(engine.parse(json), ctx);
        NodeExecutionLog formLog = recorder.getExecutionLog(result.getExecutionId())
                .getNodeExecutionLogs().get(0);
        assertEquals("Alice", formLog.getInputSnapshot().get("user"));
        assertNotNull(formLog.getOutputSnapshot());
    }

    // ---- basic flow (backward compat with explicit next) --------------------

    @Test
    void executeOrderFlow_highAmount() {
        InputStream is = getClass().getResourceAsStream("/flows/order-flow.json");
        FlowDefinition def = engine.parse(is);
        FlowContext ctx = new FlowContext(def.getId());
        ctx.setVariable("amount", 2000);

        FlowResult result = engine.execute(def, ctx);
        assertTrue(result.isSuccess());
        assertEquals("PENDING_APPROVAL", result.getVariables().get("orderStatus"));
    }

    @Test
    void executeOrderFlow_lowAmount() {
        InputStream is = getClass().getResourceAsStream("/flows/order-flow.json");
        FlowDefinition def = engine.parse(is);
        FlowContext ctx = new FlowContext(def.getId());
        ctx.setVariable("amount", 500);

        FlowResult result = engine.execute(def, ctx);
        assertTrue(result.isSuccess());
        assertEquals("APPROVED", result.getVariables().get("orderStatus"));
    }

    @Test
    void simpleFlow() {
        String json = """
                { "version": "2.0", "id": "s", "name": "S", "startNodeId": "s",
                  "nodes": [
                    { "id": "s", "type": "start", "name": "S", "next": "t" },
                    { "id": "t", "type": "task",  "name": "T", "properties": { "x": 42 }, "next": "e" },
                    { "id": "e", "type": "end",   "name": "E" }
                  ] }
                """;
        FlowResult result = engine.execute(engine.parse(json));
        assertTrue(result.isSuccess());
        assertEquals(42, result.getVariables().get("x"));
    }

    @Test
    void contextSharedBetweenNodes() {
        String json = """
                { "version": "2.0", "id": "c", "name": "C", "startNodeId": "a",
                  "nodes": [
                    { "id": "a", "type": "task", "name": "A", "properties": { "greeting": "hello" }, "next": "b" },
                    { "id": "b", "type": "log",  "name": "B", "properties": { "message": "${greeting} world" }, "next": "c" },
                    { "id": "c", "type": "end",  "name": "C" }
                  ] }
                """;
        FlowResult result = engine.execute(engine.parse(json));
        assertTrue(result.isSuccess());
        assertEquals("hello world", result.getVariables().get("_lastLog"));
    }

    @Test
    void missingNodeReturnsFailure() {
        String json = """
                { "version": "2.0", "id": "b", "name": "B", "startNodeId": "a",
                  "nodes": [{ "id": "a", "type": "task", "name": "A", "next": "x" }] }
                """;
        FlowResult result = engine.execute(engine.parse(json));
        assertFalse(result.isSuccess());
    }

    @Test
    void customHandler() {
        engine.registerHandler(new NodeHandler() {
            @Override public String getType() { return "custom"; }
            @Override public HandleResult execute(FlowNode node, FlowContext context) {
                context.setVariable("customRan", true);
                return HandleResult.none();
            }
        });

        String json = """
                { "version": "2.0", "id": "cu", "name": "Cu", "startNodeId": "c",
                  "nodes": [
                    { "id": "c", "type": "custom", "name": "C", "next": "e" },
                    { "id": "e", "type": "end",    "name": "E" }
                  ] }
                """;
        FlowResult result = engine.execute(engine.parse(json));
        assertTrue(result.isSuccess());
        assertEquals(true, result.getVariables().get("customRan"));
    }

    // ---- file and cross-node ------------------------------------------------

    @Test
    void crossNodeReference() {
        fileStorage.seed("seed", "d.txt", "text/plain", "data".getBytes());

        String json = """
                { "version": "2.0", "id": "xr", "name": "XR", "startNodeId": "a",
                  "nodes": [
                    { "id": "a", "type": "submit_form", "name": "A",
                      "inputMappings": [{ "name": "in", "source": "file:seed", "dataType": "FILE" }],
                      "next": "b" },
                    { "id": "b", "type": "aggregate_file", "name": "B",
                      "inputMappings": [{ "name": "f", "source": "${a.receiptFile}", "dataType": "FILE" }],
                      "next": "e" },
                    { "id": "e", "type": "end", "name": "E" }
                  ] }
                """;
        FlowResult result = engine.execute(engine.parse(json));
        assertTrue(result.isSuccess());
        assertTrue(result.getVariables().get("b.mergedFile") instanceof FileReference);
    }

    // ---- consecutive capability nodes ---------------------------------------

    @Test
    void consecutiveCapabilityNodes_pipeline() {
        fileStorage.seed("raw-data-001", "raw.csv", "text/csv", "1,Alice".getBytes());
        InputStream is = getClass().getResourceAsStream("/flows/pipeline-flow.json");
        FlowContext ctx = new FlowContext("pipeline-flow");
        ctx.setVariable("dataSource", "CRM");

        FlowResult result = engine.execute(engine.parse(is), ctx);
        assertTrue(result.isSuccess());
        assertEquals(List.of("ingest", "transform", "validate", "export", "end"),
                result.getExecutionTrace());
    }

    @Test
    void capabilityNodes_noStartOrEnd() {
        String json = """
                { "version": "2.0", "id": "bare", "name": "Bare", "startNodeId": "a",
                  "nodes": [
                    { "id": "a", "type": "submit_form", "name": "A",
                      "inputMappings": [{ "name": "f1", "source": "v1", "dataType": "STRING" }],
                      "next": "b" },
                    { "id": "b", "type": "submit_form", "name": "B",
                      "inputMappings": [{ "name": "prev", "source": "${a.receiptFile}", "dataType": "FILE" }],
                      "next": "c" },
                    { "id": "c", "type": "submit_form", "name": "C",
                      "inputMappings": [
                        { "name": "fromA", "source": "${a.result}", "dataType": "STRING" },
                        { "name": "fromB", "source": "${b.result}", "dataType": "STRING" }
                      ] }
                  ] }
                """;
        FlowResult result = engine.execute(engine.parse(json));
        assertTrue(result.isSuccess());
        assertEquals(List.of("a", "b", "c"), result.getExecutionTrace());
    }

    // ---- switch node --------------------------------------------------------

    @Test
    void switchNode_matchesCase() {
        String json = """
                { "version": "2.0", "id": "sw", "name": "Sw", "startNodeId": "set",
                  "nodes": [
                    { "id": "set", "type": "task", "name": "Set", "properties": { "status": "REJECTED" }, "next": "sw" },
                    { "id": "sw", "type": "switch", "name": "Sw",
                      "properties": { "expression": "status" },
                      "branches": [
                        { "condition": "APPROVED", "target": "ok" },
                        { "condition": "REJECTED", "target": "fail" }
                      ], "next": "ok" },
                    { "id": "ok",   "type": "task", "name": "OK",   "properties": { "path": "approved" }, "next": "end" },
                    { "id": "fail", "type": "task", "name": "Fail", "properties": { "path": "rejected" }, "next": "end" },
                    { "id": "end",  "type": "end",  "name": "End" }
                  ] }
                """;
        FlowResult result = engine.execute(engine.parse(json));
        assertTrue(result.isSuccess());
        assertEquals("rejected", result.getVariables().get("path"));
    }

    // ---- foreach node -------------------------------------------------------

    @Test
    void forEachNode_iterates() {
        String json = """
                { "version": "2.0", "id": "fe", "name": "FE", "startNodeId": "setup",
                  "nodes": [
                    { "id": "setup", "type": "task", "name": "Setup", "next": "loop" },
                    { "id": "loop", "type": "foreach", "name": "Loop",
                      "properties": { "collection": "items", "itemVar": "cur", "indexVar": "idx" },
                      "next": "end" },
                    { "id": "end", "type": "end", "name": "End" }
                  ] }
                """;
        FlowContext ctx = new FlowContext("fe");
        ctx.setVariable("items", List.of("a", "b", "c"));

        FlowResult result = engine.execute(engine.parse(json), ctx);
        assertTrue(result.isSuccess());
        assertEquals(3, result.getVariables().get("loop.count"));
    }

    // ---- document flow ------------------------------------------------------

    @Test
    void documentFlow_lowAmount() {
        fileStorage.seed("doc-001", "app.pdf", "application/pdf", "PDF".getBytes());
        InputStream is = getClass().getResourceAsStream("/flows/document-flow.json");
        FlowContext ctx = new FlowContext("document-flow");
        ctx.setVariable("applicantName", "Bob");
        ctx.setVariable("amount", 5000);

        FlowResult result = engine.execute(engine.parse(is), ctx);
        assertTrue(result.isSuccess());
    }

    @Test
    void documentFlow_highAmount() {
        fileStorage.seed("doc-001", "app.pdf", "application/pdf", "PDF".getBytes());
        InputStream is = getClass().getResourceAsStream("/flows/document-flow.json");
        FlowContext ctx = new FlowContext("document-flow");
        ctx.setVariable("applicantName", "Carol");
        ctx.setVariable("amount", 20000);

        FlowResult result = engine.execute(engine.parse(is), ctx);
        assertTrue(result.isSuccess());
    }

    // ---- recording on fork-join via prevNodes --------------------------------

    @Test
    void recording_forkJoinViaPrevNodes() {
        String json = """
                { "version": "2.0", "id": "rec-fj", "name": "RecFJ",
                  "nodes": [
                    { "id": "a", "type": "task", "name": "A" },
                    { "id": "b", "type": "task", "name": "B", "prevNodes": ["a"], "properties": {"x":1} },
                    { "id": "d", "type": "task", "name": "D", "prevNodes": ["a"], "properties": {"y":2} },
                    { "id": "c", "type": "task", "name": "C", "prevNodes": ["b", "d"] },
                    { "id": "e", "type": "end",  "name": "E", "prevNodes": ["c"] }
                  ] }
                """;
        FlowResult result = engine.execute(engine.parse(json));
        assertTrue(result.isSuccess());

        ExecutionLog log = recorder.getExecutionLog(result.getExecutionId());
        assertEquals(5, log.getTotalNodes());
        assertEquals(5, log.getSuccessNodes());
    }
}
