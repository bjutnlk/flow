package com.flow.engine;

import com.flow.engine.exception.FlowValidationException;
import com.flow.engine.handler.*;
import com.flow.engine.model.*;
import com.flow.engine.recorder.ExecutionLog;
import com.flow.engine.recorder.ExecutionRecorder;
import com.flow.engine.recorder.NodeExecutionLog;
import com.flow.engine.service.FlowEngine;
import com.flow.engine.storage.FileStorageService;
import com.flow.engine.storage.InMemoryFileStorageService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Spring Boot integration test — all beans auto-wired by the container,
 * no manual setUp needed.
 *
 * <p>At startup Spring auto-discovers and injects:
 * <ul>
 *   <li>{@code FlowEngine} — the core service</li>
 *   <li>{@code StartNodeHandler}, {@code EndNodeHandler},
 *       {@code SwitchNodeHandler} — built-in flow node handlers
 *       (via {@code List<NodeHandler>} constructor param)</li>
 *   <li>{@code InputResolver} — expression resolver</li>
 *   <li>{@code FlowValidator} — pre-execution validation</li>
 *   <li>{@code InMemoryFileStorageService} — file storage fallback</li>
 *   <li>{@code InMemoryExecutionRecorder} — execution recorder fallback</li>
 *   <li>{@code ObjectMapper} — from spring-boot-starter-json</li>
 * </ul>
 */
@SpringBootTest
class FlowEngineTest {

    @Autowired
    private FlowEngine engine;

    @Autowired
    private ExecutionRecorder recorder;

    @Autowired
    private FileStorageService fileStorageService;

    // ======================================================================
    // Start node: input parameters
    // ======================================================================

    @Test
    void startNode_setsInputParamsIntoContext() {
        String json = """
                { "version": "2.0", "id": "si", "name": "SI",
                  "nodes": [
                    { "id": "s", "type": "start", "name": "Start",
                      "inputMappings": [
                        { "name": "userId", "source": "${userId}", "dataType": "STRING" },
                        { "name": "count",  "source": "${count}",  "dataType": "NUMBER" }
                      ] },
                    { "id": "e", "type": "end", "name": "End", "prevNodes": ["s"] }
                  ] }
                """;
        FlowContext ctx = new FlowContext("si");
        ctx.setVariable("userId", "U-100");
        ctx.setVariable("count", 5);

        FlowResult result = engine.execute(engine.parse(json), ctx);
        assertTrue(result.isSuccess());
        assertEquals("U-100", result.getVariables().get("userId"));
        assertEquals(5, result.getVariables().get("count"));
    }

    @Test
    void startNode_withStaticProperties() {
        String json = """
                { "version": "2.0", "id": "sp", "name": "SP",
                  "nodes": [
                    { "id": "s", "type": "start", "name": "Start",
                      "properties": { "env": "production", "retryLimit": 3 } },
                    { "id": "e", "type": "end", "name": "End", "prevNodes": ["s"] }
                  ] }
                """;
        FlowResult result = engine.execute(engine.parse(json));
        assertTrue(result.isSuccess());
        assertEquals("production", result.getVariables().get("env"));
        assertEquals(3, result.getVariables().get("retryLimit"));
    }

    // ======================================================================
    // End node: return values
    // ======================================================================

    @Test
    void endNode_collectsReturnValues() {
        engine.registerHandler(capabilityHandler("process"));

        String json = """
                { "version": "2.0", "id": "rv", "name": "RV",
                  "nodes": [
                    { "id": "s", "type": "start", "name": "Start", "properties": { "status": "OK" } },
                    { "id": "p", "type": "process", "name": "Process", "prevNodes": ["s"],
                      "inputMappings": [{ "name": "data", "source": "${status}", "dataType": "STRING" }] },
                    { "id": "e", "type": "end", "name": "End", "prevNodes": ["p"],
                      "properties": {
                        "returnValues": {
                          "processResult": "${p.result}",
                          "originalStatus": "${status}"
                        }
                      } }
                  ] }
                """;
        FlowResult result = engine.execute(engine.parse(json));

        assertTrue(result.isSuccess());
        assertEquals("DONE", result.getReturnValues().get("processResult"));
        assertEquals("OK", result.getReturnValues().get("originalStatus"));
    }

