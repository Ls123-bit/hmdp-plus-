package org.javaup.dto;

import lombok.Data;

import java.util.List;

/**
 * @program: hmdp-plus
 * @description: 智能商户推荐客服-回复结果（新增功能，独立VO，不影响原有业务）
 * @author: hmdp-plus
 **/
@Data
public class AssistantReplyVO {

    /**
     * 客服回复文本
     */
    private String reply;

    /**
     * 回复产生方式：ollama=开源大模型生成，rule=本地规则引擎兜底
     */
    private String mode;

    /**
     * 本次识别的意图：greeting/help/voucher/shop_info/recommend
     */
    private String intent;

    /**
     * 命中的商户类型名称（可能为空）
     */
    private String typeName;

    /**
     * 推荐的商户列表，前端渲染成卡片，可点击跳转商户详情
     */
    private List<?> shops;
}
