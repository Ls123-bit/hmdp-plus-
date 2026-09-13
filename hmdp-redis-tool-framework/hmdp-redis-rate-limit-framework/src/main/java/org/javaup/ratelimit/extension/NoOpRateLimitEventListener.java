package org.javaup.ratelimit.extension;

import lombok.extern.slf4j.Slf4j;
import org.javaup.enums.BaseCode;

import java.util.concurrent.atomic.LongAdder;

/**
 * @program: 黑马点评-plus升级版实战项目。添加 阿星不是程序员 微信，添加时备注 点评 来获取项目的完整资料
 * @description: 默认事件监听实现
 * @author: 阿星不是程序员
 **/
@Slf4j
public class NoOpRateLimitEventListener implements RateLimitEventListener {

    private static final LongAdder BEFORE_EXECUTE_COUNTER = new LongAdder();
    private static final LongAdder ALLOWED_COUNTER = new LongAdder();
    private static final LongAdder BLOCKED_COUNTER = new LongAdder();

    @Override
    public void onBeforeExecute(RateLimitContext ctx) {
        BEFORE_EXECUTE_COUNTER.increment();
        if (log.isDebugEnabled()) {
            log.debug("rate-limit.before: voucherId={}, userId={}, ip={}, useSliding={}, keys={}",
                    ctx.getVoucherId(), ctx.getUserId(), ctx.getClientIp(), ctx.isUseSliding(), ctx.getKeys());
        }
    }

    @Override
    public void onAllowed(RateLimitContext ctx) {
        ALLOWED_COUNTER.increment();
        if (log.isDebugEnabled()) {
            log.debug("rate-limit.allowed: voucherId={}, userId={}, ip={}, result={}",
                    ctx.getVoucherId(), ctx.getUserId(), ctx.getClientIp(), ctx.getResult());
        }
    }

    @Override
    public void onBlocked(RateLimitContext ctx, BaseCode reason) {
        BLOCKED_COUNTER.increment();
        log.warn("rate-limit.blocked: reason={}, voucherId={}, userId={}, ip={}, window(ip={},user={}), attempts(ip={},user={})",
                reason,
                ctx.getVoucherId(), ctx.getUserId(), ctx.getClientIp(),
                ctx.getIpWindowMillis(), ctx.getUserWindowMillis(),
                ctx.getIpMaxAttempts(), ctx.getUserMaxAttempts());
    }

    public long getBeforeExecuteCount(){
        return BEFORE_EXECUTE_COUNTER.sum();
    }
    public long getAllowedCount(){
        return ALLOWED_COUNTER.sum();
    }
    public long getBlockedCount(){
        return BLOCKED_COUNTER.sum();
    }
}