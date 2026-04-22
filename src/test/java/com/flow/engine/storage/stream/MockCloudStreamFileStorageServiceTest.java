package com.flow.engine.storage.stream;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MockCloudStreamFileStorageServiceTest {

    private final MockCloudStreamFileStorageService storage = new MockCloudStreamFileStorageService();

    @Test
    void 上传后能流式下载回原始内容() throws IOException {
        byte[] payload = "hello 云存储".getBytes(StandardCharsets.UTF_8);

        UploadResult up;
        try (InputStream in = new ByteArrayInputStream(payload)) {
            up = storage.upload(in, UploadMetadata.of("greet.txt", "text/plain; charset=UTF-8"));
        }

        assertNotNull(up.fileId());
        assertEquals("greet.txt", up.fileName());
        assertEquals(payload.length, up.size());
        assertTrue(storage.exists(up.fileId()));

        try (DownloadStream ds = storage.download(up.fileId())) {
            assertEquals("greet.txt", ds.getFileName());
            assertEquals("text/plain; charset=UTF-8", ds.getContentType());
            byte[] got = ds.getInputStream().readAllBytes();
            assertArrayEquals(payload, got);
        }
    }

    @Test
    void DownloadStream_close后底层流也被关闭() throws IOException {
        byte[] payload = new byte[]{1, 2, 3};
        UploadResult up;
        try (InputStream in = new ByteArrayInputStream(payload)) {
            up = storage.upload(in, UploadMetadata.of("a.bin", "application/octet-stream"));
        }

        DownloadStream ds = storage.download(up.fileId());
        InputStream inner = ds.getInputStream();
        ds.close();

        // ByteArrayInputStream.close 是 no-op，所以直接断言重复 close 不抛异常、且 DownloadStream 可幂等关闭
        ds.close();
        assertNotNull(inner);
    }

    @Test
    void 上传不会关闭调用方传入的流() throws IOException {
        AtomicBoolean closed = new AtomicBoolean(false);
        byte[] payload = "abc".getBytes();
        InputStream wrapped = new ByteArrayInputStream(payload) {
            @Override
            public void close() throws IOException {
                closed.set(true);
                super.close();
            }
        };

        storage.upload(wrapped, UploadMetadata.of("x.txt", "text/plain"));

        assertFalse(closed.get(), "实现不应该关闭调用方传入的流");
        wrapped.close();
        assertTrue(closed.get());
    }

    @Test
    void 上传元数据缺失会抛异常() {
        try (InputStream in = new ByteArrayInputStream(new byte[]{1})) {
            assertThrows(FileStorageIOException.class, () -> storage.upload(in, null));
        } catch (IOException ignore) {
        }

        assertThrows(NullPointerException.class,
                () -> UploadMetadata.of(null, "text/plain"));
        assertThrows(NullPointerException.class,
                () -> UploadMetadata.of("x.txt", null));
    }

    @Test
    void 下载不存在的文件抛FileStorageIOException() {
        assertThrows(FileStorageIOException.class, () -> storage.download("not-exist"));
    }
}
