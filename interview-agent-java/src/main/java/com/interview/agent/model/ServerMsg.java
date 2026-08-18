package com.interview.agent.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * WebSocket 服务端消息。
 * 使用 NON_NULL 策略：空字段不序列化。
 *
 * @author 陈龙强
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ServerMsg {
    /**
     * 消息类型（chat_reply/stage_change/question/review_item/memory_weak_points/question_directions/question_plan_details/score）
     */
    private String type;

    /**
     * 流式消息所属的业务类型，例如 chat_reply/report/review_plan。
     */
    private String streamType;

    /**
     * 单次流式输出的唯一 ID，用于前端把 chunk 追加到正确的消息气泡。
     */
    private String streamId;

    /**
     * 流式增量内容。
     */
    private String delta;

    /**
     * 流式消息是否结束。
     */
    private Boolean done;

    /**
     * 消息内容
     */
    private String content;

    /**
     * 当前阶段
     */
    private String stage;

    /**
     * 消息文本
     */
    private String message;

    /**
     * 当前题目编号
     */
    @JsonProperty("question_num")
    private Integer questionNum;

    /**
     * 得分
     */
    private Double score;

    /**
     * 反馈
     */
    private String feedback;

    /**
     * 答对的要点列表
     */
    @JsonProperty("key_points_hit")
    private List<String> keyPointsHit;

    /**
     * 答漏的要点列表
     */
    @JsonProperty("key_points_missed")
    private List<String> keyPointsMissed;
}
