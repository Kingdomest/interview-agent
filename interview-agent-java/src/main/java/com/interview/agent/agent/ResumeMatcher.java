package com.interview.agent.agent;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interview.agent.model.JDAnalysis;
import com.interview.agent.model.Resume;
import com.interview.agent.model.ResumeMatchResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * @author 陈龙强
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ResumeMatcher {

    private static final int MAX_RESUME_CHUNKS = 80;
    private static final int MAX_CHUNK_LENGTH = 800;
    private static final int MIN_CHUNK_LENGTH = 12;
    private static final double MATCH_THRESHOLD = 0.62;
    private static final double STRONG_MATCH_THRESHOLD = 0.72;
    private static final double KEYWORD_BONUS = 0.12;
    private static final Pattern SPLIT_PATTERN = Pattern.compile("\\R{2,}|(?m)^\\s*(?:[-*•]|\\d+[.)、])\\s+");

    private final ChatModel chatModel;
    private final EmbeddingModel embeddingModel;
    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static final String RESUME_MATCHER_PROMPT = """
            你是一个专业的简历匹配分析专家。你需要将候选人的简历与目标岗位 JD 进行深度匹配分析。
            
            请按照以下 JSON 格式输出匹配结果（不要输出其他内容，只输出纯 JSON）：
            
            {
              "overall_score": 75.0,
              "skill_match": [
                {
                  "skill_name": "技能名称",
                  "required": true,
                  "matched": true,
                  "match_score": 80.0,
                  "evidence": "从简历中找到的匹配证据"
                }
              ],
              "strengths": ["优势1", "优势2"],
              "weaknesses": ["薄弱点1", "薄弱点2"],
              "focus_areas": ["面试重点考察方向1", "面试重点考察方向2"],
              "resume_gaps": ["简历空白点1（可深挖的地方）"]
            }
            
            评分标准：
            - overall_score: 0-100 分，综合考虑技能匹配度、经验相关性、项目质量
            - skill_match: 逐项列出 JD 要求的技能，标注是否在简历中匹配到；每一项只能包含 skill_name、required、matched、match_score、evidence 这 5 个字段，不要输出 preferred、category、importance 等额外字段
            - strengths: 候选人明显的优势（与岗位相关的）
            - weaknesses: 候选人的不足或需要提升的地方
            - focus_areas: 基于匹配分析推荐的面试重点考察方向
            - resume_gaps: 简历中可以深挖或追问的空白点""";

    /**
     * 对候选人简历和岗位 JD 做匹配分析。
     *
     * <p>流程分两层：先用 embedding 在本地完成“JD 原子要求 -> 简历片段”的近邻匹配，
     * 得到可解释证据；再把本地匹配结果交给 LLM 生成最终结构化 JSON。
     * 如果 LLM 调用或 JSON 解析失败，则使用本地 embedding 匹配结果降级返回，避免面试流程中断。</p>
     *
     * @param jdAnalysis 已结构化的 JD 分析结果
     * @param resume     候选人简历对象
     * @return 简历与岗位的结构化匹配结果
     */
    public ResumeMatchResult match(JDAnalysis jdAnalysis, Resume resume) {
        log.info("[ResumeMatcher] 开始匹配简历与 JD: {}", jdAnalysis.getPosition());

        String jdSummary = formatJDForMatching(jdAnalysis);
        String resumeText = formatResumeForMatching(resume);
        LocalMatchAnalysis localAnalysis = analyzeWithEmbedding(jdAnalysis, resumeText);

        String userMsg = """
                ## 岗位 JD 分析结果
                %s
                
                ## 候选人简历
                %s
                
                ## 本地 embedding 近邻匹配结果
                %s
                
                请优先依据“本地 embedding 近邻匹配结果”中的逐项证据给出匹配分析。
                - matched 和 match_score 应以本地相似度、关键词命中、证据强弱为主要依据
                - evidence 必须来自候选人简历原文或本地匹配证据，不要编造
                - 如果某项没有可靠证据，matched=false，match_score 不要虚高
                - overall_score 可参考本地综合分，但允许结合项目质量和岗位相关性小幅调整
                """.formatted(jdSummary, resumeText, formatLocalAnalysis(localAnalysis));

        Prompt prompt = new Prompt(List.of(
                new SystemMessage(RESUME_MATCHER_PROMPT),
                new UserMessage(userMsg)
        ));

        try {
            ChatResponse response = chatModel.call(prompt);
            String content = response.getResult().getOutput().getText();
            String json = AgentUtils.extractJSON(content);
            ResumeMatchResult result = objectMapper.readValue(json, ResumeMatchResult.class);
            normalizeByLocalEvidence(result, localAnalysis);
            log.info("[ResumeMatcher] 匹配完成，综合得分: {}", result.getOverallScore());
            return result;
        } catch (Exception e) {
            log.warn("[ResumeMatcher] LLM 匹配结果解析失败，使用 embedding 本地匹配结果降级: {}", e.getMessage());
            return buildFallbackResult(localAnalysis);
        }
    }

    /**
     * 使用 embedding 计算 JD 要求和简历片段之间的语义匹配关系。
     *
     * <p>该方法会先拆出 JD 原子要求，再切分简历文本，分别生成向量；
     * 对每个 JD 要求找到最相似的简历片段，并计算加权综合分。</p>
     *
     * @param jdAnalysis JD 分析结果
     * @param resumeText 简历原始文本
     * @return 本地 embedding 匹配分析结果
     */
    private LocalMatchAnalysis analyzeWithEmbedding(JDAnalysis jdAnalysis, String resumeText) {
        List<JdRequirement> requirements = extractRequirements(jdAnalysis);
        List<String> chunks = splitResumeChunks(resumeText);

        if (requirements.isEmpty() || chunks.isEmpty()) {
            return new LocalMatchAnalysis(requirements, List.of(), 0.0);
        }

        try {
            List<ResumeChunkVector> chunkVectors = new ArrayList<>();
            for (String chunk : chunks) {
                chunkVectors.add(new ResumeChunkVector(chunk, embeddingModel.embed(chunk)));
            }

            List<RequirementMatch> matches = new ArrayList<>();
            for (JdRequirement requirement : requirements) {
                float[] reqVector = embeddingModel.embed(requirement.text());
                RequirementMatch best = findBestMatch(requirement, reqVector, chunkVectors);
                matches.add(best);
            }

            double overall = weightedOverallScore(matches);
            return new LocalMatchAnalysis(requirements, matches, overall);
        } catch (Exception e) {
            log.warn("[ResumeMatcher] embedding 匹配失败，降级为空本地证据: {}", e.getMessage());
            return new LocalMatchAnalysis(requirements, List.of(), 0.0);
        }
    }

    /**
     * 为单个 JD 要求在所有简历片段中寻找最佳匹配证据。
     *
     * <p>匹配分由向量余弦相似度和关键词命中奖励共同构成。
     * 返回值会保留原始余弦相似度、综合分、命中状态和最佳证据片段。</p>
     *
     * @param requirement  当前 JD 原子要求
     * @param reqVector    当前 JD 要求的 embedding 向量
     * @param chunkVectors 简历片段及其 embedding 向量
     * @return 当前 JD 要求对应的最佳简历匹配结果
     */
    private RequirementMatch findBestMatch(JdRequirement requirement, float[] reqVector,
                                           List<ResumeChunkVector> chunkVectors) {
        RequirementMatch best = null;
        for (ResumeChunkVector chunkVector : chunkVectors) {
            double similarity = cosineSimilarity(reqVector, chunkVector.vector());
            boolean keywordHit = containsKeyword(chunkVector.content(), requirement.keyword());
            double adjustedScore = Math.min(1.0, similarity + (keywordHit ? KEYWORD_BONUS : 0.0));
            RequirementMatch current = new RequirementMatch(
                    requirement,
                    chunkVector.content(),
                    similarity,
                    adjustedScore,
                    adjustedScore >= MATCH_THRESHOLD,
                    keywordHit
            );
            if (best == null || current.adjustedScore() > best.adjustedScore()) {
                best = current;
            }
        }
        return best != null ? best : new RequirementMatch(requirement, "", 0.0, 0.0, false, false);
    }

    /**
     * 从 JDAnalysis 中提取可匹配的原子要求。
     *
     * <p>当前会提取必备技能、加分技能、关键考察主题和岗位职责。
     * 使用 LinkedHashMap 去重并保留原始顺序，避免同一个技能重复参与打分。</p>
     *
     * @param jd JD 分析结果
     * @return JD 原子要求列表
     */
    private List<JdRequirement> extractRequirements(JDAnalysis jd) {
        Map<String, JdRequirement> requirements = new LinkedHashMap<>();

        if (jd.getRequiredSkills() != null) {
            for (JDAnalysis.SkillItem skill : jd.getRequiredSkills()) {
                addRequirement(requirements, skill.getName(), skill.getName(), true, "required_skill");
            }
        }
        if (jd.getPreferredSkills() != null) {
            for (JDAnalysis.SkillItem skill : jd.getPreferredSkills()) {
                addRequirement(requirements, skill.getName(), skill.getName(), false, "preferred_skill");
            }
        }
        if (jd.getKeyTopics() != null) {
            for (String topic : jd.getKeyTopics()) {
                addRequirement(requirements, topic, topic, true, "key_topic");
            }
        }
        if (jd.getResponsibilities() != null) {
            for (String responsibility : jd.getResponsibilities()) {
                addRequirement(requirements, responsibility, responsibility, true, "responsibility");
            }
        }

        return new ArrayList<>(requirements.values());
    }

    /**
     * 向 JD 要求集合中加入一条要求，并按归一化文本去重。
     *
     * @param requirements JD 要求集合，key 为归一化后的文本
     * @param text         要求原文
     * @param keyword      用于关键词命中的核心词
     * @param required     是否为必需项
     * @param source       要求来源，例如 required_skill、preferred_skill
     */
    private void addRequirement(Map<String, JdRequirement> requirements, String text,
                                String keyword, boolean required, String source) {
        if (text == null || text.isBlank()) {
            return;
        }
        String normalized = normalize(text);
        requirements.putIfAbsent(normalized, new JdRequirement(text.trim(), keyword, required, source));
    }

    /**
     * 将简历原文切分成适合做 embedding 的片段。
     *
     * <p>优先按空行、项目符号、编号列表等结构边界切分；
     * 对过长片段继续按句子切分，避免单个 embedding 文本过长导致语义被稀释。</p>
     *
     * @param resumeText 简历原始文本
     * @return 去重、截断后的简历片段列表
     */
    private List<String> splitResumeChunks(String resumeText) {
        if (resumeText == null || resumeText.isBlank()) {
            return List.of();
        }

        List<String> chunks = new ArrayList<>();
        for (String part : SPLIT_PATTERN.split(resumeText)) {
            String normalized = part.replaceAll("[ \\t]+", " ").trim();
            if (normalized.length() < MIN_CHUNK_LENGTH) {
                continue;
            }
            if (normalized.length() <= MAX_CHUNK_LENGTH) {
                chunks.add(normalized);
            } else {
                chunks.addAll(splitLongChunk(normalized));
            }
        }

        if (chunks.isEmpty()) {
            chunks.addAll(splitLongChunk(resumeText.trim()));
        }

        return chunks.stream()
                .filter(s -> s.length() >= MIN_CHUNK_LENGTH)
                .distinct()
                .limit(MAX_RESUME_CHUNKS)
                .toList();
    }

    /**
     * 将超长简历片段继续按句子切成多个小片段。
     *
     * @param text 超过最大长度的简历片段
     * @return 切分后的片段列表
     */
    private List<String> splitLongChunk(String text) {
        List<String> chunks = new ArrayList<>();
        String[] sentences = text.split("(?<=[。！？；;.!?])");
        StringBuilder current = new StringBuilder();
        for (String sentence : sentences) {
            String s = sentence.trim();
            if (s.isEmpty()) {
                continue;
            }
            if (current.length() + s.length() > MAX_CHUNK_LENGTH && !current.isEmpty()) {
                chunks.add(current.toString().trim());
                current.setLength(0);
            }
            current.append(s);
        }
        if (!current.isEmpty()) {
            chunks.add(current.toString().trim());
        }
        return chunks;
    }

    /**
     * 根据各类 JD 要求的重要程度计算本地综合匹配分。
     *
     * <p>必备技能权重最高，关键主题和岗位职责次之，加分技能权重较低。
     * 返回值范围为 0-100。</p>
     *
     * @param matches 每个 JD 要求的最佳匹配结果
     * @return 加权综合匹配分
     */
    private double weightedOverallScore(List<RequirementMatch> matches) {
        if (matches == null || matches.isEmpty()) {
            return 0.0;
        }
        double weightedSum = 0.0;
        double totalWeight = 0.0;
        for (RequirementMatch match : matches) {
            double weight = switch (match.requirement().source()) {
                case "required_skill" -> 3.0;
                case "key_topic" -> 2.0;
                case "responsibility" -> 1.5;
                case "preferred_skill" -> 1.0;
                default -> 1.0;
            };
            weightedSum += toMatchScore(match) * weight;
            totalWeight += weight;
        }
        return totalWeight == 0 ? 0.0 : round1(weightedSum / totalWeight);
    }

    /**
     * 将本地 embedding 匹配结果格式化为 LLM 可读的证据说明。
     *
     * <p>该文本会作为提示词的一部分交给 LLM，约束 LLM 基于真实证据生成最终 JSON，
     * 降低凭空判断和编造简历证据的概率。</p>
     *
     * @param analysis 本地 embedding 匹配分析结果
     * @return 面向 LLM 的文本化匹配证据
     */
    private String formatLocalAnalysis(LocalMatchAnalysis analysis) {
        if (analysis.matches().isEmpty()) {
            return "无可用 embedding 匹配结果，本轮只能基于原始 JD 和简历判断。";
        }

        String items = analysis.matches().stream()
                .sorted(Comparator.comparing((RequirementMatch m) -> m.requirement().required()).reversed()
                        .thenComparing(m -> m.requirement().text()))
                .map(match -> """
                        - JD要求：%s
                          来源：%s，必需：%s
                          原始余弦相似度：%.4f，关键词命中：%s，综合匹配分：%.1f
                          是否匹配：%s
                          最相关简历证据：%s
                        """.formatted(
                        match.requirement().text(),
                        match.requirement().source(),
                        match.requirement().required(),
                        match.cosineSimilarity(),
                        match.keywordHit(),
                        toMatchScore(match),
                        match.matched(),
                        trimEvidence(match.evidence(), 260)
                ))
                .collect(Collectors.joining("\n"));

        return "本地综合匹配分：" + analysis.overallScore() + "\n" + items;
    }

    /**
     * 在 LLM 不可用或输出不可解析时，根据本地 embedding 结果构造降级匹配结果。
     *
     * <p>该方法保证 WebSocket 面试主流程不会因为简历匹配阶段的 LLM 异常直接失败。</p>
     *
     * @param analysis 本地 embedding 匹配分析结果
     * @return 降级生成的简历匹配结果
     */
    private ResumeMatchResult buildFallbackResult(LocalMatchAnalysis analysis) {
        List<ResumeMatchResult.SkillMatch> skillMatches = analysis.matches().stream()
                .filter(match -> match.requirement().source().endsWith("_skill"))
                .map(match -> ResumeMatchResult.SkillMatch.builder()
                        .skillName(match.requirement().text())
                        .required(match.requirement().required())
                        .matched(match.matched())
                        .matchScore(toMatchScore(match))
                        .evidence(match.matched() ? trimEvidence(match.evidence(), 180) : "未在简历中找到可靠证据")
                        .build())
                .toList();

        List<String> strengths = analysis.matches().stream()
                .filter(match -> match.adjustedScore() >= STRONG_MATCH_THRESHOLD)
                .limit(4)
                .map(match -> match.requirement().text())
                .toList();

        List<String> weaknesses = analysis.matches().stream()
                .filter(match -> match.requirement().required() && !match.matched())
                .limit(4)
                .map(match -> match.requirement().text())
                .toList();

        List<String> focusAreas = analysis.matches().stream()
                .filter(match -> match.requirement().required() && match.adjustedScore() < STRONG_MATCH_THRESHOLD)
                .limit(5)
                .map(match -> match.requirement().text())
                .toList();

        List<String> resumeGaps = weaknesses.stream()
                .map(item -> item + "：简历中缺少明确证据，面试时建议追问")
                .toList();

        return ResumeMatchResult.builder()
                .overallScore(analysis.overallScore())
                .skillMatch(skillMatches)
                .strengths(strengths)
                .weaknesses(weaknesses)
                .focusAreas(focusAreas)
                .resumeGaps(resumeGaps)
                .build();
    }

    /**
     * 对 LLM 返回的匹配结果做基础归一化和兜底补全。
     *
     * <p>如果 LLM 漏掉技能匹配列表或部分集合字段为空，则使用本地 embedding 结果补齐，
     * 避免后续流程或前端渲染出现空指针问题。</p>
     *
     * @param result   LLM 解析出的匹配结果
     * @param analysis 本地 embedding 匹配分析结果
     */
    private void normalizeByLocalEvidence(ResumeMatchResult result, LocalMatchAnalysis analysis) {
        if (result.getSkillMatch() == null || result.getSkillMatch().isEmpty()) {
            result.setSkillMatch(buildFallbackResult(analysis).getSkillMatch());
        }
        if (result.getOverallScore() <= 0 && analysis.overallScore() > 0) {
            result.setOverallScore(analysis.overallScore());
        }
        if (result.getStrengths() == null) result.setStrengths(List.of());
        if (result.getWeaknesses() == null) result.setWeaknesses(List.of());
        if (result.getFocusAreas() == null) result.setFocusAreas(List.of());
        if (result.getResumeGaps() == null) result.setResumeGaps(List.of());
    }

    /**
     * 将 0-1 范围的综合相似度转换成 0-100 的匹配分。
     *
     * @param match 单条 JD 要求的匹配结果
     * @return 一位小数的百分制匹配分
     */
    private double toMatchScore(RequirementMatch match) {
        return round1(Math.clamp(match.adjustedScore() * 100.0, 0.0, 100.0));
    }

    /**
     * 计算两个 embedding 向量的余弦相似度。
     *
     * <p>结果越接近 1 表示语义越接近；维度不一致、空向量或零向量时返回 0。</p>
     *
     * @param a 向量 A
     * @param b 向量 B
     * @return 余弦相似度
     */
    private double cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length || a.length == 0) {
            return 0.0;
        }

        double dot = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0.0 || normB == 0.0) {
            return 0.0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    /**
     * 判断简历片段中是否直接包含 JD 要求的关键词。
     *
     * <p>关键词命中用于弥补纯语义相似度对技术名词、框架名、数据库名等专有词不够敏感的问题。</p>
     *
     * @param text    简历片段文本
     * @param keyword JD 要求关键词
     * @return 是否命中关键词
     */
    private boolean containsKeyword(String text, String keyword) {
        if (text == null || keyword == null || keyword.isBlank()) {
            return false;
        }
        return normalize(text).contains(normalize(keyword));
    }

    /**
     * 对文本做轻量归一化，便于去重和关键词匹配。
     *
     * @param text 原始文本
     * @return 小写且去掉空白符后的文本
     */
    private String normalize(String text) {
        return text == null ? "" : text.toLowerCase().replaceAll("\\s+", "");
    }

    /**
     * 裁剪简历证据文本，避免 LLM 提示词和前端展示过长。
     *
     * @param evidence  原始证据文本
     * @param maxLength 最大保留长度
     * @return 裁剪后的证据文本
     */
    private String trimEvidence(String evidence, int maxLength) {
        if (evidence == null || evidence.isBlank()) {
            return "未找到可靠证据";
        }
        String normalized = evidence.replaceAll("\\s+", " ").trim();
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength) + "...";
    }

    /**
     * 将浮点数四舍五入到一位小数。
     *
     * @param value 原始数值
     * @return 保留一位小数的数值
     */
    private double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    /**
     * 将 JDAnalysis 序列化为适合放入提示词的 JSON 文本。
     *
     * @param jd JD 分析结果
     * @return JSON 格式的 JD 摘要；序列化失败时返回对象 toString
     */
    private String formatJDForMatching(JDAnalysis jd) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(jd);
        } catch (Exception e) {
            return jd.toString();
        }
    }

    /**
     * 获取适合匹配分析的简历文本。
     *
     * <p>优先使用 rawText，保留用户上传/粘贴的原始简历内容；
     * 如果 rawText 为空，则把结构化 Resume 对象序列化为 JSON。</p>
     *
     * @param resume 简历对象
     * @return 简历文本
     */
    private String formatResumeForMatching(Resume resume) {
        if (resume.getRawText() != null && !resume.getRawText().isEmpty()) {
            return resume.getRawText();
        }
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(resume);
        } catch (Exception e) {
            return resume.toString();
        }
    }

    /**
     * JD 中可独立匹配的一个原子要求。
     *
     * @param text     要求原文
     * @param keyword  用于关键词匹配的核心词
     * @param required 是否为必需项
     * @param source   要求来源
     */
    private record JdRequirement(String text, String keyword, boolean required, String source) {
    }

    /**
     * 简历片段及其 embedding 向量。
     *
     * @param content 简历片段原文
     * @param vector  片段对应的 embedding 向量
     */
    private record ResumeChunkVector(String content, float[] vector) {
    }

    /**
     * 单条 JD 要求和最佳简历片段之间的匹配结果。
     *
     * @param requirement      JD 原子要求
     * @param evidence         最相关的简历证据片段
     * @param cosineSimilarity 原始余弦相似度
     * @param adjustedScore    叠加关键词奖励后的综合相似度，范围 0-1
     * @param matched          是否达到匹配阈值
     * @param keywordHit       是否直接命中关键词
     */
    private record RequirementMatch(JdRequirement requirement, String evidence, double cosineSimilarity,
                                    double adjustedScore, boolean matched, boolean keywordHit) {
    }

    /**
     * 本地 embedding 匹配分析的整体结果。
     *
     * @param requirements JD 原子要求列表
     * @param matches      每条要求对应的最佳匹配结果
     * @param overallScore 本地加权综合匹配分，范围 0-100
     */
    private record LocalMatchAnalysis(List<JdRequirement> requirements, List<RequirementMatch> matches,
                                      double overallScore) {
    }
}
