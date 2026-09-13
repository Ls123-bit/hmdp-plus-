package org.javaup.service.impl;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.date.LocalDateTimeUtil;
import cn.hutool.core.lang.loader.Loader;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.javaup.core.RedisKeyManage;
import org.javaup.entity.SeckillVoucher;
import org.javaup.entity.VoucherOrder;
import org.javaup.entity.VoucherReconcileLog;
import org.javaup.enums.LogType;
import org.javaup.enums.ReconciliationStatus;
import org.javaup.model.RedisTraceLogModel;
import org.javaup.redis.RedisCache;
import org.javaup.redis.RedisKeyBuild;
import org.javaup.service.IReconciliationTaskService;
import org.javaup.service.ISeckillVoucherService;
import org.javaup.service.IVoucherOrderService;
import org.javaup.service.IVoucherReconcileLogService;
import org.javaup.servicelock.LockType;
import org.javaup.servicelock.annotion.ServiceLock;
import org.springframework.aop.framework.AopContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

import static org.javaup.constant.DistributedLockConstants.UPDATE_SECKILL_VOUCHER_STOCK_LOCK;
import static org.javaup.kafka.consumer.SeckillVoucherConsumer.MESSAGE_DELAY_TIME;

/**
 * @program: 黑马点评-plus升级版实战项目。添加 阿星不是程序员 微信，添加时备注 点评 来获取项目的完整资料
 * @description: 对账执行 接口
 * @author: 阿星不是程序员
 **/
@Service
@Slf4j
public class ReconciliationTaskServiceImpl implements IReconciliationTaskService {
    
    @Resource
    private ISeckillVoucherService seckillVoucherService;
    
    @Resource
    private IVoucherOrderService voucherOrderService;//优惠券订单的service
    
    @Resource
    private IVoucherReconcileLogService voucherReconcileLogService;
    
    @Resource
    private RedisCache redisCache;
    
