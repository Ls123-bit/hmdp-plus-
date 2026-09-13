package org.javaup.enums;

import lombok.Getter;

/**
 * @program: 黑马点评-plus升级版实战项目。添加 阿星不是程序员 微信，添加时备注 点评 来获取项目的完整资料
 * @description: 是否删除秒杀优惠券订单记录
 * @author: 阿星不是程序员
 **/
public enum SubscribeStatus {
    /**
     * 是否删除秒杀优惠券订单记录
     * */
    UNSUBSCRIBED(0, "已取消订阅或未订阅"),
    
    SUBSCRIBED(1, "已订阅到券提醒（在队列中）"),
    
    SUCCESS(2,"自动发券已成功（已创建订单）")
    ;
    
    @Getter
    private final Integer code;
    
    private String msg = "";
    
    SubscribeStatus(Integer code, String msg) {
        this.code = code;
        this.msg = msg;
    }
    
    public String getMsg() {
        return this.msg == null ? "" : this.msg;
    }
    
    public static String getMsg(Integer code) {
        for (SubscribeStatus re : SubscribeStatus.values()) {
            if (re.code.intValue() == code.intValue()) {
                return re.msg;
            }
        }
        return "";
    }
    
    public static SubscribeStatus getRc(Integer code) {
        for (SubscribeStatus re : SubscribeStatus.values()) {
            if (re.code.intValue() == code.intValue()) {
                return re;
            }
        }
        return null;
    }
}
