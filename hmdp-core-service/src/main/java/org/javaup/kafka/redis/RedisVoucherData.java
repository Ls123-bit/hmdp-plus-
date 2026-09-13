package org.javaup.kafka.redis;

import cn.hutool.core.collection.ListUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.javaup.core.RedisKeyManage;
import org.javaup.entity.RollbackFailureLog;
import org.javaup.enums.BaseCode;
import org.javaup.enums.LogType;
import org.javaup.enums.SeckillVoucherOrderOperate;
import org.javaup.lua.SeckillVoucherRollBackOperate;
import org.javaup.redis.RedisKeyBuild;
import org.javaup.service.IRollbackFailureLogService;
import org.javaup.service.IRollbackAlertService;
import org.javaup.toolkit.SnowflakeIdGenerator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import io.micrometer.core.instrument.MeterRegistry;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;


/**
 * @program: 黑马点评-plus升级版实战项目。添加 阿星不是程序员 微信，添加时备注 点评 来获取项目的完整资料
 * @description: Redis 秒杀订单回滚数据操作组件。
 * @author: 阿星不是程序员
 **/
@Slf4j
@Component
public class RedisVoucherData {
    
    @Resource
    private SeckillVoucherRollBackOperate seckillVoucherRollBackOperate;
    
    @Resource
    private IRollbackFailureLogService rollbackFailureLogService;//通知失败日志服务

    @Resource
    private SnowflakeIdGenerator snowflakeIdGenerator;
    
    @Resource
    private MeterRegistry meterRegistry;
    
    @Resource
    private IRollbackAlertService rollbackAlertService;
    
    @Value("${seckill.rollback.retry.maxAttempts:3}")//可配置化，最大重试次数
    private int retryMaxAttempts;
    
    @Value("${seckill.rollback.retry.initialBackoffMillis:200}")//可配置化，初始间隔时间
    private long initialBackoffMillis;
    
    @Value("${seckill.rollback.retry.maxBackoffMillis:1000}")//可配置化，最大间隔时间
    private long maxBackoffMillis;


