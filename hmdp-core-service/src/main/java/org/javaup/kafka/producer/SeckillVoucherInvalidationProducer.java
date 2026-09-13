package org.javaup.kafka.producer;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.javaup.AbstractProducerHandler;
import org.javaup.kafka.message.SeckillVoucherInvalidationMessage;
import org.javaup.message.MessageExtend;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.support.PropertiesLoaderSupport;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * @program: 黑马点评-plus升级版实战项目。添加 阿星不是程序员 微信，添加时备注 点评 来获取项目的完整资料
 * @description: Kafka 生产者：广播“秒杀券缓存失效”消息
 * @author: 阿星不是程序员
 **/
@Slf4j
@Component
public class SeckillVoucherInvalidationProducer extends AbstractProducerHandler<MessageExtend<SeckillVoucherInvalidationMessage>> {
    
    private final static String RETRY_COUNT = "retryCount";
    
    private final static String DLQ = ".DLQ";
    
    @Autowired
    private PropertiesLoaderSupport propertiesLoaderSupport;
    
    public SeckillVoucherInvalidationProducer(final KafkaTemplate<String, MessageExtend<SeckillVoucherInvalidationMessage>> kafkaTemplate) {
        super(kafkaTemplate);
    }
   
    @Resource
    private MeterRegistry meterRegistry;
    
    @Value("${seckill.cache.invalidate.retry.maxAttempts:3}")
    private int retryMaxAttempts;
    
    @Value("${seckill.cache.invalidate.retry.initialBackoffMillis:200}")
    private long initialBackoffMillis;
    
    @Value("${seckill.cache.invalidate.retry.maxBackoffMillis:800}")
    private long maxBackoffMillis;
    
    private static final Logger auditLog = LoggerFactory.getLogger("AUDIT");//审计日志，记录上报成功的


    /**
     * 发送失败处理：结构化日志，指标，自适应退避重试，超限后转交DLQ  https://www.doubao.com/thread/w7ca63c1def4ac74d
     *
     * @param topic
     * @param message
     * @param throwable
     */