    @Test
    void endNode_noReturnValues_emptyMap() {
        String json = """
                { "version": "2.0", "id": "nr", "name": "NR",
                  "nodes": [
                    { "id": "s", "type": "start", "name": "S" },
                    { "id": "e", "type": "end",   "name": "E", "prevNodes": ["s"] }
                  ] }
                """;
        FlowResult result = engine.execute(engine.parse(json));
        assertTrue(result.isSuccess());
        assertTrue(result.getReturnValues().isEmpty());
    }

    // ======================================================================
    // Switch node: comparison operators
    // ======================================================================

    @Test
    void switch_exactMatch() {
        String json = """
                { "version": "2.0", "id": "sem", "name": "SEM", "startNodeId": "s",
                  "nodes": [
                    { "id": "s", "type": "start", "name": "S", "properties": { "status": "REJECTED" }, "next": "sw" },
                    { "id": "sw", "type": "switch", "name": "Sw",
                      "properties": { "expression": "status" },
                      "branches": [
                        { "condition": "APPROVED", "target": "ok" },
                        { "condition": "REJECTED", "target": "fail" }
                      ], "next": "ok" },
                    { "id": "ok",   "type": "start", "name": "OK",   "properties": { "path": "approved" }, "next": "e" },
                    { "id": "fail", "type": "start", "name": "Fail", "properties": { "path": "rejected" }, "next": "e" },
                    { "id": "e", "type": "end", "name": "E",
                      "properties": { "returnValues": { "path": "${path}" } } }
                  ] }
                """;
        FlowResult result = engine.execute(engine.parse(json));
        assertTrue(result.isSuccess());
        assertEquals("rejected", result.getReturnValues().get("path"));
    }

    @Test
    void switch_greaterThan() {
        String json = """
                { "version": "2.0", "id": "sgt", "name": "SGT", "startNodeId": "s",
                  "nodes": [
                    { "id": "s", "type": "start", "name": "S", "next": "sw" },
                    { "id": "sw", "type": "switch", "name": "Sw",
                      "properties": { "expression": "amount" },
                      "branches": [
                        { "condition": "> 1000",  "target": "high" },
                        { "condition": "<= 1000", "target": "low" }
                      ] },
                    { "id": "high", "type": "start", "name": "H", "properties": { "tier": "HIGH" }, "next": "e" },
                    { "id": "low",  "type": "start", "name": "L", "properties": { "tier": "LOW" },  "next": "e" },
                    { "id": "e", "type": "end", "name": "E",
                      "properties": { "returnValues": { "tier": "${tier}" } } }
                  ] }
                """;
        FlowContext ctx = new FlowContext("sgt");
        ctx.setVariable("amount", 2000);

        FlowResult result = engine.execute(engine.parse(json), ctx);
        assertTrue(result.isSuccess());
        assertEquals("HIGH", result.getReturnValues().get("tier"));
    }

    @Test
    void switch_lessThanOrEqual() {
        String json = """
                { "version": "2.0", "id": "sle", "name": "SLE", "startNodeId": "s",
                  "nodes": [
                    { "id": "s", "type": "start", "name": "S", "next": "sw" },
                    { "id": "sw", "type": "switch", "name": "Sw",
                      "properties": { "expression": "score" },
                      "branches": [
                        { "condition": ">= 90", "target": "a" },
                        { "condition": ">= 60", "target": "b" },
                        { "condition": "< 60",  "target": "c" }
                      ] },
                    { "id": "a", "type": "start", "name": "A", "properties": { "grade": "A" }, "next": "e" },
                    { "id": "b", "type": "start", "name": "B", "properties": { "grade": "B" }, "next": "e" },
                    { "id": "c", "type": "start", "name": "C", "properties": { "grade": "C" }, "next": "e" },
                    { "id": "e", "type": "end", "name": "E",
                      "properties": { "returnValues": { "grade": "${grade}" } } }
                  ] }
                """;
        FlowContext ctx = new FlowContext("sle");
        ctx.setVariable("score", 75);

        FlowResult result = engine.execute(engine.parse(json), ctx);
        assertTrue(result.isSuccess());
        assertEquals("B", result.getReturnValues().get("grade"));
    }

