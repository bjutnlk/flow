package com.flow.engine.zip;

import com.flow.engine.model.FileReference;
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
 * End-to-end tests for the extract / repack pipeline, covering:
 * nested zips, empty folders, duplicated file names across folders,
 * result-file replacement, and round-trip integrity.
 */
@SpringBootTest
class ZipExtractRepackTest {

    @Autowired private ZipExtractService extractService;
    @Autowired private ZipRepackService repackService;
    @Autowired private InMemoryZipExtractRecordRepository repository;
    @Autowired private InMemoryFileStorageService storage;

    @BeforeEach
    void clean() {
        repository.clear();
    }

    // ---- helpers ------------------------------------------------------------

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

    // ---- basic extract ------------------------------------------------------

    @Test
    void extract_persistsOneRecordPerEntry_withCorrectTree() throws IOException {
        Map<String, byte[]> src = new HashMap<>();
        src.put("a.txt",         "hello".getBytes());
        src.put("dir/b.txt",     "world".getBytes());
        src.put("dir/empty/",    new byte[0]);
        byte[] zip = buildZip(src);

        String rootId = storage.upload("root.zip", "application/zip", zip).getFileId();

        List<ZipExtractRecord> records = extractService.extract("task-1", rootId);

        assertEquals(1, records.stream().filter(r -> r.getEntryType() == ZipExtractRecord.EntryType.ROOT_ZIP).count());
        assertEquals(2, records.stream().filter(r -> r.getEntryType() == ZipExtractRecord.EntryType.FILE).count());
        assertEquals(1, records.stream().filter(r -> r.getEntryType() == ZipExtractRecord.EntryType.DIRECTORY).count());

        ZipExtractRecord root = records.get(0);
        assertNull(root.getParentRecordId());
        assertEquals(0, root.getDepth());
        assertEquals(rootId, root.getFileId());

        records.stream().skip(1).forEach(r -> assertEquals(root.getId(), r.getParentRecordId()));
    }

    @Test
    void extract_allowsDuplicateFileNamesInDifferentFolders() throws IOException {
        Map<String, byte[]> src = new HashMap<>();
        src.put("dirA/data.txt", "A-CONTENT".getBytes());
        src.put("dirB/data.txt", "B-CONTENT".getBytes());
        byte[] zip = buildZip(src);
        String id = storage.upload("dup.zip", "application/zip", zip).getFileId();

        List<ZipExtractRecord> records = extractService.extract("task-dup", id);

        List<ZipExtractRecord> files = records.stream()
                .filter(r -> r.getEntryType() == ZipExtractRecord.EntryType.FILE)
                .toList();
        assertEquals(2, files.size());
        assertNotEquals(files.get(0).getEntryPath(), files.get(1).getEntryPath());

        for (ZipExtractRecord f : files) {
            byte[] bytes = storage.download(f.getFileId());
            if (f.getEntryPath().startsWith("dirA/")) {
                assertEquals("A-CONTENT", new String(bytes));
            } else {
                assertEquals("B-CONTENT", new String(bytes));
            }
        }
    }

    @Test
    void extract_nestedZip_recursivelyUnpacked() throws IOException {
        Map<String, byte[]> innerSrc = new HashMap<>();
        innerSrc.put("inner-a.txt", "INNER-A".getBytes());
        innerSrc.put("sub/inner-b.txt", "INNER-B".getBytes());
        byte[] inner = buildZip(innerSrc);

        Map<String, byte[]> outerSrc = new HashMap<>();
        outerSrc.put("top.txt", "TOP".getBytes());
        outerSrc.put("nested/inner.zip", inner);
        byte[] outer = buildZip(outerSrc);

        String id = storage.upload("outer.zip", "application/zip", outer).getFileId();
        List<ZipExtractRecord> records = extractService.extract("task-nest", id);

        long nestedZips = records.stream()
                .filter(r -> r.getEntryType() == ZipExtractRecord.EntryType.NESTED_ZIP).count();
        assertEquals(1, nestedZips);

        ZipExtractRecord nestedZipRec = records.stream()
                .filter(r -> r.getEntryType() == ZipExtractRecord.EntryType.NESTED_ZIP)
                .findFirst().orElseThrow();
        assertEquals(1, nestedZipRec.getDepth());

        List<ZipExtractRecord> innerFiles = records.stream()
                .filter(r -> nestedZipRec.getId().equals(r.getParentRecordId()))
                .filter(r -> r.getEntryType() == ZipExtractRecord.EntryType.FILE)
                .toList();
        assertEquals(2, innerFiles.size());
        for (ZipExtractRecord f : innerFiles) {
            assertEquals(2, f.getDepth());
        }
    }

    // ---- repack round-trip --------------------------------------------------