    @Override
    public void reconciliationTaskExecute() {//先把每个优惠券查出来，看每个优惠券下，每个订单的对比情况，所以要循环每个优惠券
        List<SeckillVoucher> seckillVoucherList = seckillVoucherService.lambdaQuery().list();
        for (SeckillVoucher seckillVoucher : seckillVoucherList) {
            reconciliationTaskExecute(seckillVoucher.getVoucherId());
        }
    }
    //对比优惠券下的每个订单的对账状态是否一致 检查redis流水日志和数据库流水记录是否一致，如果数据库存在，redis不存在，会向redis进行补偿 （目前已有的流程是以数据库为准） 改进（因为现有的问题是少买，数据库里面根本没有）--》  （要添加新的流程增加检查redis有有扣减流水记录，但数据库不存在扣减流水记录的处理流程 ）
    //检查redis和数据库的扣减流水记录，判断是不是一一对应的   一旦发现 redis扣减有记录，但数据库中不存在，立即将redis中对应的优惠券库存和这条扣减流水删除，让后续请求触发再从数据库重新加载库存
    public void reconciliationTaskExecute(Long voucherId){
       //读取此优惠券下的redis中的记录日志
        Map<String, RedisTraceLogModel> redisTraceLogMap = loadRedisTraceLogMap(voucherId);
        redisDeductTraceWithoutDbOrder(voucherId, redisTraceLogMap);
        RedisKeyBuild traceLogKey = RedisKeyBuild.createRedisKey(RedisKeyManage.SECKILL_TRACE_LOG_TAG_KEY, voucherId);
        long ttlSeconds = resolveTraceTtlSeconds(traceLogKey, voucherId);



        //根据优惠券的id查询出没有对账的订单（订单有状态：待处理，异常，一致，不一致）
        List<VoucherOrder> voucherOrderList = loadPendingOrders(voucherId);

        //优惠券可能有多条订单 所以要循环每个订单
        for (VoucherOrder voucherOrder : voucherOrderList) {
            //查询此订单数据库中的记录日志
            List<VoucherReconcileLog> logs = loadReconcileLogs(voucherOrder.getId());
            if (CollectionUtil.isEmpty(logs)) {//如果数据库中的记录日志不存在，那么就异常了。（把此订单和订单记录日志的对账状态更新为异常状态）
                //从Spring里面把他的代理对象取过来  在AOP的上下文去取（可以直接获取到当前的代理对象是谁） 要转换类型为当前类型
                ((ReconciliationTaskServiceImpl) AopContext.currentProxy())//我们的事务是通过AOP生成的，AOP是用Spring代理对象生成的
                        .markOrderStatus(voucherOrder.getId(), ReconciliationStatus.ABNORMAL);//单纯的调用这行其实是this调用，this调用调用到这里是普通对象，普通对象执行这个，他的AOP是不生效的
                continue; //如果异常不用执行直接停止循环  因为日志的对账日志都没有，
            }
            boolean anyMissing=backfillMissingTraceLogs(logs,redisTraceLogMap,traceLogKey,ttlSeconds);
            //要判断数据库有几条日志，如果数据库的记录日志只有一条（表示这个订单只有抢购优惠券）如果日志有两条，表示这个订单先抢购优惠券，然后又取消了
            // 当anyMissing为true时，不管一条还是两条。都说明我们是往redis中进行补偿了。往redis里面补偿了，应该把redis里面库存删掉  因为这时候的redis库存肯定是有问题的（删掉比回滚整个链路更简便可行）删掉等下次再抢购优惠券的时候，会先从我们的数据库里面去加载 加载到redis库存里面后，后续再进行顺利的扣减

            int dbLogCount = logs.size();//先获取数据库中的数据有几条
            boolean markConsistent = true;//再设置一个标志位
            //数据库中的记录日志只有一条，那么表示此订单只有抢购优惠券
            //如果数据库中的记录日志有两条，表示此订单先抢购优惠券，然后又取消了
            if (dbLogCount == 1 || dbLogCount == 2) {
                if (anyMissing) {//如果是true说明 向redis有发生了补偿的记录日志了，删除redis库存中的数据  （发生补偿，要把redis库存删掉，说明库存肯定是错了）
                  //如果发生了向redis补偿后，将redis的库存删除
                    ((IReconciliationTaskService) AopContext.currentProxy()).delRedisStock(voucherId);//delRedisStock(voucherId)要取出真正的代理对象出来
                }
            } else {//（如果订单有了，但是订单下的记录没有，要设置成异常情况  代理对象执行）
                //如果数据库中没有记录日志，那么吧此订单和订单记录日志的对账状态更新为异常状态
                ((ReconciliationTaskServiceImpl) AopContext.currentProxy())
                        .markOrderStatus(voucherOrder.getId(), ReconciliationStatus.ABNORMAL);
                markConsistent = false;
            }
            //********执行到这里，说明数据库中的记录日志和redis中的记录日志是一致的， 那么把此订单和订单记录日志的对账状态更新为一致状态
            if (markConsistent) {//(如果markConsistent是true的话，说明此时不用管是发生补偿也好，不发生补偿也好，说明一开始两个记录完全一致，也可能是补偿后给补偿成功了 成功状态要把订单状态更新为一致状态)
                //(不管一开始redis和数据库的记录能对的上还是说进行后补偿，反正最后都成功了，既然成功了，把他的状态更新为一致状态)
                ((ReconciliationTaskServiceImpl) AopContext.currentProxy())
                        .markOrderStatus(voucherOrder.getId(), ReconciliationStatus.CONSISTENT);
            }
        }
    }
    
