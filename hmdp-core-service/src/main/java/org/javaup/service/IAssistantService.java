package org.javaup.service;

import org.javaup.dto.ChatRequestDTO;
import org.javaup.dto.Result;

/**
 * @program: hmdp-plus
 * @description: 智能商户推荐客服 接口（新增功能，只读查询商户数据，不改动原有业务逻辑）
 * @author: hmdp-plus
 **/
public interface IAssistantService {

    /**
     * 与智能客服对话：识别意图 -> 检索商户候选 -> 大模型生成/规则兜底回复
     *
     * @param request 用户消息与上下文
     * @return 回复文本 + 推荐商户卡片
     */
    Result chat(ChatRequestDTO request);

    /**
     * 客服能力状态（大模型是否可用等），供前端展示
     */
    Result status();
}
