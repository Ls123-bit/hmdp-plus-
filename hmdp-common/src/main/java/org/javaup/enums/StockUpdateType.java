package org.javaup.enums;

import lombok.Getter;

/**
 * @program: 黑马点评-plus升级版实战项目。添加 阿星不是程序员 微信，添加时备注 点评 来获取项目的完整资料
 * @description: 库存操作类型
 * @author: 阿星不是程序员
 **/
public enum StockUpdateType {
    /**
     * 库存操作类型
     * */
    DECREASE(-1, "扣减"),
    
    INCREASE(1, "增加"),
    ;
    
    @Getter
    private final Integer code;
    
    private String msg = "";
    
    StockUpdateType(Integer code, String msg) {
        this.code = code;
        this.msg = msg;
    }
    
    public String getMsg() {
        return this.msg == null ? "" : this.msg;
    }
    
    public static String getMsg(Integer code) {
        for (StockUpdateType re : StockUpdateType.values()) {
            if (re.code.intValue() == code.intValue()) {
                return re.msg;
            }
        }
        return "";
    }
    
    public static StockUpdateType getRc(Integer code) {
        for (StockUpdateType re : StockUpdateType.values()) {
            if (re.code.intValue() == code.intValue()) {
                return re;
            }
        }
        return null;
    }
}
