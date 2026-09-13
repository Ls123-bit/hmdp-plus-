package org.javaup.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * @program: hmdp-plus
 * @description: 智能商户推荐客服-聊天请求（新增功能，独立DTO，不影响原有业务）
 * @author: hmdp-plus
 **/
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatRequestDTO {

    /**
     * 用户发送的消息
     */
    private String message;

    /**
     * 简单的多轮上下文（可选）：元素形如 {"role":"user|assistant","content":"..."}
     */
    private List<Map<String, String>> history;
}
