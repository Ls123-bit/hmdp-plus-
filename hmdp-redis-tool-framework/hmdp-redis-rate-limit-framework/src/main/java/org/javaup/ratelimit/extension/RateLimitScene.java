package org.javaup.ratelimit.extension;

/**
 * @program: 黑马点评-plus升级版实战项目。添加 阿星不是程序员 微信，添加时备注 点评 来获取项目的完整资料
 * @description: 限流场景
 * @author: 阿星不是程序员
 **/
//限流场景枚举 （发令牌接口和下单接口）
public enum RateLimitScene {
    /** 发令牌接口 */
    ISSUE_TOKEN,
    /** 下单（秒杀）接口 */
    SECKILL_ORDER
}