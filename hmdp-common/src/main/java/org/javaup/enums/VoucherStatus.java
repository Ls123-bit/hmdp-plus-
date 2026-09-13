package org.javaup.enums;

import lombok.Getter;

/**
 * @program: 黑马点评-plus升级版实战项目。添加 阿星不是程序员 微信，添加时备注 点评 来获取项目的完整资料
 * @description: 优惠券状态
 * @author: 阿星不是程序员
 **/
public enum VoucherStatus {
    /**
     * 优惠券状态 
     * */
    AVAILABLE(1, "上架"),
    UNAVAILABLE(2, "下架"),
    EXPIRED(3, "过期");
    
    ;
    
    @Getter
    private final Integer code;
    
    private String msg = "";
    
    VoucherStatus(Integer code, String msg) {
        this.code = code;
        this.msg = msg;
    }
    
    public String getMsg() {
        return this.msg == null ? "" : this.msg;
    }
    
    public static String getMsg(Integer code) {
        for (VoucherStatus re : VoucherStatus.values()) {
            if (re.code.intValue() == code.intValue()) {
                return re.msg;
            }
        }
        return "";
    }
    
    public static VoucherStatus getRc(Integer code) {
        for (VoucherStatus re : VoucherStatus.values()) {
            if (re.code.intValue() == code.intValue()) {
                return re;
            }
        }
        return null;
    }
}
