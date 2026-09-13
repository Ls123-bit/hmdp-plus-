package org.javaup.kafka.consumer;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.javaup.consumer.AbstractConsumerHandler;
import org.javaup.core.RedisKeyManage;
import org.javaup.enums.BaseCode;
import org.javaup.enums.BusinessType;
import org.javaup.enums.LogType;
import org.javaup.enums.SeckillVoucherOrderOperate;
import org.javaup.exception.HmdpFrameException;
import org.javaup.kafka.message.SeckillVoucherMessage;
import org.javaup.kafka.redis.RedisVoucherData;
import org.javaup.message.MessageExtend;
import org.javaup.model.SeckillVoucherFullModel;
import org.javaup.redis.RedisCache;
import org.javaup.redis.RedisKeyBuild;
import org.javaup.service.IAutoIssueNotifyService;
import org.javaup.service.ISeckillVoucherService;
import org.javaup.service.IVoucherOrderService;
import org.javaup.service.IVoucherReconcileLogService;
import org.javaup.toolkit.SnowflakeIdGenerator;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Headers;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.javaup.constant.Constant.SECKILL_VOUCHER_TOPIC;
import static org.javaup.constant.Constant.SPRING_INJECT_PREFIX_DISTINCTION_NAME;


/**
 * @program: 黑马点评-plus升级版实战项目。添加 阿星不是程序员 微信，添加时备注 点评 来获取项目的完整资料
 * @description: Kafka 消费者：处理秒杀券下单消息。
 * @author: 阿星不是程序员
 **/

@Slf4j
@Component
//消费端也要执行kafka组件，要继承抽象类
public class SeckillVoucherConsumer extends AbstractConsumerHandler<SeckillVoucherMessage> {
    
    public static Long MESSAGE_DELAY_TIME = 10000L;
    
    @Resource
    private IVoucherOrderService voucherOrderService;//生成订单的service
    
    @Resource
    private RedisVoucherData redisVoucherData;//回滚操作的service
    
    @Resource
    private RedisCache redisCache;
    
    @Resource
    private ISeckillVoucherService seckillVoucherService;//秒杀券的service
    
    @Resource
    private IVoucherReconcileLogService voucherReconcileLogService;//对账的service引入进来，用于对账日志的添加
     
    @Resource
    private SnowflakeIdGenerator snowflakeIdGenerator;//雪花算法生成器
    
    
    @Resource
    private IAutoIssueNotifyService autoIssueNotifyService;//负责通知的service
    
    
    private static final int CPU_CORES = Runtime.getRuntime().availableProcessors();
    private static final int EXECUTOR_THREADS = Math.max(2, CPU_CORES);
    private static final int EXECUTOR_QUEUE_CAPACITY = 1024 * Math.max(1, CPU_CORES);
    //秒杀订单消费线程池 构建一个线程池，用于异步执行秒杀订单消费成功任务
    private static final ThreadPoolExecutor SECKILL_ORDER_CONSUME_TASK_EXECUTOR =
            new ThreadPoolExecutor(
                    EXECUTOR_THREADS,
                    EXECUTOR_THREADS,
                    0L,
                    TimeUnit.MILLISECONDS,
                    new LinkedBlockingQueue<>(EXECUTOR_QUEUE_CAPACITY),
                    new NamedThreadFactory("seckill-order-consume-task", false),//线程工厂   daemon = false：表示创建的线程是用户线程（非守护线程）。
                    new ThreadPoolExecutor.CallerRunsPolicy()//拒绝策略：调用者运行策略，直接在调用者线程中运行任务。
            );
    
    private static class NamedThreadFactory implements ThreadFactory {
        private final String namePrefix;
        private final boolean daemon;
        private final AtomicInteger index = new AtomicInteger(1);
        