    @Override               //发送失败
    //发送失败的流程：1.重试，（用MQ组件实现）设置重试次数    2。MQ中设置死信队列  当重试次数达到最大次数的时候，再放入死信队列
    protected void afterSendFailure(final String topic, final MessageExtend<SeckillVoucherInvalidationMessage> message, final Throwable throwable) {

       //1。结构化日志，便于定位问题
        final SeckillVoucherInvalidationMessage body = message.getMessageBody();//先获得消息体
        //下面是获取必要的参数
        final Long voucherId = body.getVoucherId();
        final String reason = body.getReason();
        final String errMsg = throwable == null ? "unknown" : throwable.getMessage();
        //打印日志错误，表示消费失败了
        log.error("SeckillVoucherInvalidation send failed, topic={}, uuid={}, key={}, voucherId={}, reason={}, error= {}",
                topic, message.getUuid(), message.getKey(), voucherId, reason, errMsg, throwable);





        if (topic.contains(DLQ)) {//如果死信队列也发送失败，直接统计到失败指标中
            safeInc("seckill_invalidation_dlq", "reason", "send_failures");
            return;
        }else {//指标：失败计数
            safeInc("seckill_invalidation_send_failures", "topic", topic);//打印完日志，上报指标
        }


        //2.失败重试（可配置次数+退避），通过header标记重试次数避免无限递归

        //请求头里面可以知道重试的次数，要知道重试几次，每重试一次，重试次数要加一，
        //组装请求头
        Map<String, String> headers = message.getHeaders();
        headers = headers == null ? new HashMap<>(8) : new HashMap<>(headers);
        int retryCount = 0;//重试次数的计数，初始为零
        try {
            if (headers.containsKey(RETRY_COUNT)) {//如果包含了重试次数的key   正常的重试次数，下面消费成功的重试次数dlqReplay是死信队列的重试次数
                retryCount = Integer.parseInt(headers.get(RETRY_COUNT));
            }
        } catch (Exception ignore) {
        }//（重试准备，复制或创建headers，解析retryCount，解析失败则按零处理）


        //自适应退避重试
        if (retryCount < retryMaxAttempts) {//如果重试次数小于最大重试次数
            long backoff = Math.min(initialBackoffMillis * (1L << retryCount), maxBackoffMillis);//重试的间隔时间，计算退避时间
            headers.put(RETRY_COUNT, String.valueOf(retryCount + 1));//请求头里面重试次数要加一，因为当前是失败的，
            headers.put("lastError", truncate(errMsg));//错误信息放进去
            message.setHeaders(headers);//消息体塞进请求头，重试次数要加一，错误信息要进去，重试次数要加一
            //重试打印日志
            log.warn("Retry sending cache invalidation, topic={}, uuid={}, voucherId={}, retryCount={}, backoffMs={}",
                    topic, message.getUuid(), voucherId, retryCount + 1, backoff);
           //打印完日志，上报指标
            safeInc("seckill_invalidation_send_retries", "topic", topic);

            sleepQuietly(backoff);//上报完指标休眠一下（简单退避等待后重试）等待上面的退避时间    安静休眠 sleepQuietly(backoffMs) 捕获中断仅设置标记。
            //异步重试：若再失败会再次进入本方法，直接超过最大重试次数   若再次失败，会按同样的流程进入aftersendfailure

            sendRecord(topic, message);//重试发送到kafka
            return;
        }




        //放入DLQ，便于后续人工/自动补偿（超限转入死信队列）

        //执行到这还不行，发送到死信队列，死信队列里还不行，没必要重试了，确实不行，
        final String dlqReason = "send_invalid_cache_broadcast_failed: " + truncate(errMsg);//构建dlqreason，包含最近的错误摘要  truncate（s）限制头/日志长度
        try {
            sendToDlq(topic, body, dlqReason);//发送到死信队列 调用 sendToDlq(topic, body, dlqReason) ；这里就是“发送到 DLQ”，无需讲解其原理。
            log.warn("Send cache invalidation to DLQ, originalTopic={}, uuid={}, voucherId={}, dlqReason={}",
                    topic, message.getUuid(), voucherId, dlqReason);
            ///记录审计日志与指标 DLQ_PUBLISH 审计、
            auditLog.warn("DLQ_PUBLISH|topic={}|uuid={}|key={}|voucherId={}|reason={}",
                    topic, message.getUuid(), message.getKey(), voucherId, dlqReason);
            safeInc("seckill_invalidation_send_dlq", "topic", topic);//seckill_invalidation_send_dlq 成功
        } catch (Exception e) {
            log.error("Send cache invalidation to DLQ failed, originalTopic={}, uuid={}, voucherId={}, error={}",
                    topic, message.getUuid(), voucherId, e.getMessage(), e);
            //死信队列里还不行，上报给普罗米修斯   若发送 DLQ 失败则记 seckill_invalidation_send_dlq_failures 。
            safeInc("seckill_invalidation_send_dlq_failures", "topic", topic);
        }
    }
    //发送成功的流程：1.在普罗米修斯上报一下，（引入用：meterRegistry）
    @Override
    protected void afterSendSuccess(SendResult<String, MessageExtend<SeckillVoucherInvalidationMessage>> result) {
        super.afterSendSuccess(result);
        String topic = result.getRecordMetadata().topic();//获得topic
        MessageExtend<SeckillVoucherInvalidationMessage> message = result.getProducerRecord().value();//获得消息体
       //判断是否是死信队列   死信队列的标识：是死信队列，消息头是又一个内容的，要取一下，看看消息头是不是有东西，
        boolean dlqReplay = message != null && message.getHeaders() != null && "1".equals(message.getHeaders().getOrDefault("dlqReplayCount", "0"));//获取消息头
        safeInc("seckill_invalidation_send_success", "topic", topic);//上报“成功”
        if (dlqReplay) {//如果是死信队列，就上报“死信队列重试成功”    如果死信队列的标识是有的
            safeInc("seckill_invalidation_dlq_replay_success", "topic", topic);
            //审计日志，记录上报成功的死信队列重试成功的消息
            auditLog.info("DLQ_REPLAY_SUCCESS|topic={}|uuid={}|key={}|voucherId={}",
                    topic, message.getUuid(), message.getKey(), message.getMessageBody().getVoucherId());
        }
    }

    private String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() <= 256 ? s : s.substring(0, 256);
    }
    
    private void sleepQuietly(long backoffMs) {//休眠时间，隔一段时间来重试
        try {
            TimeUnit.MILLISECONDS.sleep(backoffMs);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
    //上报的方法
    private void safeInc(String name, String tagKey, String tagValue) {
        try {
            if (meterRegistry != null) {
                meterRegistry.counter(name, tagKey, tagValue).increment();
            }
        } catch (Exception ignore) {
        }
    }
}