    @Test
    void switch_notEquals() {
        String json = """
                { "version": "2.0", "id": "sne", "name": "SNE", "startNodeId": "s",
                  "nodes": [
                    { "id": "s", "type": "start", "name": "S", "properties": { "code": "ERR" }, "next": "sw" },
                    { "id": "sw", "type": "switch", "name": "Sw",
                      "properties": { "expression": "code" },
                      "branches": [
                        { "condition": "!= OK", "target": "err" },
                        { "condition": "== OK", "target": "ok" }
                      ] },
                    { "id": "ok",  "type": "start", "name": "OK",  "properties": { "r": "ok" },    "next": "e" },
                    { "id": "err", "type": "start", "name": "Err", "properties": { "r": "error" }, "next": "e" },
                    { "id": "e", "type": "end", "name": "E",
                      "properties": { "returnValues": { "result": "${r}" } } }
                  ] }
                """;
        FlowResult result = engine.execute(engine.parse(json));
        assertTrue(result.isSuccess());
        assertEquals("error", result.getReturnValues().get("result"));
    }

    @Test
    void switch_defaultFallthrough() {
        String json = """
                { "version": "2.0", "id": "sdf", "name": "SDF", "startNodeId": "s",
                  "nodes": [
                    { "id": "s", "type": "start", "name": "S", "properties": { "val": "UNKNOWN" }, "next": "sw" },
                    { "id": "sw", "type": "switch", "name": "Sw",
                      "properties": { "expression": "val" },
                      "branches": [{ "condition": "== YES", "target": "y" }],
                      "next": "n" },
                    { "id": "y", "type": "start", "name": "Y", "properties": { "r": "yes" },     "next": "e" },
                    { "id": "n", "type": "start", "name": "N", "properties": { "r": "default" }, "next": "e" },
                    { "id": "e", "type": "end", "name": "E",
                      "properties": { "returnValues": { "result": "${r}" } } }
                  ] }
                """;
        FlowResult result = engine.execute(engine.parse(json));
        assertTrue(result.isSuccess());
        assertEquals("default", result.getReturnValues().get("result"));
    }

    @Test
    void switch_onNodeOutput() {
        engine.registerHandler(capabilityHandler("process"));

        String json = """
                { "version": "2.0", "id": "sno", "name": "SNO", "startNodeId": "s",
                  "nodes": [
                    { "id": "s", "type": "start", "name": "S", "next": "p" },
                    { "id": "p", "type": "process", "name": "P",
                      "inputMappings": [{ "name": "x", "source": "y", "dataType": "STRING" }],
                      "next": "sw" },
                    { "id": "sw", "type": "switch", "name": "Sw",
                      "properties": { "expression": "p.result" },
                      "branches": [{ "condition": "== DONE", "target": "ok" }],
                      "next": "fail" },
                    { "id": "ok",   "type": "start", "name": "OK",   "properties": { "r": "ok" },   "next": "e" },
                    { "id": "fail", "type": "start", "name": "Fail", "properties": { "r": "fail" }, "next": "e" },
                    { "id": "e", "type": "end", "name": "E",
                      "properties": { "returnValues": { "result": "${r}" } } }
                  ] }
                """;
        FlowResult result = engine.execute(engine.parse(json));
        assertTrue(result.isSuccess());
        assertEquals("ok", result.getReturnValues().get("result"));
    }

    // ======================================================================
    // Full flow: start → capability → switch → end
    // ======================================================================

    @Test
    void fullFlow() {
        engine.registerHandler(capabilityHandler("submit_form"));

        String json = """
                { "version": "2.0", "id": "full", "name": "Full Flow", "startNodeId": "start",
                  "nodes": [
                    { "id": "start", "type": "start", "name": "Begin",
                      "inputMappings": [
                        { "name": "userName", "source": "${userName}", "dataType": "STRING" },
                        { "name": "amount",   "source": "${amount}",  "dataType": "NUMBER" }
                      ], "next": "submit" },
                    { "id": "submit", "type": "submit_form", "name": "Submit",
                      "inputMappings": [
                        { "name": "user",   "source": "${userName}", "dataType": "STRING" },
                        { "name": "amount", "source": "${amount}",   "dataType": "NUMBER" }
                      ], "next": "check" },
                    { "id": "check", "type": "switch", "name": "Check",
                      "properties": { "expression": "amount" },
                      "branches": [
                        { "condition": "> 10000",  "target": "high" },
                        { "condition": "<= 10000", "target": "low" }
                      ] },
                    { "id": "high", "type": "start", "name": "High", "properties": { "tier": "PREMIUM" },  "next": "end" },
                    { "id": "low",  "type": "start", "name": "Low",  "properties": { "tier": "STANDARD" }, "next": "end" },
                    { "id": "end", "type": "end", "name": "Done",
                      "properties": {
                        "returnValues": {
                          "tier": "${tier}",
                          "submitResult": "${submit.result}"
                        }
                      } }
                  ] }
                """;
        FlowContext ctx = new FlowContext("full");
        ctx.setVariable("userName", "Alice");
        ctx.setVariable("amount", 50000);

        FlowResult result = engine.execute(engine.parse(json), ctx);

        assertTrue(result.isSuccess());
        assertEquals("PREMIUM", result.getReturnValues().get("tier"));
        assertEquals("DONE", result.getReturnValues().get("submitResult"));
    }

