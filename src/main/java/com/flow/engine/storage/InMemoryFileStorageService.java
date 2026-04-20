package com.flow.engine.storage;

import com.flow.engine.model.FileReference;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory implementation of {@link FileStorageService} for testing
 * and local development.  Falls back automatically when no cloud
 * storage bean is configured.
 */
@Component
@ConditionalOnMissingBean(value = FileStorageService.class, ignored = InMemoryFileStorageService.class)
public class InMemoryFileStorageService implements FileStorageService {

    private final Map<String, StoredFile> store = new ConcurrentHashMap<>();

    @Override
    public FileReference resolve(String fileId) {
        StoredFile sf = store.get(fileId);
        if (sf == null) {
            throw new FileStorageException("File not found: " + fileId, fileId);
        }
        return new FileReference(fileId, sf.fileName, sf.mimeType, sf.content.length);
    }

    @Override
    public byte[] download(String fileId) {
        StoredFile sf = store.get(fileId);
        if (sf == null) {
            throw new FileStorageException("File not found: " + fileId, fileId);
        }
        return sf.content.clone();
    }

    @Override
    public FileReference upload(String fileName, String mimeType, byte[] content) {
        String fileId = UUID.randomUUID().toString();
        store.put(fileId, new StoredFile(fileName, mimeType, content));
        return new FileReference(fileId, fileName, mimeType, content.length);
    }

    /**
     * Pre-seed a file (useful in tests to simulate files already in cloud storage).
     */
    public void seed(String fileId, String fileName, String mimeType, byte[] content) {
        store.put(fileId, new StoredFile(fileName, mimeType, content));
    }

    public int getFileCount() {
        return store.size();
    }

    private record StoredFile(String fileName, String mimeType, byte[] content) {}
}