    @Test
    void repack_roundTrip_preservesEntryNamesAndContent() throws IOException {
        Map<String, byte[]> src = new HashMap<>();
        src.put("a.txt",          "AAA".getBytes());
        src.put("dir1/b.txt",     "BBB".getBytes());
        src.put("dir1/sub/c.txt", "CCC".getBytes());
        src.put("dir2/",          new byte[0]);
        byte[] zip = buildZip(src);

        String id = storage.upload("src.zip", "application/zip", zip).getFileId();
        extractService.extract("task-rt", id);

        FileReference repacked = repackService.repack("task-rt");
        byte[] repackedBytes = storage.download(repacked.getFileId());
        Map<String, byte[]> result = unzip(repackedBytes);

        assertTrue(result.containsKey("a.txt"));
        assertTrue(result.containsKey("dir1/b.txt"));
        assertTrue(result.containsKey("dir1/sub/c.txt"));
        assertTrue(result.containsKey("dir2/"));

        assertEquals("AAA", new String(result.get("a.txt")));
        assertEquals("BBB", new String(result.get("dir1/b.txt")));
        assertEquals("CCC", new String(result.get("dir1/sub/c.txt")));
    }

    @Test
    void repack_preservesNestedZipStructure() throws IOException {
        Map<String, byte[]> innerSrc = new HashMap<>();
        innerSrc.put("x.txt", "X".getBytes());
        innerSrc.put("y.txt", "Y".getBytes());
        byte[] inner = buildZip(innerSrc);

        Map<String, byte[]> outerSrc = new HashMap<>();
        outerSrc.put("readme.txt", "hi".getBytes());
        outerSrc.put("docs/inner.zip", inner);
        byte[] outer = buildZip(outerSrc);

        String id = storage.upload("outer.zip", "application/zip", outer).getFileId();
        extractService.extract("task-nested-rt", id);

        FileReference repacked = repackService.repack("task-nested-rt");
        Map<String, byte[]> outerEntries = unzip(storage.download(repacked.getFileId()));

        assertTrue(outerEntries.containsKey("readme.txt"));
        assertTrue(outerEntries.containsKey("docs/inner.zip"));

        Map<String, byte[]> innerEntries = unzip(outerEntries.get("docs/inner.zip"));
        assertEquals("X", new String(innerEntries.get("x.txt")));
        assertEquals("Y", new String(innerEntries.get("y.txt")));
    }

    @Test
    void repack_usesResultFileId_whenSet() throws IOException {
        Map<String, byte[]> src = new HashMap<>();
        src.put("data.txt", "ORIGINAL".getBytes());
        byte[] zip = buildZip(src);

        String id = storage.upload("src.zip", "application/zip", zip).getFileId();
        List<ZipExtractRecord> records = extractService.extract("task-swap", id);

        ZipExtractRecord fileRec = records.stream()
                .filter(r -> r.getEntryType() == ZipExtractRecord.EntryType.FILE)
                .findFirst().orElseThrow();

        FileReference processed = storage.upload("data.txt", "text/plain", "PROCESSED".getBytes());
        assertTrue(repository.updateResultFileId(fileRec.getId(), processed.getFileId()));

        FileReference repacked = repackService.repack("task-swap");
        Map<String, byte[]> after = unzip(storage.download(repacked.getFileId()));

        assertEquals("PROCESSED", new String(after.get("data.txt")));
    }

    @Test
    void repack_preservesEmptyDirectory() throws IOException {
        Map<String, byte[]> src = new HashMap<>();
        src.put("empty-dir/", new byte[0]);
        src.put("file.txt", "ok".getBytes());
        byte[] zip = buildZip(src);

        String id = storage.upload("src.zip", "application/zip", zip).getFileId();
        extractService.extract("task-empty", id);
        FileReference repacked = repackService.repack("task-empty");
        Map<String, byte[]> after = unzip(storage.download(repacked.getFileId()));

        assertTrue(after.containsKey("empty-dir/"));
        assertTrue(after.containsKey("file.txt"));
    }

    @Test
    void repack_failsForUnknownTask() {
        assertThrows(IllegalStateException.class, () -> repackService.repack("no-such-task"));
    }

    // ---- repository querying ------------------------------------------------

    @Test
    void repository_findByTaskId_isolatesTasks() throws IOException {
        byte[] zipA = buildZip(Map.of("a.txt", "A".getBytes()));
        byte[] zipB = buildZip(Map.of("b.txt", "B".getBytes()));

        String idA = storage.upload("a.zip", "application/zip", zipA).getFileId();
        String idB = storage.upload("b.zip", "application/zip", zipB).getFileId();

        extractService.extract("t-a", idA);
        extractService.extract("t-b", idB);

        List<ZipExtractRecord> aAll = repository.findByTaskId("t-a");
        List<ZipExtractRecord> bAll = repository.findByTaskId("t-b");

        assertFalse(aAll.isEmpty());
        assertFalse(bAll.isEmpty());
        aAll.forEach(r -> assertEquals("t-a", r.getTaskId()));
        bAll.forEach(r -> assertEquals("t-b", r.getTaskId()));
    }
}
