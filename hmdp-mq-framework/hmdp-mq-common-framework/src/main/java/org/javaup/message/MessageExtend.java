package org.javaup.message;

import cn.hutool.core.date.DateTime;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

/**
 * @program: 黑马点评-plus升级版实战项目。添加 阿星不是程序员 微信，添加时备注 点评 来获取项目的完整资料
 * @description: 消息包装
 * @author: 阿星不是程序员
 **/
@Data
@NoArgsConstructor(force = true)
@AllArgsConstructor
@RequiredArgsConstructor
public final class MessageExtend<T> implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;
    
    @NonNull
    private T messageBody;
    
    private String key;
    
    private Map<String, String> headers;
    
    private String uuid = UUID.randomUUID().toString();
    
    private Date producerTime = DateTime.now();
    
    public static <T> MessageExtend<T> of(T body){
        return new MessageExtend<>(body);
    }
    
    public static <T> MessageExtend<T> of(T body, String key, Map<String, String> headers){
        MessageExtend<T> msg = new MessageExtend<>(body);
        msg.setKey(key);
        msg.setHeaders(headers);
        return msg;
    }
}