    // ======================================================================
    // Validation
    // ======================================================================

    @Test
    void validate_versionMissing() {
        String json = """
                { "id": "nv", "name": "X", "startNodeId": "e",
                  "nodes": [{ "id": "e", "type": "end", "name": "E" }] }
                """;
        assertThrows(FlowValidationException.class, () -> engine.execute(engine.parse(json)));
    }

    @Test
    void validate_noEndNode() {
        String json = """
                { "version": "2.0", "id": "ne", "name": "X",
                  "nodes": [{ "id": "a", "type": "start", "name": "A" }] }
                """;
        assertThrows(FlowValidationException.class, () -> engine.execute(engine.parse(json)));
    }

    @Test
    void validate_cycle() {
        String json = """
                { "version": "2.0", "id": "cyc", "name": "X", "startNodeId": "a",
                  "nodes": [
                    { "id": "a", "type": "start", "name": "A", "next": "b" },
                    { "id": "b", "type": "start", "name": "B", "next": "a" },
                    { "id": "e", "type": "end",   "name": "E" }
                  ] }
                """;
        assertThrows(FlowValidationException.class, () -> engine.execute(engine.parse(json)));
    }

    @Test
    void validate_validMinimalFlow() {
        String json = """
                { "version": "2.0", "id": "ok", "name": "OK",
                  "nodes": [
                    { "id": "s", "type": "start", "name": "S" },
                    { "id": "e", "type": "end",   "name": "E", "prevNodes": ["s"] }
                  ] }
                """;
        assertTrue(engine.execute(engine.parse(json)).isSuccess());
    }

    // ======================================================================
    // Fork-join
    // ======================================================================

    @Test
    void forkJoin_viaPrevNodes() {
        engine.registerHandler(capabilityHandler("work"));

        String json = """
                { "version": "2.0", "id": "fj", "name": "FJ",
                  "nodes": [
                    { "id": "s", "type": "start", "name": "S" },
                    { "id": "b", "type": "work", "name": "B", "prevNodes": ["s"],
                      "inputMappings": [{ "name": "x", "source": "bVal", "dataType": "STRING" }] },
                    { "id": "d", "type": "work", "name": "D", "prevNodes": ["s"],
                      "inputMappings": [{ "name": "x", "source": "dVal", "dataType": "STRING" }] },
                    { "id": "e", "type": "end", "name": "E", "prevNodes": ["b", "d"],
                      "properties": {
                        "returnValues": {
                          "bResult": "${b.result}",
                          "dResult": "${d.result}"
                        }
                      } }
                  ] }
                """;
        FlowResult result = engine.execute(engine.parse(json));

        assertTrue(result.isSuccess());
        assertEquals("DONE", result.getReturnValues().get("bResult"));
        assertEquals("DONE", result.getReturnValues().get("dResult"));
    }

    // ======================================================================
    // Recording
    // ======================================================================

    @Test
    void recording_onSuccess() {
        String json = """
                { "version": "2.0", "id": "rec", "name": "Rec",
                  "nodes": [
                    { "id": "s", "type": "start", "name": "S" },
                    { "id": "e", "type": "end",   "name": "E", "prevNodes": ["s"] }
                  ] }
                """;
        FlowResult result = engine.execute(engine.parse(json));
        ExecutionLog log = recorder.getExecutionLog(result.getExecutionId());
        assertEquals(ExecutionLog.Status.SUCCESS, log.getStatus());
        assertEquals(2, log.getTotalNodes());
    }

    @Test
    void recording_capturesNodeDetails() {
        String json = """
                { "version": "2.0", "id": "det", "name": "Det",
                  "nodes": [
                    { "id": "s", "type": "start", "name": "Start" },
                    { "id": "e", "type": "end",   "name": "End", "prevNodes": ["s"] }
                  ] }
                """;
        FlowResult result = engine.execute(engine.parse(json));
        List<NodeExecutionLog> logs = recorder.getExecutionLog(result.getExecutionId()).getNodeExecutionLogs();
        assertEquals("s", logs.get(0).getNodeId());
        assertEquals("start", logs.get(0).getNodeType());
    }