    @Override
    @ServiceLock(lockType= LockType.Write,name = UPDATE_SECKILL_VOUCHER_STOCK_LOCK,keys = {"#voucherId"})
    //删除redis库存 并发行为 （删除redis库存是并发性行为）要加读写锁 ---->写锁 要进行修改，
    public void delRedisStock(Long voucherId){
        RedisKeyBuild stockKey = RedisKeyBuild.createRedisKey(RedisKeyManage.SECKILL_STOCK_TAG_KEY, voucherId);
        redisCache.del(stockKey);//删除redis库存
    }
   //查询没有对账的订单（订单有状态：待处理，异常，一致，不一致）
    private List<VoucherOrder> loadPendingOrders(Long voucherId) {
        return voucherOrderService.lambdaQuery()
                .eq(VoucherOrder::getVoucherId, voucherId)
                .le(VoucherOrder::getCreateTime, LocalDateTimeUtil.offset(LocalDateTimeUtil.now(), 2, ChronoUnit.MINUTES))//在当前时间（订单创建时间）和当前时间两分钟之后，因为有可能订单正在消费还没有完全执行
                .eq(VoucherOrder::getReconciliationStatus, ReconciliationStatus.PENDING.getCode())//对比的状态 待处理
                .orderByAsc(VoucherOrder::getCreateTime)
                .list();
    }

    //根据订单id查询订单的对账记录
    private List<VoucherReconcileLog> loadReconcileLogs(Long orderId) {
        return voucherReconcileLogService.lambdaQuery()
                .eq(VoucherReconcileLog::getOrderId, orderId)
                .orderByAsc(VoucherReconcileLog::getCreateTime)//根据订单创建时间进行排序
                .list();
    }

    //读取此优惠券下的redis中的记录日志
    private Map<String, RedisTraceLogModel> loadRedisTraceLogMap(Long voucherId) {
        return redisCache.getAllMapForHash(//哈希结构
                RedisKeyBuild.createRedisKey(RedisKeyManage.SECKILL_TRACE_LOG_TAG_KEY, voucherId),
                RedisTraceLogModel.class
        );
    }

    //计算并返回秒杀券跟踪日志（Trace Log）在 Redis 中的过期时间（TTL），确保日志在合理的时间内保留，同时兼顾性能和数据完整性。
    private long resolveTraceTtlSeconds(RedisKeyBuild traceLogKey, Long voucherId) {
        Long ttlSeconds = redisCache.getExpire(traceLogKey, TimeUnit.SECONDS);//首先看redis中是否有过期时间
        if (ttlSeconds != null && ttlSeconds > 0) {
            return ttlSeconds;
        }
        //如果没有的话，从数据库里面开始查看优惠券的过期时间
        SeckillVoucher voucher = seckillVoucherService.lambdaQuery()
                .eq(SeckillVoucher::getVoucherId, voucherId)
                .one();
        long computedTtl = 3600L;
        if (voucher != null && voucher.getEndTime() != null) {
            LocalDateTime now = LocalDateTimeUtil.now();
            long secondsUntilEnd = Math.max(0L, Duration.between(now, voucher.getEndTime()).getSeconds());
            computedTtl = Math.max(1L, secondsUntilEnd + Duration.ofDays(1).getSeconds());//过期时间计算出来后，整点冗余时间，不可以说正好优惠券活动结束时间为过期时间，因为有可能订单正在消费还没有完全执行  多冗余一天
        }
        return computedTtl;
    }
    

    //对比redis和数据库的扣减流水记录， 参数：优惠券id 第二个参数是redis的流水
    /**
     * 处理“Redis 已记录扣减流水，但数据库未落对应扣减流水”的少卖场景。
     *
     * 背景：
     * 1. Redis 扣减流水（RedisTraceLogModel）与数据库 tb_voucher_reconcile_log 通过 traceId 一一对应；
     * 2. 当生产者异步发送后进程异常退出，可能出现 Redis 已扣减、Kafka 消息未完成落盘/回调未执行的窗口；
     * 3. 最终表现为：Redis 中看起来库存已减少，但 DB 中没有这笔订单扣减流水。
     *
     * 处理策略：
     * 1. 仅扫描 Redis 中 logType=DEDUCT 的流水；
     * 2. 用 orderId + traceId 到 tb_voucher_reconcile_log 做精确匹配；
     * 3. 若 DB 缺失该流水，则先做“延迟保护窗口”判断，避免把正常的消费延迟误判成异常；
     * 4. 超过保护窗口仍缺失，判定为异常：删除 Redis 库存（每个 voucher 只删一次）并清理该条 Redis 流水。
     */

