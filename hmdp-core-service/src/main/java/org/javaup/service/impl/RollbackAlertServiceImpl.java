package org.javaup.service.impl;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.javaup.core.RedisKeyManage;
import org.javaup.entity.RollbackFailureLog;
import org.javaup.redis.RedisCache;
import org.javaup.redis.RedisKeyBuild;
import org.javaup.service.IRollbackAlertService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeUnit;

/**
 * @program: 黑马点评-plus升级版实战项目。添加 阿星不是程序员 微信，添加时备注 点评 来获取项目的完整资料
 * @description: 回滚失败通知服务：用于发送短信/邮件告警（可插拔实现）。
 * @author: 阿星不是程序员
 **/
@Slf4j
@Service
public class RollbackAlertServiceImpl implements IRollbackAlertService {

    @Value("${seckill.rollback.alert.sms.enabled:false}")
    private boolean smsEnabled;






    //这些是模拟的，实际要用短信的sdk或者钉钉的sdk去执行，
    @Value("${seckill.rollback.alert.email.enabled:false}")
    private boolean emailEnabled;

    @Value("${seckill.rollback.alert.sms.to:}")
    private String smsTo;

    @Value("${seckill.rollback.alert.email.to:}")
    private String emailTo;








    @Value("${seckill.rollback.alert.dedup.window.seconds:300}")
    private long dedupWindowSeconds;

    @Resource
    private RedisCache redisCache;  //redis组件 避免一直通知 风暴通知

    @Override
    //通知
    public void sendRollbackAlert(RollbackFailureLog logEntity) {
        try {
            //判断是否需要通知  如果不需要通知 直接结束，
            if (!shouldNotify(logEntity.getVoucherId())) {
                return;
            }
            //格式化通知内容
            String content = formatContent(logEntity);

            if (smsEnabled && smsTo != null && !smsTo.isEmpty()) {
                log.warn("[ROLLBACK_SMS] to={} content={} ", smsTo, content);//模拟发送短信通知
            }
            if (emailEnabled && emailTo != null && !emailTo.isEmpty()) {
                log.warn("[ROLLBACK_EMAIL] to={} content={} ", emailTo, content);//模拟发送邮件通知
            }
        } catch (Exception e) {
            log.warn("发送回滚失败通知异常", e);
        }
    }
    //判断是否需要通知
    private boolean shouldNotify(Long voucherId) {
        try {
            return redisCache.setIfAbsent(//如果没有值的话就设置，返回结果是true 如果之前有值的话，不执行 返回结果是false
                    RedisKeyBuild.createRedisKey(RedisKeyManage.SECKILL_ROLLBACK_ALERT_DEDUP_KEY,voucherId),
                    "1", 
                    dedupWindowSeconds, //过期时间
                    TimeUnit.SECONDS);
        } catch (Exception e) {
            return true;
        }
    }
    //格式化通知内容
    private String formatContent(RollbackFailureLog rollbackFailureLog) {
        String time = 
                rollbackFailureLog.getCreateTime() == null ? 
                        "" 
                        : 
                        rollbackFailureLog.getCreateTime().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        return String.format("回滚失败告警 | voucherId=%s userId=%s orderId=%s traceId=%s attempts=%s source=%s time=%s detail=%s", 
                rollbackFailureLog.getVoucherId(), 
                rollbackFailureLog.getUserId(), 
                rollbackFailureLog.getOrderId(), 
                rollbackFailureLog.getTraceId(), 
                rollbackFailureLog.getRetryAttempts(), 
                rollbackFailureLog.getSource(),
                time,
                rollbackFailureLog.getDetail());
    }
}