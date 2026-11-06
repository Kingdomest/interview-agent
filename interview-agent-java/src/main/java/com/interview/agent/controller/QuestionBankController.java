package com.interview.agent.controller;

import com.interview.agent.rag.MilvusStore;
import com.interview.agent.rag.RagDocument;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** 只读展示当前登录用户已解析入库的题库 chunks。 */
@Slf4j
@RestController
@RequestMapping("/api/question-bank")
@RequiredArgsConstructor
public class QuestionBankController {

    private final MilvusStore milvusStore;

    @GetMapping("/sources")
    public ResponseEntity<?> listSources(@RequestAttribute("username") String username) {
        try {
            List<QuestionSource> sources = milvusStore.listSourcesByUser(username).entrySet().stream()
                    .map(entry -> new QuestionSource(entry.getKey(), entry.getValue()))
                    .sorted(Comparator.comparing(QuestionSource::sourceFile, String.CASE_INSENSITIVE_ORDER))
                    .toList();
            return ResponseEntity.ok(sources);
        } catch (Exception e) {
            log.error("[QuestionBank] 读取用户 {} 的题库分类失败: {}", username, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", "读取题库分类失败"));
        }
    }

    @GetMapping("/chunks")
    public ResponseEntity<?> listChunks(@RequestAttribute("username") String username,
                                        @RequestParam String sourceFile,
                                        @RequestParam(defaultValue = "0") int offset,
                                        @RequestParam(defaultValue = "20") int limit) {
        if (sourceFile.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "请选择题库文件"));
        }
        if (offset < 0 || limit < 1 || limit > 100) {
            return ResponseEntity.badRequest().body(Map.of("error", "分页参数无效"));
        }
        try {
            List<RagDocument> rows = milvusStore.listBySource(username, sourceFile, offset, limit + 1);
            boolean hasMore = rows.size() > limit;
            List<QuestionChunk> chunks = rows.stream().limit(limit)
                    .map(doc -> new QuestionChunk(doc.getId(), doc.getContent(), doc.getSourceFile(), doc.getMetadata()))
                    .toList();
            return ResponseEntity.ok(new ChunkPage(chunks, offset + chunks.size(), hasMore));
        } catch (Exception e) {
            log.error("[QuestionBank] 读取用户 {} 的题库失败: {}", username, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", "读取题库失败"));
        }
    }

    public record QuestionChunk(String id, String content, String sourceFile, Map<String, Object> metadata) {}

    public record QuestionSource(String sourceFile, long count) {}

    public record ChunkPage(List<QuestionChunk> chunks, int nextOffset, boolean hasMore) {}
}