    private void redisDeductTraceWithoutDbOrder(Long voucherId, Map<String, RedisTraceLogModel> redisTraceLogMap) {
        if (voucherId == null || CollectionUtil.isEmpty(redisTraceLogMap)) {//检查参数都不可为空，
            return;
        }
        //把日志键（流水的redis里面的key给拿到手）
        RedisKeyBuild traceLogKey = RedisKeyBuild.createRedisKey(RedisKeyManage.SECKILL_TRACE_LOG_TAG_KEY, voucherId);
        boolean delRedisStockHasHappened = false;//加的变量   标识第一次是false标识没有执行过删除库存的操作
        long now = System.currentTimeMillis();//获取当前的时间
        //开始循环我们的redis的流水日志 要用entryset()方法（效率高），因为要获取key和value都给拿到手
        for (Entry<String, RedisTraceLogModel> redisTraceLogModelEntry : redisTraceLogMap.entrySet()) {
            String traceId = redisTraceLogModelEntry.getKey();//Key就是traceId
            RedisTraceLogModel redisTraceLogModel = redisTraceLogModelEntry.getValue();//Value就是我们的流水日志?
            if (redisTraceLogModel == null) {//如果流水日志为空，直接跳过
                continue;
            }
            //只处理扣减类型的流水   如果不是等于扣减的话，也不往下执行
            if (!String.valueOf(LogType.DEDUCT.getCode()).equals(redisTraceLogModel.getLogType())) {
                continue;
            }
            //订单id拿到手
            String orderId = redisTraceLogModel.getOrderId();
            //查流水数据库 订单id和一一对应的traceId
            VoucherReconcileLog voucherReconcileLog = 
                    voucherReconcileLogService.lambdaQuery()
                            .eq(VoucherReconcileLog::getOrderId, Long.parseLong(orderId))//里应用traceId就可以，但是我们的表使用订单ID进行分库分表  仅有下面一个条件没有分片键就变成全路由查询了，  分库分表的情况下，一定时时刻刻带着分片键
                            .eq(VoucherReconcileLog::getTraceId, Long.parseLong(traceId))
                            .one();
            if (Objects.isNull(voucherReconcileLog)){//判断数据库有没有 如果没有 也不一定要把redis删了（redis扣减 消息发送 kafka消息消费（可能在消费的过程中有点慢，但不是消费失败了） 生成订单）所以要预留时间保证过了延迟时间
                Long traceTs = redisTraceLogModel.getTs();//首先拿到时间戳
                if (traceTs != null && now - traceTs < (MESSAGE_DELAY_TIME + 2000)) {//时间戳不为空，并且当前时间减去时间戳小于等于10秒(MESSAGE_DELAY_TIME),但是不可以直接卡着这个时间节点，因为在消息消费过程中，生成订单也得占点时间 添加的操作是insert（时间很快），不是update（有行锁）就延迟两秒钟
                    continue;
                }
                //执行到这里，说明消息确实丢了，都大于时间节点了，确实该删去了，
                log.error("发现Redis扣减流水存在，但DB订单不存在的情况，voucherId={}, orderId={}", voucherId, orderId);
                if (!delRedisStockHasHappened) {//没有执行过的话，才会进入到这个判断中 上面设置为false的原因
                    //删除库存
                    ((IReconciliationTaskService) AopContext.currentProxy()).delRedisStock(voucherId);//将切面正常执行
                    delRedisStockHasHappened = true;//优惠券有多笔订单记录，在删库存的时候有可能删多次 删多次的话，也是浪费redis的执行，其实删一次就够了 删完之后变为true，下次再产生问题的时候就进不来了 不用再删了
                }
                //删除当前异常流水（库存删完以后后，redis和数据库的库存就一致了， 一致后redis中有多余的流水就可以删掉了 ）不删的话，下次判断下次再执行对账的时候，又判断又得有
                redisCache.delForHash(traceLogKey, traceId);
            }
        }
    }

