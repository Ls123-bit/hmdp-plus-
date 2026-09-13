package org.javaup.kafka.producer;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.javaup.AbstractProducerHandler;
import org.javaup.enums.SeckillVoucherOrderOperate;
import org.javaup.kafka.message.SeckillVoucherMessage;
import org.javaup.kafka.redis.RedisVoucherData;
import org.javaup.message.MessageExtend;
import org.javaup.toolkit.SnowflakeIdGenerator;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * @program: 黑马点评-plus升级版实战项目。添加 阿星不是程序员 微信，添加时备注 点评 来获取项目的完整资料
 * @description: Kafka 生产者：发送秒杀券
 * @author: 阿星不是程序员
 **/
@Slf4j
@Component
//kafka生产者  发送秒杀券？               实现MQ发送的组件
public class SeckillVoucherProducer extends AbstractProducerHandler<MessageExtend<SeckillVoucherMessage>> {
    
    @Resource
    private SnowflakeIdGenerator snowflakeIdGenerator;
    
    
    @Resource
    private RedisVoucherData redisVoucherData;

    public SeckillVoucherProducer(final KafkaTemplate<String,MessageExtend<SeckillVoucherMessage>> kafkaTemplate) {
        super(kafkaTemplate);//
    }

    //发送失败的回调方法     发送失败redis要回滚回去，把库存加回去
    @Override
    protected void afterSendFailure(final String topic, final MessageExtend<SeckillVoucherMessage> message, final Throwable throwable) {
        super.afterSendFailure(topic, message, throwable);
        long traceId = snowflakeIdGenerator.nextId();//需要从分布式系统追踪和ID 生成的角度分析： 生成唯一 ID：snowflakeIdGenerator.nextId() 使用 雪花算法 生成一个全局唯一的 64 位整数 ID。
        //赋值给 traceId：将生成的唯一 ID 赋值给 traceId 变量，用于后续的请求追踪。 在分布式系统（特别是微服务架构）中，traceId 用于：
        //
        //请求链路追踪：标记一个请求的完整调用链路，从客户端到各个服务节点。
        //日志关联：将不同服务、不同节点的日志通过 traceId 关联起来，方便问题排查。
        //性能分析：通过 traceId 追踪请求的执行时间、调用路径等，进行性能分析。
        //分布式事务追踪：在分布式事务中，通过 traceId 关联不同服务的事务操作。
//        . 在 SeckillVoucherProducer 中的应用
//        SeckillVoucherProducer 作为 Kafka 消息生产者，生成 traceId 的目的可能是：
//
//        消息追踪：为发送的秒杀券相关消息添加唯一标识，便于追踪消息的发送和消费过程。
//        日志关联：在生产端和消费端的日志中使用同一个 traceId，方便排查消息处理问题。
//        分布式链路追踪：将 traceId 传递到后续的服务（如消费者服务），形成完整的调用链路。

        redisVoucherData.rollbackRedisVoucherData(//回滚秒杀券数据
                SeckillVoucherOrderOperate.YES,//yes是要删除
                traceId,
                message.getMessageBody().getVoucherId(),
                message.getMessageBody().getUserId(),
                message.getMessageBody().getOrderId(),
                message.getMessageBody().getAfterQty(),
                message.getMessageBody().getChangeQty(),
                message.getMessageBody().getBeforeQty());
    }
}
