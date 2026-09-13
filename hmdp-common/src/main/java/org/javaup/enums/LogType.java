package org.javaup.enums;

import lombok.Getter;

/**
 * @program: 黑马点评-plus升级版实战项目。添加 阿星不是程序员 微信，添加时备注 点评 来获取项目的完整资料
 * @description: 记录类型
 * @author: 阿星不是程序员
 **/
public enum LogType {
    /**
     * 记录类型
     * */
    DEDUCT(-1, "扣减"),
    
    RESTORE(1, "恢复"),
    ;
    
    @Getter
    private final Integer code;
    
    private String msg = "";
    
    LogType(Integer code, String msg) {
        this.code = code;
        this.msg = msg;
    }
    
    public String getMsg() {
        return this.msg == null ? "" : this.msg;
    }
    
    public static String getMsg(Integer code) {
        for (LogType re : LogType.values()) {
            if (re.code.intValue() == code.intValue()) {
                return re.msg;
            }
        }
        return "";
    }
    
    public static LogType getRc(Integer code) {
        for (LogType re : LogType.values()) {
            if (re.code.intValue() == code.intValue()) {
                return re;
            }
        }
        return null;
    }
}
