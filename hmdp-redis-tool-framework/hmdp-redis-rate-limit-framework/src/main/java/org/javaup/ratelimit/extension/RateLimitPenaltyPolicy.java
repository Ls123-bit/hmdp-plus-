package org.javaup.ratelimit.extension;

import org.javaup.enums.BaseCode;

/**
 * @program: 黑马点评-plus升级版实战项目。添加 阿星不是程序员 微信，添加时备注 点评 来获取项目的完整资料
 * @description: 惩罚策略扩展点：在命中限流后可执行封禁、打标、告警等动作。
 * @author: 阿星不是程序员
 **/
public interface RateLimitPenaltyPolicy {

    /**
     * 应用惩罚策略
     * @param ctx    当前限流上下文
     * @param reason 命中原因（IP/USER 限流等）
     */
    void apply(RateLimitContext ctx, BaseCode reason);
}