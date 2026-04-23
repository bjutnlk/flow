package com.flow.engine.zip;

import com.flow.engine.handler.HandleResult;
import com.flow.engine.handler.NodeHandler;
import com.flow.engine.model.FileReference;
import com.flow.engine.model.FlowContext;
import com.flow.engine.model.FlowNode;
import com.flow.engine.model.FlowResult;
import com.flow.engine.model.NodeOutput;
import com.flow.engine.service.FlowEngine;
import com.flow.engine.storage.InMemoryFileStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Full flow-engine wiring test: {@code file_extract} → (process) →
 * {@code file_repack} with the processing stage attaching a
 * {@code resultFileId} to every extracted record.
 */
@SpringBootTest
class FileExtractRepackFlowTest {

    @Autowired private FlowEngine engine;
    @Autowired private InMemoryFileStorageService storage;
    @Autowired private InMemoryZipExtractRecordRepository repository;

    @BeforeEach
    void reset() {
        repository.clear();
    }

    private static byte[] buildZip(Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                zos.putNextEntry(new ZipEntry(e.getKey()));
                if (e.getValue() != null && e.getValue().length > 0) {
                    zos.write(e.getValue());
                }
                zos.closeEntry();
            }
        }
        return baos.toByteArray();
    }

    private static Map<String, byte[]> unzip(byte[] zipBytes) throws IOException {
        Map<String, byte[]> out = new HashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                out.put(entry.getName(), zis.readAllBytes());
                zis.closeEntry();
            }
        }
        return out;
    }

    /**
     * Stub that walks every FILE record of a task, writes an uppercased
     * copy to storage, and patches {@code resultFileId}.
     */
    private NodeHandler uppercaseProcessor() {
        return new NodeHandler() {
            @Override public String getType() { return "upper_process"; }
            @Override public HandleResult execute(FlowNode node, FlowContext context) {
                String taskId = (String) context.getResolvedInputs().get("taskId");
                List<ZipExtractRecord> records = repository.findByTaskId(taskId);
                int count = 0;
                for (ZipExtractRecord r : records) {
                    if (r.getEntryType() == ZipExtractRecord.EntryType.FILE) {
                        byte[] original = storage.download(r.getFileId());
                        byte[] processed = new String(original).toUpperCase().getBytes();
                        FileReference up = storage.upload(r.getFileName(), r.getMimeType(), processed);
                        repository.updateResultFileId(r.getId(), up.getFileId());
                        count++;
                    }
                }
                return HandleResult.output(NodeOutput.builder().addNumber("processed", count).build());
            }
        };
    }

    @Test
    void flow_extractProcessRepack_producesStructureIdenticalZipWithProcessedBytes() throws IOException {
        engine.registerHandler(uppercaseProcessor());

        Map<String, byte[]> innerSrc = new HashMap<>();
        innerSrc.put("note.txt", "inside".getBytes());
        byte[] inner = buildZip(innerSrc);

        Map<String, byte[]> outerSrc = new HashMap<>();
        outerSrc.put("hello.txt",        "hello".getBytes());
        outerSrc.put("dir/world.txt",    "world".getBytes());
        outerSrc.put("dir/dup.txt",      "x".getBytes());
        outerSrc.put("other/dup.txt",    "y".getBytes());
        outerSrc.put("empty/",           new byte[0]);
        outerSrc.put("pkg/inner.zip",    inner);
        byte[] outer = buildZip(outerSrc);

        String rootId = storage.upload("root.zip", "application/zip", outer).getFileId();

        String json = """
                { "version": "2.0", "id": "ep-flow", "name": "Extract-Process-Repack", "startNodeId": "s",
                  "nodes": [
                    { "id": "s", "type": "start", "name": "S", "next": "extract",
                      "properties": { "taskId": "task-ep", "zipFileId": "%s" } },

                    { "id": "extract", "type": "file_extract", "name": "Extract",
                      "inputMappings": [
                        { "name": "zipFileId", "source": "${zipFileId}", "dataType": "STRING" },
                        { "name": "taskId",    "source": "${taskId}",    "dataType": "STRING" }
                      ], "next": "proc" },

                    { "id": "proc", "type": "upper_process", "name": "Process",
                      "inputMappings": [
                        { "name": "taskId", "source": "${extract.taskId}", "dataType": "STRING" }
                      ], "next": "repack" },

                    { "id": "repack", "type": "file_repack", "name": "Repack",
                      "inputMappings": [
                        { "name": "taskId",         "source": "${extract.taskId}",  "dataType": "STRING" },
                        { "name": "outputFileName", "source": "final.zip",           "dataType": "STRING" }
                      ], "next": "e" },

                    { "id": "e", "type": "end", "name": "E",
                      "properties": { "returnValues": {
                        "recordCount": "${extract.recordCount}",
                        "fileCount":   "${extract.fileCount}",
                        "processed":   "${proc.processed}",
                        "zipFile":     "${repack.zipFile}"
                      } } }
                  ] }
                """.formatted(rootId);

        FlowResult result = engine.execute(engine.parse(json));
        assertTrue(result.isSuccess(), "flow should succeed: " + result.getErrorMessage());

        assertEquals(5L, ((Number) result.getReturnValues().get("fileCount")).longValue(),
                "5 files total: hello.txt, dir/world.txt, dir/dup.txt, other/dup.txt, pkg/inner.zip/note.txt");
        assertEquals(5, ((Number) result.getReturnValues().get("processed")).intValue());

        FileReference zipRef = (FileReference) result.getReturnValues().get("zipFile");
        assertNotNull(zipRef);

        Map<String, byte[]> outerEntries = unzip(storage.download(zipRef.getFileId()));
        assertTrue(outerEntries.containsKey("hello.txt"));
        assertTrue(outerEntries.containsKey("dir/world.txt"));
        assertTrue(outerEntries.containsKey("dir/dup.txt"));
        assertTrue(outerEntries.containsKey("other/dup.txt"));
        assertTrue(outerEntries.containsKey("empty/"));
        assertTrue(outerEntries.containsKey("pkg/inner.zip"));

        assertEquals("HELLO", new String(outerEntries.get("hello.txt")));
        assertEquals("WORLD", new String(outerEntries.get("dir/world.txt")));
        assertEquals("X",     new String(outerEntries.get("dir/dup.txt")));
        assertEquals("Y",     new String(outerEntries.get("other/dup.txt")));

        Map<String, byte[]> innerEntries = unzip(outerEntries.get("pkg/inner.zip"));
        assertEquals(1, innerEntries.size());
        assertEquals("INSIDE", new String(innerEntries.get("note.txt")));
    }

    @Test
    void flow_extractAlone_persistsAllRecords() throws IOException {
        Map<String, byte[]> src = new HashMap<>();
        src.put("a.txt", "A".getBytes());
        src.put("d/b.txt", "B".getBytes());
        byte[] zip = buildZip(src);
        String id = storage.upload("x.zip", "application/zip", zip).getFileId();

        String json = """
                { "version": "2.0", "id": "only-extract", "name": "OnlyExtract", "startNodeId": "s",
                  "nodes": [
                    { "id": "s", "type": "start", "name": "S", "next": "x",
                      "properties": { "zipFileId": "%s", "taskId": "task-only" } },
                    { "id": "x", "type": "file_extract", "name": "Extract",
                      "inputMappings": [
                        { "name": "zipFileId", "source": "${zipFileId}", "dataType": "STRING" },
                        { "name": "taskId",    "source": "${taskId}",    "dataType": "STRING" }
                      ], "next": "e" },
                    { "id": "e", "type": "end", "name": "E",
                      "properties": { "returnValues": {
                        "recordCount": "${x.recordCount}",
                        "fileCount":   "${x.fileCount}",
                        "taskId":      "${x.taskId}"
                      } } }
                  ] }
                """.formatted(id);

        FlowResult result = engine.execute(engine.parse(json));
        assertTrue(result.isSuccess());
        assertEquals("task-only", result.getReturnValues().get("taskId"));
        assertEquals(2L, ((Number) result.getReturnValues().get("fileCount")).longValue());

        List<ZipExtractRecord> stored = repository.findByTaskId("task-only");
        assertFalse(stored.isEmpty());
        assertEquals(stored.size(),
                ((Number) result.getReturnValues().get("recordCount")).intValue());
    }
}