    //（判断是不是缺失了）数据库中缺失的秒杀跟踪日志回填到 Redis 中，确保 Redis 缓存与数据库的数据一致性
    private boolean backfillMissingTraceLogs(List<VoucherReconcileLog> logs,   //参数设计：数据库日志（logs）：作为权威数据源，存储完整的对账记录。
                                             Map<String, RedisTraceLogModel> redisTraceLogMap, //Redis 映射（redisTraceLogMap）：作为缓存快照，支持快速查找和对比。使用 Map 结构（redisTraceLogMap）实现 O(1) 时间复杂度的查找，避免遍历 Redis 数据。
                                             RedisKeyBuild traceLogKey,//统一key管理 RedisKeyBuild 确保 Redis Key 的命名规范，避免硬编码，提高可维护性。
                                             long ttlSeconds) {//动态过期策略 ttlSeconds 根据秒杀券的结束时间动态计算，平衡数据保留时间和内存占用。
        boolean anyMissing = false;//标识，判断是不是缺失，首先默认是不缺失的
        for (VoucherReconcileLog log : logs) {//遍历数据库中的对账日志 记录
            String traceIdStr = String.valueOf(log.getTraceId());
            RedisTraceLogModel existed = redisTraceLogMap.get(traceIdStr);//redisTraceLogMap是redis中的
            //如果不为空 则没有问题  终止这些循环
            if (existed != null) {
                continue;
            }
            //如果有 则要进行补偿操作 要开始组装redis中的信息
            anyMissing = true;
            //开始组装redis中的信息  拿数据库中的数据一点点的组装起来
            RedisTraceLogModel model = new RedisTraceLogModel();
            model.setLogType(String.valueOf(log.getLogType()));
            model.setTs(LocalDateTimeUtil.toEpochMilli(log.getCreateTime()));
            model.setOrderId(String.valueOf(log.getOrderId()));
            model.setTraceId(traceIdStr);
            model.setUserId(String.valueOf(log.getUserId()));
            model.setVoucherId(String.valueOf(log.getVoucherId()));
            model.setBeforeQty(log.getBeforeQty());
            model.setChangeQty(log.getChangeQty());
            model.setAfterQty(log.getAfterQty());
            //往redis中进行放  开始补偿，因为redsi中没有
            redisCache.putHash(traceLogKey, traceIdStr, model);
            //因为可能有的有，有的没有，有的缺失有的不缺失，所以我们要判断外面的大key是否过期(有过期时间）了（哈希结构），如果没有过期时间，我们再进行设置
            Long currentTtl = redisCache.getExpire(traceLogKey, TimeUnit.SECONDS);
            if (currentTtl == null || currentTtl <= 0) {//如果当前时间是空的或者小于零我们再往里面设置
                redisCache.expire(traceLogKey, ttlSeconds, TimeUnit.SECONDS);
            }
        }
        return anyMissing;//结果返回
    }

    //根据订单id和对账状态更新订单和订单记录日志的对账状态
    @Transactional(rollbackFor = Exception.class)    //因为更新两张表，所以我们要加事务
    public void markOrderStatus(Long orderId, ReconciliationStatus status) {
        voucherOrderService.lambdaUpdate()
                .set(VoucherOrder::getReconciliationStatus, status.getCode()) //我们的对账状态是我们传入的
                .set(VoucherOrder::getUpdateTime, LocalDateTime.now()) //更新时间是当前时间
                .eq(VoucherOrder::getId, orderId)//通过我们的订单id进行更新
                .update();
        voucherReconcileLogService.lambdaUpdate() //把对账记录也要更更新为异常状态 （日志更新）
                .set(VoucherReconcileLog::getReconciliationStatus, status.getCode())
                .set(VoucherReconcileLog::getUpdateTime, LocalDateTime.now())
                .eq(VoucherReconcileLog::getOrderId, orderId)
                .update();
    }
}