        public NamedThreadFactory(String namePrefix, boolean daemon) {
            this.namePrefix = namePrefix;
            this.daemon = daemon;
        }
        
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, namePrefix + index.getAndIncrement());
            t.setDaemon(daemon);
            t.setUncaughtExceptionHandler((thread, ex) ->
                    log.error("未捕获异常，线程={}, err={}", thread.getName(), ex.getMessage(), ex)
            );
            return t;
        }
    }
    
    
    public SeckillVoucherConsumer() {
        super(SeckillVoucherMessage.class);
    }
    
   
    @KafkaListener(
            topics = {SPRING_INJECT_PREFIX_DISTINCTION_NAME + "-" + SECKILL_VOUCHER_TOPIC}//监听 指定我们的主题
    )
    public void onMessage(String value,   //string类型的字符串，虽然是批量拉去但是是一条条消费的
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
     * 一致性问题：如果消息消费大于十秒的话，往redis和我们的数据库里都放一条对账日志   对账主要看关联id 在redis里面 关联id是新生成的  （回滚 回滚日志肯定是新的一条）数据库里面的对账日志也是回滚
     * 但我们用的关联id是消息里面的 消息id里的关联id是发消息传过来的，发消息传过来的关联id是扣减成功  （关联id应该是数据库里面创建订单成功了，然后那个对账日志也生成一条是成功的， 订单创建成功的应该用这个）
     * 但是我们现在是超时回滚
     * @param message
     * @return
     */
    @Override
    //消费前 相当于AOP里面的前置通知 在这里判断消息是不是超时，。如果没有超时小于十秒，则继续执行doconsumer，如果超过十秒 直接将消息丢弃，消费过程就直接结束了，
       protected Boolean beforeConsume(MessageExtend<SeckillVoucherMessage> message) {
        //获取发送时间，
        long producerTimeTimestamp = message.getProducerTime().getTime();
        //延迟时间     现在的时间减去发送时间
        long delayTime = System.currentTimeMillis() - producerTimeTimestamp;
        //如果消息超时时间达到了阈值（10秒）
        if (delayTime > MESSAGE_DELAY_TIME){ //如果消费延迟时间大于10秒 就把就把这个消息给抛弃了
            log.info("消费到kafka的创建优惠券消息延迟时间大于了 {} 毫秒 此订单消息被丢弃 订单号 : {}",
                    delayTime,message.getMessageBody().getOrderId());

            //超时了，要把redis的扣减完，再回滚回去，回滚的话要记录日志，有关联id     redis的回滚
            long traceId = snowflakeIdGenerator.nextId();
            redisVoucherData.rollbackRedisVoucherData(//回滚操作
                    SeckillVoucherOrderOperate.YES,
                    traceId,
                    message.getMessageBody().getVoucherId(),
                    message.getMessageBody().getUserId(),
                    message.getMessageBody().getOrderId(),
                    // 这是回滚操作，所以redis中扣减前和扣减后的数量要和消息中的反过来
                    message.getMessageBody().getAfterQty(),
                    message.getMessageBody().getChangeQty(),
                    message.getMessageBody().getBeforeQty()
            );
            try {//数据库中的回滚，
                voucherReconcileLogService.saveReconcileLog(LogType.RESTORE.getCode(), 
                        BusinessType.TIMEOUT.getCode(), 
                        "message delayed " + delayTime + "ms, rollback redis", 
                        traceId,
                        message);
            } catch (Exception e) {
                log.warn("保存对账日志失败(延迟丢弃)", e);
            }
            return false;
        }
        return true;
    }
    
    @Override
    protected void doConsume(MessageExtend<SeckillVoucherMessage> message) {
        voucherOrderService.createVoucherOrderV2(message);
    }


    //消息消费成功的
    @Override
    protected void afterConsumeSuccess(MessageExtend<SeckillVoucherMessage> message) {
        super.afterConsumeSuccess(message);
        //获取消息体
        SeckillVoucherMessage messageBody = message.getMessageBody();
        //在消息体中获取用户id，优惠券id，订单id
        Long userId = messageBody.getUserId();
        Long voucherId = messageBody.getVoucherId();
        Long orderId = messageBody.getOrderId();


        //不是主流程，应该异步执行，要添加一个线程池，
        SECKILL_ORDER_CONSUME_TASK_EXECUTOR.execute(() -> {
            try {
                //已经购买成功了，那么订阅的状态就应该剔除掉首先把键构建出来
                RedisKeyBuild subscribeZSetKey = RedisKeyBuild.createRedisKey(
                        RedisKeyManage.SECKILL_SUBSCRIBE_ZSET_TAG_KEY,
                        messageBody.getVoucherId()
                );
                //构建出来后，要将其删掉  删除用户id
                redisCache.delForSortedSet(subscribeZSetKey, String.valueOf(userId));
            } catch (Exception e) {
                log.warn("清理订阅ZSET成员失败，voucherId={}, userId={}, err={}", messageBody.getVoucherId(), userId, e.getMessage());
            }

            //messageBody.getAutoIssue()开启了才会有通知
            if (Boolean.TRUE.equals(messageBody.getAutoIssue())) {
                try {
                    autoIssueNotifyService.sendAutoIssueNotify(voucherId, userId, orderId);
                } catch (Exception e) {
                    log.warn("自动发券通知发送失败，voucherId={}, userId={}, orderId={}, err={}",
                            voucherId, userId, orderId, e.getMessage());
                }
            }
            try {
                //统计优惠券
                //首先把优惠券查询出来，查询的目的是拿到他的商铺id
                SeckillVoucherFullModel voucherFull = seckillVoucherService.queryByVoucherId(voucherId);
                if (Objects.isNull(voucherFull)) {//如果优惠券不存在，就终止
                    return;
                }
                //如果不为空，获取对应的店铺id
                Long shopId = voucherFull.getShopId();
                // yyyyMMdd         构建每天统计的数量 首先放在redis里面，首先把它对应的key构建出来 要统计每天的，所以要先有每天的时间戳
                String day = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
                //把我们的key构建出来
                RedisKeyBuild dailyKey = RedisKeyBuild.createRedisKey(
                        RedisKeyManage.SECKILL_SHOP_TOP_BUYERS_DAILY_TAG_KEY,
                        shopId,
                        day
                );

                //key有了，优惠券领取了，领取完之后我们要计数一次
                redisCache.incrementScoreForSortedSet(dailyKey, String.valueOf(userId), 1.0);//调用此方实现自增   多少次数统计到该用户id下面

                Long ttl = redisCache.getExpire(dailyKey, TimeUnit.SECONDS);
                if (ttl == null || ttl < 0) {//获取过期时间，如果过期时间是空的话,或者小于零了，那么在获取一个过期时间 过期时间设置一个九十天
                    redisCache.expire(dailyKey, 90, TimeUnit.DAYS);
                }
            } catch (Exception e) {
                log.warn("统计店铺Top买家失败，忽略不影响主流程", e);
            }
        });
    }





    //消费失败之后，
    @Override
    protected void afterConsumeFailure(final MessageExtend<SeckillVoucherMessage> message, 
                                       final Throwable throwable) {
        super.afterConsumeFailure(message, throwable);
        SeckillVoucherOrderOperate seckillVoucherOrderOperate = SeckillVoucherOrderOperate.YES;
        if (throwable instanceof HmdpFrameException hmdpFrameException) {
            if (Objects.nonNull(hmdpFrameException.getCode()) && //错误码不为空并且这code码等于我们那个code码(VOUCHER_ORDER_EXIST)，
                    hmdpFrameException.getCode().equals(BaseCode.VOUCHER_ORDER_EXIST.getCode())){
                seckillVoucherOrderOperate = SeckillVoucherOrderOperate.NO;//如果用户已经下单了，就不删除订单
            }
        }
        long traceId = snowflakeIdGenerator.nextId();//追踪id生成，生成唯一的追踪ID，用于关联操作链路。
        //redis回滚
        redisVoucherData.rollbackRedisVoucherData(
                seckillVoucherOrderOperate,
                traceId,
                message.getMessageBody().getVoucherId(),
                message.getMessageBody().getUserId(),
                message.getMessageBody().getOrderId(),
                message.getMessageBody().getAfterQty(),
                message.getMessageBody().getChangeQty(),
                message.getMessageBody().getBeforeQty()
        );
        //保存对账日志
        try {
            String detail = throwable == null ? "consume failed" : ("consume failed: " + throwable.getMessage());
            voucherReconcileLogService.saveReconcileLog(LogType.RESTORE.getCode(),
                    BusinessType.FAIL.getCode(), 
                    detail,
                    traceId,
                    message
            );
        } catch (Exception e) {
            log.warn("保存对账日志失败(消费失败)", e);
        }
    }
}
