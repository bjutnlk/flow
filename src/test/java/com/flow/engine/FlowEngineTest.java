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

    // ---- version validation -------------------------------------------------

    @Test
    void versionMissing_throwsUnsupported() {
        String json = """
                { "id": "no-ver", "name": "X", "startNodeId": "e",
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
        UnsupportedVersionException ex = assertThrows(
                UnsupportedVersionException.class, () -> engine.execute(engine.parse(json)));
        assertTrue(ex.getMessage().contains("0.5"));
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

    // ---- recording: single save at end --------------------------------------

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
        assertNotNull(result.getExecutionId());

        ExecutionLog log = recorder.getExecutionLog(result.getExecutionId());
        assertNotNull(log);
        assertEquals(ExecutionLog.Status.SUCCESS, log.getStatus());
        assertEquals(3, log.getTotalNodes());
        assertEquals(3, log.getSuccessNodes());
        assertEquals(0, log.getFailedNodes());
        assertTrue(log.getTotalDurationMs() >= 0);
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
        assertEquals(1, nodeLogs.get(0).getStepIndex());
        assertEquals(NodeExecutionLog.Status.SUCCESS, nodeLogs.get(0).getStatus());
    }

    @Test
    void recording_savedOnceAtEnd_onFailure() {
        String json = """
                { "version": "2.0", "id": "fail", "name": "Fail", "startNodeId": "a",
                  "nodes": [{ "id": "a", "type": "task", "name": "A", "next": "missing" }] }
                """;
        FlowResult result = engine.execute(engine.parse(json));

        assertFalse(result.isSuccess());
        ExecutionLog log = recorder.getExecutionLog(result.getExecutionId());
        assertEquals(ExecutionLog.Status.FAILED, log.getStatus());
        assertTrue(log.getErrorMessage().contains("missing"));
        assertEquals(1, log.getSuccessNodes());
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
        assertEquals(NodeExecutionLog.Status.FAILED, log.getNodeExecutionLogs().get(0).getStatus());
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
        ExecutionLog log = recorder.getExecutionLog(result.getExecutionId());

        NodeExecutionLog formLog = log.getNodeExecutionLogs().get(0);
        assertNotNull(formLog.getInputSnapshot());
        assertEquals("Alice", formLog.getInputSnapshot().get("user"));
        assertNotNull(formLog.getOutputSnapshot());
        assertTrue(formLog.getOutputSnapshot().containsKey("result"));
    }

    // ---- basic flow tests ---------------------------------------------------

    @Test
    void executeOrderFlow_highAmount() {
        InputStream is = getClass().getResourceAsStream("/flows/order-flow.json");
        FlowDefinition def = engine.parse(is);
        FlowContext ctx = new FlowContext(def.getId());
        ctx.setVariable("amount", 2000);

        FlowResult result = engine.execute(def, ctx);

        assertTrue(result.isSuccess());
        assertEquals("PENDING_APPROVAL", result.getVariables().get("orderStatus"));
        assertEquals(List.of("start", "validate", "check-amount", "approve-manager", "log-result", "end"),
                result.getExecutionTrace());
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
        assertTrue(result.getErrorMessage().contains("x"));
    }

    @Test
    void customHandlerAtRuntime() {
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
    void submitForm_producesFileOutput() {
        fileStorage.seed("doc-001", "app.pdf", "application/pdf", "PDF".getBytes());

        String json = """
                { "version": "2.0", "id": "sf", "name": "SF", "startNodeId": "s",
                  "nodes": [
                    { "id": "s", "type": "start", "name": "S", "next": "f" },
                    { "id": "f", "type": "submit_form", "name": "F",
                      "inputMappings": [{ "name": "doc", "source": "file:doc-001", "dataType": "FILE" }],
                      "next": "e" },
                    { "id": "e", "type": "end", "name": "E" }
                  ] }
                """;
        FlowResult result = engine.execute(engine.parse(json));
        assertTrue(result.isSuccess());
        assertEquals("SUBMITTED", result.getVariables().get("f.result"));
    }

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
        FlowDefinition def = engine.parse(is);
        FlowContext ctx = new FlowContext(def.getId());
        ctx.setVariable("dataSource", "CRM");

        FlowResult result = engine.execute(def, ctx);
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

    // ---- fork-join ----------------------------------------------------------

    @Test
    void forkJoin_A_forks_to_B_and_D_then_C_merges() {
        String json = """
                { "version": "2.0", "id": "fj", "name": "FJ", "startNodeId": "a",
                  "nodes": [
                    { "id": "a", "type": "task", "name": "A",
                      "properties": { "origin": "a" },
                      "next": ["b", "d"] },
                    { "id": "b", "type": "task", "name": "B",
                      "properties": { "bResult": "fromB" },
                      "next": "c" },
                    { "id": "d", "type": "task", "name": "D",
                      "properties": { "dResult": "fromD" },
                      "next": "c" },
                    { "id": "c", "type": "log", "name": "C",
                      "waitFor": ["b", "d"],
                      "properties": { "message": "${bResult} + ${dResult}" },
                      "next": "e" },
                    { "id": "e", "type": "end", "name": "E" }
                  ] }
                """;
        FlowResult result = engine.execute(engine.parse(json));

        assertTrue(result.isSuccess());
        assertEquals("fromB", result.getVariables().get("bResult"));
        assertEquals("fromD", result.getVariables().get("dResult"));
        assertTrue(result.getExecutionTrace().contains("b"));
        assertTrue(result.getExecutionTrace().contains("d"));
        int cIndex = result.getExecutionTrace().indexOf("c");
        int bIndex = result.getExecutionTrace().indexOf("b");
        int dIndex = result.getExecutionTrace().indexOf("d");
        assertTrue(cIndex > bIndex, "c must come after b");
        assertTrue(cIndex > dIndex, "c must come after d");
        assertEquals("fromB + fromD", result.getVariables().get("_lastLog"));
    }

    @Test
    void forkJoin_withFileOutputs_C_reads_B_and_D() {
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
    void forkJoin_withConditionBranching() {
        String json = """
                { "version": "2.0", "id": "fj-cond", "name": "FJC", "startNodeId": "start",
                  "nodes": [
                    { "id": "start", "type": "task", "name": "Start",
                      "properties": { "val": 10 },
                      "next": ["path1", "path2"] },
                    { "id": "path1", "type": "task", "name": "P1",
                      "properties": { "p1": "done" },
                      "next": "join" },
                    { "id": "path2", "type": "task", "name": "P2",
                      "properties": { "p2": "done" },
                      "next": "join" },
                    { "id": "join", "type": "task", "name": "Join",
                      "waitFor": ["path1", "path2"],
                      "properties": { "merged": true },
                      "next": "end" },
                    { "id": "end", "type": "end", "name": "End" }
                  ] }
                """;
        FlowResult result = engine.execute(engine.parse(json));

        assertTrue(result.isSuccess());
        assertEquals("done", result.getVariables().get("p1"));
        assertEquals("done", result.getVariables().get("p2"));
        assertEquals(true, result.getVariables().get("merged"));
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

    @Test
    void switchNode_defaultFallthrough() {
        String json = """
                { "version": "2.0", "id": "sw2", "name": "Sw2", "startNodeId": "set",
                  "nodes": [
                    { "id": "set", "type": "task", "name": "Set", "properties": { "status": "UNKNOWN" }, "next": "sw" },
                    { "id": "sw", "type": "switch", "name": "Sw",
                      "properties": { "expression": "status" },
                      "branches": [{ "condition": "APPROVED", "target": "ok" }],
                      "next": "def" },
                    { "id": "ok",  "type": "task", "name": "OK",  "properties": { "path": "ok" },      "next": "end" },
                    { "id": "def", "type": "task", "name": "Def", "properties": { "path": "default" },  "next": "end" },
                    { "id": "end", "type": "end",  "name": "End" }
                  ] }
                """;
        FlowResult result = engine.execute(engine.parse(json));
        assertTrue(result.isSuccess());
        assertEquals("default", result.getVariables().get("path"));
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

    // ---- recording on complex flow ------------------------------------------

    @Test
    void recording_complexForkJoin() {
        String json = """
                { "version": "2.0", "id": "rec-fj", "name": "RecFJ", "startNodeId": "a",
                  "nodes": [
                    { "id": "a", "type": "task", "name": "A", "next": ["b", "d"] },
                    { "id": "b", "type": "task", "name": "B", "properties": {"x":1}, "next": "c" },
                    { "id": "d", "type": "task", "name": "D", "properties": {"y":2}, "next": "c" },
                    { "id": "c", "type": "task", "name": "C", "waitFor": ["b", "d"], "next": "e" },
                    { "id": "e", "type": "end",  "name": "E" }
                  ] }
                """;
        FlowResult result = engine.execute(engine.parse(json));
        assertTrue(result.isSuccess());

        ExecutionLog log = recorder.getExecutionLog(result.getExecutionId());
        assertEquals(5, log.getTotalNodes());
        assertEquals(5, log.getSuccessNodes());
        assertEquals(ExecutionLog.Status.SUCCESS, log.getStatus());
    }
}