    //发送失败的数据要回滚回去      这个方法没有加事务，因为就一个表
    public void rollbackRedisVoucherData(SeckillVoucherOrderOperate seckillVoucherOrderOperate,//操作redis的对象
                                         Long traceId,
                                         Long voucherId,
                                         Long userId,
                                         Long orderId,
                                         Integer beforeQty,
                                         Integer changeQty,
                                         Integer afterQty) {
        List<String> keys = ListUtil.of(//需要操作的redis的key 优惠券库存，用户秒杀记录，秒杀日志
                RedisKeyBuild.createRedisKey(RedisKeyManage.SECKILL_STOCK_TAG_KEY, voucherId).getRelKey(),
                RedisKeyBuild.createRedisKey(RedisKeyManage.SECKILL_USER_TAG_KEY, voucherId).getRelKey(),
                RedisKeyBuild.createRedisKey(RedisKeyManage.SECKILL_TRACE_LOG_TAG_KEY, voucherId).getRelKey()
        );
        //执行lua需要的参数，把需要的数据拼接到args数组中
        String[] args = new String[9];
        args[0] = String.valueOf(voucherId);
        args[1] = String.valueOf(userId);
        args[2] = String.valueOf(orderId);
        args[3] = String.valueOf(seckillVoucherOrderOperate.getCode());
        args[4] = String.valueOf(traceId);
        args[5] = String.valueOf(LogType.RESTORE.getCode());//回滚日志类型
        args[6] = String.valueOf(beforeQty);
        args[7] = String.valueOf(changeQty);
        args[8] = String.valueOf(afterQty);
        //带退避的重试，返回最终的结果的code码，
        Integer finalCode = luaRollbackWithResultCode(keys, args, retryMaxAttempts, initialBackoffMillis, maxBackoffMillis);
        boolean ok = finalCode != null && finalCode.equals(BaseCode.SUCCESS.getCode());//不为空，并且等于我们的成功的code码，
        if (!ok) {//如果不成功，旧的上报
            String reason = BaseCode.getMsg(finalCode == null ? -1 : finalCode);//
            log.error("Redis回滚最终失败|voucherId={}|userId={}|orderId={}|traceId={} reason={}", voucherId, userId, orderId, traceId, reason);
           //失败日志  异常--恢复失败 记录lua返回码供后续精准路和统计
            saveRollbackFailureLog(voucherId, userId, orderId, traceId, "redis rollback failed after retries: " + reason, finalCode);
           //再上传一个指标 重试放弃了，因为达到最大重试次数也不好使
            safeInc("seckill_rollback_retry_give_up", "component", "redis_voucher_data");
        }
    }
    //回滚数据
    private Integer luaRollbackWithResultCode(
            List<String> keys,
            String[] args,
            int maxAttempts,      //重试最大值
            long initialBackoffMs,  //间隔时间
            long maxBackoffMs) {//最大间隔时间
        int attempt = 0;    //初始重试次数
        long backoff = Math.max(50, initialBackoffMs);//响应延迟时间，延迟重试
        Integer lastCode = null;//code码，判断是否执行成功，
        while (true) {//重试循环
            try {
                Integer result = seckillVoucherRollBackOperate.execute(keys, args);
                lastCode = result;
                if (result != null && result.equals(BaseCode.SUCCESS.getCode())) {//不能为空，并且执行成功，那么直接返回
                  //成功了，也上报一下
                    safeInc("seckill_rollback_retry_success", "component", "redis_voucher_data");
                    return result;
                }
                //执行失败，记录日志
                String reason = BaseCode.getMsg(result == null ? -1 : result);//要知道什么原因不成功，通过code码拿到对应的信息，如果是空的话，默认是-1，如果不是空的话，就是code码对应的信息
                log.warn("Redis回滚失败，准备重试|attempt={} reason={}", attempt + 1, reason);//原因打个日志
            } catch (Exception e) {
                lastCode = -1;//执行中出现异常，code码没返回来，但是执行中出现异常，把code码设为-1，
                log.warn("Redis回滚异常，准备重试|attempt={} error={}", attempt + 1, e.getMessage());//还是打个日志
            }
            attempt++;
            if (attempt >= maxAttempts) {//重试次数大于最大重试次数 终止
                break;
            }
            sleepQuietly(withJitter(backoff));
            backoff = Math.min(backoff * 2, Math.max(backoff, maxBackoffMs));
        }
        return lastCode;
    }
    //随机延迟时间，避免所有请求同时到达服务器
    private long withJitter(long base) {
        long jitter = Math.round(base * 0.15 * Math.random());
        return base + jitter;
    }
    //休眠指定时间
    private void sleepQuietly(long backoffMs) {
        try {
            TimeUnit.MILLISECONDS.sleep(backoffMs);
        } catch (InterruptedException ie) {//捕获异常，线程中断，
            Thread.currentThread().interrupt();
        }
    }
    //保存回滚失败日志  redis很少失败，所以放在数据库不会影响高并发，
    private void saveRollbackFailureLog(Long voucherId, Long userId, Long orderId, Long traceId, String detail, Integer resultCode) {
        try {
            RollbackFailureLog logEntity = new RollbackFailureLog();
            logEntity.setId(snowflakeIdGenerator.nextId())
                    .setOrderId(orderId)
                    .setUserId(userId)
                    .setVoucherId(voucherId)
                    .setDetail(detail)
                    .setResultCode(resultCode)
                    .setTraceId(traceId)
                    .setRetryAttempts(retryMaxAttempts)  //失败了，肯定达到最大重试次数了。
                    .setSource("redis_voucher_data")
                    .setCreateTime(LocalDateTime.now())
                    .setUpdateTime(LocalDateTime.now());
            rollbackFailureLogService.save(logEntity);
           //上报指标
            safeInc("seckill_rollback_failure", "component", "redis_voucher_data");
            safeInc("seckill_rollback_failure", "reason", "retry_exhausted");

            rollbackAlertService.sendRollbackAlert(logEntity);
        } catch (Exception e) {
            log.warn("保存回滚失败日志异常", e);
        }
    }
    //上报指标   引入指标上报组件  meterRegistry
    private void safeInc(String name, String tagKey, String tagValue) {
        try {
            if (meterRegistry != null) {
                meterRegistry.counter(name, tagKey, tagValue).increment();
            }
        } catch (Exception ignore) {
        }
    }
}
