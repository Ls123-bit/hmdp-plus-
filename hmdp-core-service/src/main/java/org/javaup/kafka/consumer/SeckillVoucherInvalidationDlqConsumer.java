package org.javaup.kafka.consumer;

import com.alibaba.fastjson.JSON;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.javaup.consumer.AbstractConsumerHandler;
import org.javaup.kafka.message.SeckillVoucherInvalidationMessage;
import org.javaup.message.MessageExtend;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Headers;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;

import static org.javaup.constant.Constant.SECKILL_VOUCHER_CACHE_INVALIDATION_TOPIC;
import static org.javaup.constant.Constant.SPRING_INJECT_PREFIX_DISTINCTION_NAME;

/**
 * @program: 黑马点评-plus升级版实战项目。添加 阿星不是程序员 微信，添加时备注 点评 来获取项目的完整资料
 * @description: 秒杀券缓存失效广播的 DLQ 消费者
 * @author: 阿星不是程序员
 **/
@Slf4j
@Component
public class SeckillVoucherInvalidationDlqConsumer extends AbstractConsumerHandler<SeckillVoucherInvalidationMessage> {
    
    @Resource
    private MeterRegistry meterRegistry;
    
    
    private static final Logger auditLog = LoggerFactory.getLogger("AUDIT");

 
    public SeckillVoucherInvalidationDlqConsumer() {
        super(SeckillVoucherInvalidationMessage.class);
    }
    //监听注解
    @KafkaListener(
            topics = {SPRING_INJECT_PREFIX_DISTINCTION_NAME + "-" + SECKILL_VOUCHER_CACHE_INVALIDATION_TOPIC + ".DLQ"},
            groupId = "${prefix.distinction.name:hmdp}-seckill_voucher_cache_invalidation_dlq-${random.uuid}"//组还是广播消费，死信队列也是要清理缓存，但是topic变了，有后缀
    )

    public void onMessage(String value,
                          @Headers Map<String, Object> headers,
                          @Header(name = KafkaHeaders.RECEIVED_KEY, required = false) String key,
                          Acknowledgment acknowledgment) {//加个Acknowledgment  手动提交offset
        //先执行业务消费逻辑 如果doConsumer抛异常，方法不会走到acknowledge
        consumeRaw(value, key, headers);
        if (acknowledgment != null) {//只有当业务成功返回，才提交offset
            acknowledgment.acknowledge();
        }
    }


    /**
     * 对进入死信队列的秒杀券缓存失败消息保留完整审计与指标
     * 已经进入死信队列了，其实也没必要再进行重试了，只能上报指标和记录错误
     * @param message
     */



    //执行消费，首先获得消息体
    @Override
    protected void doConsume(MessageExtend<SeckillVoucherInvalidationMessage> message) {
        SeckillVoucherInvalidationMessage body = message.getMessageBody();
        if (Objects.isNull(body.getVoucherId())) {
            log.warn("DLQ消息载荷为空或voucherId缺失, uuid={}", message.getUuid());
            //上报一下
            safeInc("seckill_invalidation_dlq_replay_skipped", "reason", "invalid_payload");
            return;
        }//消息不为空的话，往下进行，  到死信队列里面没有什么好重试的，前面重试那么多回都不行，发送到死信队列里面消费成功了，说明刚刚重试的过程中，kafka是有问题的，可能达到了峰值，带宽不够，不稳定，放入死信队列，正好成功了，成功消费了，这种情况太极端了，


        //下面这两步：方便运维和开发人员后期排查和审理问题
        //上报指标 带死信队列后缀的，
        safeInc("seckill_invalidation_dlq", "reason", "invalid_payload");
         //审计日志 记录一下
        auditLog.error("SECKILL_INVALIDATION_DLQ | message={}", JSON.toJSONString(message));
    }







    private void safeInc(String name, String tagKey, String tagValue) {
        try {//要捕获，不可以影响主流程
            if (meterRegistry != null) {
                meterRegistry.counter(name, tagKey, tagValue).increment();
            }
        } catch (Exception ignore) {
        }
    }
}
