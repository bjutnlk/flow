package com.flow.engine.zip;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory fallback implementation of {@link ZipExtractRecordRepository}.
 *
 * <p>Good enough for local development and tests; production deployments
 * should replace it with a JDBC/JPA-backed bean.</p>
 */
@Component
@ConditionalOnMissingBean(value = ZipExtractRecordRepository.class,
        ignored = InMemoryZipExtractRecordRepository.class)
public class InMemoryZipExtractRecordRepository implements ZipExtractRecordRepository {

    private final Map<String, ZipExtractRecord> store = new ConcurrentHashMap<>();

    @Override
    public ZipExtractRecord save(ZipExtractRecord record) {
        store.put(record.getId(), record);
        return record;
    }

    @Override
    public void saveAll(List<ZipExtractRecord> records) {
        for (ZipExtractRecord r : records) {
            store.put(r.getId(), r);
        }
    }

    @Override
    public Optional<ZipExtractRecord> findById(String id) {
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public List<ZipExtractRecord> findByTaskId(String taskId) {
        List<ZipExtractRecord> result = new ArrayList<>();
        for (ZipExtractRecord r : store.values()) {
            if (taskId.equals(r.getTaskId())) {
                result.add(r);
            }
        }
        result.sort((a, b) -> {
            int byDepth = Integer.compare(a.getDepth(), b.getDepth());
            if (byDepth != 0) return byDepth;
            return a.getCreatedAt().compareTo(b.getCreatedAt());
        });
        return result;
    }

    @Override
    public List<ZipExtractRecord> findByParentRecordId(String parentRecordId) {
        List<ZipExtractRecord> result = new ArrayList<>();
        for (ZipExtractRecord r : store.values()) {
            if (parentRecordId == null) {
                if (r.getParentRecordId() == null) result.add(r);
            } else if (parentRecordId.equals(r.getParentRecordId())) {
                result.add(r);
            }
        }
        return result;
    }

    @Override
    public boolean updateResultFileId(String recordId, String resultFileId) {
        ZipExtractRecord r = store.get(recordId);
        if (r == null) return false;
        r.setResultFileId(resultFileId);
        return true;
    }

    @Override
    public void deleteByTaskId(String taskId) {
        store.values().removeIf(r -> taskId.equals(r.getTaskId()));
    }

    public int count() {
        return store.size();
    }

    public void clear() {
        store.clear();
    }
}
