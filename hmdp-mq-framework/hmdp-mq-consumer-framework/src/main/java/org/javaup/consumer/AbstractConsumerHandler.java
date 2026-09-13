package org.javaup.consumer;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.javaup.message.MessageExtend;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;


/**
 * @program: 黑马点评-plus升级版实战项目。添加 阿星不是程序员 微信，添加时备注 点评 来获取项目的完整资料
 * @description: Kafka 抽象基类。
 * @author: 阿星不是程序员
 **/
@Slf4j
@RequiredArgsConstructor
public abstract class AbstractConsumerHandler<T> {
    
    private final Class<T> payloadType;
    
    public final void consumeRaw(String value, Map<String, Object> headers) {
        MessageExtend<T> message = convert(value, toStringHeaders(headers));
        consume(message);
    }
    
    public final void consumeRaw(String value, String key, Map<String, Object> headers) {
        MessageExtend<T> message = convert(value, toStringHeaders(headers));
        message.setKey(key);
        consume(message);
    }
    
    public final void consume(MessageExtend<T> message) {
        Boolean result = beforeConsume(message);//消费前
        try {
            if (result) {
                doConsume(message);//执行消费
            }
        } catch (Throwable t) {
            afterConsumeFailure(message, t);
            throw t;
        }
        afterConsumeSuccess(message);//消费成功
    }
   
    protected Boolean beforeConsume(MessageExtend<T> message) {
        log.info("kafka message before consume success, uuid={}, key={}", message.getUuid(), message.getKey());
        return true;
    }
    
    protected abstract void doConsume(MessageExtend<T> message);
    
    protected void afterConsumeSuccess(MessageExtend<T> message) {
        log.info("kafka message consume success, uuid={}, key={}", message.getUuid(), message.getKey());
    }
    
    protected void afterConsumeFailure(MessageExtend<T> message, Throwable throwable) {
        log.error("kafka message consume failed, uuid={}, key={}, messageBody={}",
                message.getUuid(), message.getKey(), JSON.toJSONString(message.getMessageBody()), throwable);
    }
    
    protected Map<String, String> toStringHeaders(Map<String, Object> headers) {
        Map<String, String> map = new HashMap<>();
        if (headers == null || headers.isEmpty()) {
            return map;
        }
        headers.forEach((k, v) -> {
            if (v == null) {
                return;
            }
            if (v instanceof byte[] bytes) {
                map.put(k, new String(bytes, StandardCharsets.UTF_8));
            } else {
                map.put(k, v.toString());
            }
        });
        return map;
    }
    
    public MessageExtend<T> convert(String value, Map<String, String> headers) {
        JSONObject root = JSON.parseObject(value);
        Object rawBody = root.get("messageBody");
        T body = rawBody == null ? null : JSON.parseObject(JSON.toJSONString(rawBody), payloadType);
        
        MessageExtend<T> message = new MessageExtend<>(body);
        message.setKey(root.getString("key"));
        if (headers != null && !headers.isEmpty()) {
            message.setHeaders(headers);
        }
        String uuid = root.getString("uuid");
        if (uuid != null) {
            message.setUuid(uuid);
        }
        Date producerTime = root.getDate("producerTime");
        if (producerTime != null) {
            message.setProducerTime(producerTime);
        }
        return message;
    }
}