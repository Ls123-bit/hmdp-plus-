package org.javaup.enums;

import lombok.Getter;

/**
 * @program: 黑马点评-plus升级版实战项目。添加 阿星不是程序员 微信，添加时备注 点评 来获取项目的完整资料
 * @description: 是否删除秒杀优惠券订单记录
 * @author: 阿星不是程序员
 **/
public enum SeckillVoucherOrderOperate {//枚举，验证在redis中是否下单成功
    /**
     * 是否删除秒杀优惠券订单记录
     * */
    NO(0, "不删除"),
    YES(1, "删除"),
    ;
    
    @Getter
    private final Integer code;
    
    private String msg = "";
    
    SeckillVoucherOrderOperate(Integer code, String msg) {
        this.code = code;
        this.msg = msg;
    }
    
    public String getMsg() {
        return this.msg == null ? "" : this.msg;
    }
    
    public static String getMsg(Integer code) {
        for (SeckillVoucherOrderOperate re : SeckillVoucherOrderOperate.values()) {
            if (re.code.intValue() == code.intValue()) {
                return re.msg;
            }
        }
        return "";
    }
    
    public static SeckillVoucherOrderOperate getRc(Integer code) {
        for (SeckillVoucherOrderOperate re : SeckillVoucherOrderOperate.values()) {
            if (re.code.intValue() == code.intValue()) {
                return re;
            }
        }
        return null;
    }
}
