package com.interview.agent.rag;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Persistent local vector index used when Milvus is unavailable. */
@Component
@Profile("local")
public class LocalMilvusStore extends MilvusStore {
    public record StoredDocument(RagDocument document, float[] vector) {}

    private final EmbeddingModel embeddingModel;
    private final Path file;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, StoredDocument> documents = new ConcurrentHashMap<>();

    public LocalMilvusStore(EmbeddingModel embeddingModel,
                            @Value("${app.local.questions-file:data/local/questions.json}") String file) {
        super(null, embeddingModel);
        this.embeddingModel = embeddingModel;
        this.file = Path.of(file);
    }

    @Override
    public void init() {
        // The local profile has no Milvus connection.
    }

    @PostConstruct
    public void loadLocal() {
        if (!Files.exists(file)) return;
        try {
            List<StoredDocument> saved = mapper.readValue(file.toFile(), new TypeReference<>() {});
            for (StoredDocument item : saved) {
                documents.put(key(item.document()), item);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Unable to read local question index: " + file, e);
        }
    }

    @Override
    public synchronized List<String> store(List<RagDocument> docs) {
        List<StoredDocument> added = new ArrayList<>(docs.size());
        for (RagDocument doc : docs) {
            float[] vector = embeddingModel.embed(doc.getContent());
            if (vector.length != VECTOR_DIMENSION) {
                throw new IllegalStateException("Unexpected embedding dimension: " + vector.length);
            }
            added.add(new StoredDocument(doc, vector));
        }
        for (StoredDocument item : added) documents.put(key(item.document()), item);
        persist();
        return docs.stream().map(RagDocument::getId).toList();
    }

    @Override
    public List<RagDocument> retrieveByUser(String userId, String query, int topK) {
        return search(userId, query, topK);
    }

    @Override
    public List<RagDocument> retrieve(String query, int topK) {
        return search(null, query, topK);
    }

    @Override
    public Map<String, Long> listSourcesByUser(String userId) {
        Map<String, Long> counts = new HashMap<>();
        documents.values().stream()
                .map(StoredDocument::document)
                .filter(doc -> userId.equals(doc.getUserId()))
                .map(RagDocument::getSourceFile)
                .filter(source -> source != null && !source.isBlank())
                .forEach(source -> counts.merge(source, 1L, Long::sum));
        return counts;
    }

    @Override
    public List<RagDocument> listBySource(String userId, String sourceFile, int offset, int limit) {
        return documents.values().stream()
                .map(StoredDocument::document)
                .filter(doc -> userId.equals(doc.getUserId()) && sourceFile.equals(doc.getSourceFile()))
                .sorted(Comparator.comparing(RagDocument::getSourceFile, Comparator.nullsLast(String::compareTo))
                        .thenComparing(RagDocument::getId, Comparator.nullsLast(String::compareTo)))
                .skip(offset)
                .limit(limit)
                .toList();
    }

    private List<RagDocument> search(String userId, String query, int topK) {
        if (documents.isEmpty()) return List.of();
        float[] queryVector = embeddingModel.embed(query);
        return documents.values().stream()
                .filter(item -> userId == null || userId.equals(item.document().getUserId()))
                .map(item -> {
                    RagDocument source = item.document();
                    return RagDocument.builder()
                            .id(source.getId()).content(source.getContent()).metadata(source.getMetadata())
                            .userId(source.getUserId()).sourceFile(source.getSourceFile())
                            .score(cosine(queryVector, item.vector())).build();
                })
                .sorted(Comparator.comparingDouble(RagDocument::getScore).reversed())
                .limit(topK > 0 ? topK : 10)
                .toList();
    }

    @Override
    public synchronized void deleteByUserID(String userId) {
        documents.values().removeIf(item -> userId.equals(item.document().getUserId()));
        persist();
    }

    @Override
    public synchronized void deleteBySourceFile(String userId, String sourceFile) {
        documents.values().removeIf(item -> userId.equals(item.document().getUserId())
                && sourceFile.equals(item.document().getSourceFile()));
        persist();
    }

    private String key(RagDocument doc) {
        return doc.getUserId() + ":" + doc.getId();
    }

    private float cosine(float[] a, float[] b) {
        double dot = 0;
        double aNorm = 0;
        double bNorm = 0;
        for (int i = 0; i < Math.min(a.length, b.length); i++) {
            dot += a[i] * b[i];
            aNorm += a[i] * a[i];
            bNorm += b[i] * b[i];
        }
        return aNorm == 0 || bNorm == 0 ? 0 : (float) (dot / Math.sqrt(aNorm * bNorm));
    }

    private void persist() {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            mapper.writeValue(temp.toFile(), documents.values());
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to save local question index: " + file, e);
        }
    }
}
