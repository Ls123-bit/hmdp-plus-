package org.javaup.kafka.consumer;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.javaup.cache.SeckillVoucherLocalCache;
import org.javaup.consumer.AbstractConsumerHandler;
import org.javaup.core.RedisKeyManage;
import org.javaup.kafka.message.SeckillVoucherInvalidationMessage;
import org.javaup.message.MessageExtend;
import org.javaup.redis.RedisCache;
import org.javaup.redis.RedisKeyBuild;
import org.javaup.servicelock.LockType;
import org.javaup.servicelock.annotion.ServiceLock;
import org.springframework.aop.framework.AopContext;
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
import static org.javaup.constant.DistributedLockConstants.UPDATE_SECKILL_VOUCHER_LOCK;

/**
 * @program: 黑马点评-plus升级版实战项目。添加 阿星不是程序员 微信，添加时备注 点评 来获取项目的完整资料
 * @description: Kafka 消费者：接收“秒杀券缓存失效”广播
 * @author: 阿星不是程序员
 **/
@Slf4j
@Component
public class SeckillVoucherInvalidationConsumer extends AbstractConsumerHandler<SeckillVoucherInvalidationMessage> {


    @Resource
    private SeckillVoucherLocalCache seckillVoucherLocalCache;
    
    @Resource
    private MeterRegistry meterRegistry;


    @Resource
    private RedisCache redisCache;
    //这段代码是 Kafka 消费者的构造函数，用于初始化父类并指定消息体的类型。  super()：调用父类 AbstractConsumerHandler 的构造函数。
    //SeckillVoucherInvalidationMessage.class：传入消息体的类型，告诉父类如何反序列化 Kafka 消息。
    public SeckillVoucherInvalidationConsumer() {
        super(SeckillVoucherInvalidationMessage.class);
    }



    //kafka消息入口，转交统一消费流程  https://www.doubao.com/thread/w84fd487229cc84c6
    @KafkaListener(//广播消费，每个实例他的消费组应该不一样，如果消费组一样，就是正常消费了，每个实例只消费一次
            topics = {SPRING_INJECT_PREFIX_DISTINCTION_NAME + "-" + SECKILL_VOUCHER_CACHE_INVALIDATION_TOPIC},
            groupId = "${prefix.distinction.name:hmdp}-seckill_voucher_cache_invalidation-${random.uuid}"        //random.uuid 每个实例唯一，意味着每个实例都在“独立的消费组”，从而实现“广播消费”——每个实例都会收到同一条消息（而不是在同一组内做负载均衡只给其中一个实例）。
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


    //核心消费：校验载荷——>本地缓存失效->redis幂等删除->记录日志
    //消费端逻辑就是清除自己的本地缓存和redis缓存，都没有数据库交互，执行失败的可能性太小，唯一执行失败的原因：redis宕机了，正好删除，但可能性也不大，删除是一瞬间删除也不是查询，，具体的设计思路看下面的afterconsumerfailer
    @Override       //消费失败重新投递死信队列，让他重试也是可以的，但是得看业务值不值这样去做，去占用MQ的资源，
    protected void doConsume(MessageExtend<SeckillVoucherInvalidationMessage> message) {
        SeckillVoucherInvalidationMessage body = message.getMessageBody();
        if (Objects.isNull(body.getVoucherId())) {
            log.warn("收到缓存失效消息但载荷为空或voucherId缺失, uuid={}", message.getUuid());
            return;
        }
        Long voucherId = body.getVoucherId();//入锅不为空，将优惠券id赋值给voucherId变量，


        /**
         * 这行的作用是从 Spring 中取出 seckillVoucherInvalidationConsumer 对象，这样才能让 Aop 功能生效，从而让写锁生效   解决AOP代理作用
         */
        ((SeckillVoucherInvalidationConsumer) AopContext.currentProxy()).delCache(voucherId);//使用AOP代理对象调用delCache方法，实现分布式锁的组件，因为分布式组件是基于spring aop实现的，所以执行方法必须是spring的代理对象，为下面的@ServiceLock做铺垫
    }
    //删除缓存的删除方法，（使用读写锁的写锁，因为删除缓存是一个写操作）
    @ServiceLock(lockType= LockType.Write,name = UPDATE_SECKILL_VOUCHER_LOCK,keys = {"#voucherId"})
    public void delCache(Long voucherId){
        //构建秒杀优惠券在redis中的缓存key，用于从redis中读取或操作秒杀优惠券的数据
        RedisKeyBuild seckillVoucherRedisKey =
                RedisKeyBuild.createRedisKey(RedisKeyManage.SECKILL_VOUCHER_TAG_KEY, voucherId);
        //清理本地缓存
        seckillVoucherLocalCache.invalidate(seckillVoucherRedisKey.getRelKey());
        //清理redis缓存（券详情，库存，空值）幂等删除
        redisCache.del(RedisKeyBuild.createRedisKey(RedisKeyManage.SECKILL_VOUCHER_TAG_KEY, voucherId));
        redisCache.del(RedisKeyBuild.createRedisKey(RedisKeyManage.SECKILL_STOCK_TAG_KEY, voucherId));
        redisCache.del(RedisKeyBuild.createRedisKey(RedisKeyManage.SECKILL_VOUCHER_NULL_TAG_KEY, voucherId));
        
    }
    
    @Override          //消费失败
    protected void afterConsumeFailure(final MessageExtend<SeckillVoucherInvalidationMessage> message, final Throwable throwable) {
        super.afterConsumeFailure(message, throwable);
        log.warn("删除Redis缓存失败 voucherId={}", message.getMessageBody().getVoucherId(), throwable);
        safeInc(errorTag(throwable));//下面两个为这里的上报做准备
    }
    //写一个上报的方法
    private void safeInc(String tagValue) {
        try {
            if (meterRegistry != null) {
                meterRegistry.counter("seckill_invalidation_consume_failures", "error", tagValue).increment();
            }
        } catch (Exception ignore) {
        }
    }
//获取错误信息
    private String errorTag(Throwable t) {
        return t == null ? "unknown" : t.getClass().getSimpleName();
    }
}