    // ======================================================================
    // file_aggregate capability node
    // ======================================================================

    private void seedFile(String fileId, String fileName, String mimeType, String content) {
        ((InMemoryFileStorageService) fileStorageService)
                .seed(fileId, fileName, mimeType, content.getBytes());
    }

    private Map<String, byte[]> unzip(byte[] zipBytes) throws IOException {
        Map<String, byte[]> entries = new HashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                entries.put(entry.getName(), zis.readAllBytes());
                zis.closeEntry();
            }
        }
        return entries;
    }

    @Test
    void fileAggregate_packsMultipleFilesIntoZip() {
        seedFile("f1", "report.pdf", "application/pdf", "PDF-CONTENT-HERE");
        seedFile("f2", "data.csv", "text/csv", "id,name\n1,Alice\n2,Bob");

        String json = """
                { "version": "2.0", "id": "fa1", "name": "FA1", "startNodeId": "s",
                  "nodes": [
                    { "id": "s", "type": "start", "name": "S", "next": "pack" },
                    { "id": "pack", "type": "file_aggregate", "name": "Pack",
                      "inputMappings": [
                        { "name": "report", "source": "file:f1", "dataType": "FILE" },
                        { "name": "data",   "source": "file:f2", "dataType": "FILE" }
                      ], "next": "e" },
                    { "id": "e", "type": "end", "name": "E",
                      "properties": { "returnValues": {
                        "zipFile": "${pack.zipFile}",
                        "fileCount": "${pack.fileCount}",
                        "fileNames": "${pack.fileNames}"
                      } } }
                  ] }
                """;
        FlowResult result = engine.execute(engine.parse(json));

        assertTrue(result.isSuccess());
        assertEquals(2, result.getVariables().get("pack.fileCount"));
        assertTrue(result.getVariables().get("pack.zipFile") instanceof FileReference);

        String names = (String) result.getVariables().get("pack.fileNames");
        assertTrue(names.contains("report.pdf"));
        assertTrue(names.contains("data.csv"));

        FileReference zipRef = (FileReference) result.getVariables().get("pack.zipFile");
        byte[] zipBytes = fileStorageService.download(zipRef.getFileId());
        assertTrue(zipBytes.length > 0);

        try {
            Map<String, byte[]> entries = unzip(zipBytes);
            assertEquals(2, entries.size());
            assertTrue(entries.containsKey("report.pdf"));
            assertTrue(entries.containsKey("data.csv"));
            assertEquals("PDF-CONTENT-HERE", new String(entries.get("report.pdf")));
            assertEquals("id,name\n1,Alice\n2,Bob", new String(entries.get("data.csv")));
        } catch (IOException e) {
            fail("Failed to read ZIP: " + e.getMessage());
        }
    }

    @Test
    void fileAggregate_withPrevNodeOutputFiles() {
        engine.registerHandler(fileProducerHandler("generate_report"));
        engine.registerHandler(fileProducerHandler("generate_receipt"));

        String json = """
                { "version": "2.0", "id": "fa2", "name": "FA2",
                  "nodes": [
                    { "id": "s", "type": "start", "name": "S" },
                    { "id": "gen1", "type": "generate_report", "name": "Gen Report",
                      "prevNodes": ["s"],
                      "inputMappings": [{ "name": "content", "source": "Report Data", "dataType": "STRING" }] },
                    { "id": "gen2", "type": "generate_receipt", "name": "Gen Receipt",
                      "prevNodes": ["s"],
                      "inputMappings": [{ "name": "content", "source": "Receipt Data", "dataType": "STRING" }] },
                    { "id": "pack", "type": "file_aggregate", "name": "Pack All",
                      "prevNodes": ["gen1", "gen2"],
                      "inputMappings": [
                        { "name": "report",  "source": "${gen1.outputFile}", "dataType": "FILE" },
                        { "name": "receipt", "source": "${gen2.outputFile}", "dataType": "FILE" }
                      ] },
                    { "id": "e", "type": "end", "name": "E", "prevNodes": ["pack"],
                      "properties": { "returnValues": {
                        "zipFile": "${pack.zipFile}",
                        "count":   "${pack.fileCount}"
                      } } }
                  ] }
                """;
        FlowResult result = engine.execute(engine.parse(json));

        assertTrue(result.isSuccess());
        assertTrue(result.getReturnValues().get("zipFile") instanceof FileReference);

        FileReference zipRef = (FileReference) result.getReturnValues().get("zipFile");
        byte[] zipBytes = fileStorageService.download(zipRef.getFileId());
        try {
            Map<String, byte[]> entries = unzip(zipBytes);
            assertEquals(2, entries.size());
        } catch (IOException e) {
            fail("Failed to read ZIP: " + e.getMessage());
        }
    }

    @Test
    void fileAggregate_deduplicatesSameFileName() {
        seedFile("fa", "data.txt", "text/plain", "AAA");
        seedFile("fb", "data.txt", "text/plain", "BBB");

        String json = """
                { "version": "2.0", "id": "fa3", "name": "FA3", "startNodeId": "s",
                  "nodes": [
                    { "id": "s", "type": "start", "name": "S", "next": "pack" },
                    { "id": "pack", "type": "file_aggregate", "name": "Pack",
                      "inputMappings": [
                        { "name": "a", "source": "file:fa", "dataType": "FILE" },
                        { "name": "b", "source": "file:fb", "dataType": "FILE" }
                      ], "next": "e" },
                    { "id": "e", "type": "end", "name": "E" }
                  ] }
                """;
        FlowResult result = engine.execute(engine.parse(json));

        assertTrue(result.isSuccess());
        FileReference zipRef = (FileReference) result.getVariables().get("pack.zipFile");
        byte[] zipBytes = fileStorageService.download(zipRef.getFileId());
        try {
            Map<String, byte[]> entries = unzip(zipBytes);
            assertEquals(2, entries.size());
            assertTrue(entries.containsKey("data.txt"));
            assertTrue(entries.containsKey("data_2.txt"));
        } catch (IOException e) {
            fail("Failed to read ZIP: " + e.getMessage());
        }
    }

    @Test
    void fileAggregate_inFullFlow_withSwitch() {
        seedFile("doc-a", "report.pdf", "application/pdf", "PDF content");
        seedFile("doc-b", "image.png", "image/png", "PNG content");

        String json = """
                { "version": "2.0", "id": "fa4", "name": "FA4", "startNodeId": "s",
                  "nodes": [
                    { "id": "s", "type": "start", "name": "S", "next": "sw" },
                    { "id": "sw", "type": "switch", "name": "Check",
                      "properties": { "expression": "mode" },
                      "branches": [
                        { "condition": "== FULL", "target": "packAll" },
                        { "condition": "== LITE", "target": "packLite" }
                      ] },
                    { "id": "packAll", "type": "file_aggregate", "name": "Pack All",
                      "inputMappings": [
                        { "name": "a", "source": "file:doc-a", "dataType": "FILE" },
                        { "name": "b", "source": "file:doc-b", "dataType": "FILE" }
                      ], "next": "e" },
                    { "id": "packLite", "type": "file_aggregate", "name": "Pack Lite",
                      "inputMappings": [
                        { "name": "a", "source": "file:doc-a", "dataType": "FILE" }
                      ], "next": "e" },
                    { "id": "e", "type": "end", "name": "E",
                      "properties": { "returnValues": { "count": "${packAll.fileCount}" } } }
                  ] }
                """;
        FlowContext ctx = new FlowContext("fa4");
        ctx.setVariable("mode", "FULL");

        FlowResult result = engine.execute(engine.parse(json), ctx);
        assertTrue(result.isSuccess());
        assertEquals(2, result.getVariables().get("packAll.fileCount"));
    }

    // ======================================================================
    // Helper: inline capability handler for tests
    // ======================================================================

    private NodeHandler fileProducerHandler(String type) {
        return new NodeHandler() {
            @Override public String getType() { return type; }
            @Override public HandleResult execute(FlowNode node, FlowContext context) {
                String content = (String) context.getResolvedInputs().getOrDefault("content", "default");
                FileReference ref = fileStorageService.upload(
                        node.getId() + ".txt", "text/plain", content.getBytes());
                return HandleResult.output(NodeOutput.builder()
                        .addFile("outputFile", ref)
                        .addString("result", "DONE")
                        .build());
            }
        };
    }

    private static NodeHandler capabilityHandler(String type) {
        return new NodeHandler() {
            @Override public String getType() { return type; }
            @Override public HandleResult execute(FlowNode node, FlowContext context) {
                Map<String, Object> inputs = context.getResolvedInputs();
                NodeOutput.Builder b = NodeOutput.builder();
                b.addString("result", "DONE");
                inputs.forEach((k, v) -> b.add(k, NodeOutput.DataType.STRING, v));
                return HandleResult.output(b.build());
            }
        };
    }
}
