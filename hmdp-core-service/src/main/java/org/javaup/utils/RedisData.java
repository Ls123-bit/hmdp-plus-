package org.javaup.utils;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * @program: 黑马点评-plus升级版实战项目。添加 阿星不是程序员 微信，添加时备注 点评 来获取项目的完整资料
 * @description: redis数据
 * @author: 阿星不是程序员
 **/
@Data
public class RedisData {
    private LocalDateTime expireTime;
    private Object data;
}
