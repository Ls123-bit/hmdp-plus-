package org.javaup.service.impl;

import cn.hutool.core.util.StrUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.javaup.core.RedisKeyManage;
import org.javaup.redis.RedisCache;
import org.javaup.redis.RedisKeyBuild;
import org.javaup.service.IAutoIssueNotifyService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * @program: 黑马点评-plus升级版实战项目。添加 阿星不是程序员 微信，添加时备注 点评 来获取项目的完整资料
 * @description: 自动发券成功后的用户通知服务接口实现
 * @author: 阿星不是程序员
 **/
@Slf4j
@Service
public class AutoIssueNotifyServiceImpl implements IAutoIssueNotifyService {

    @Value("${seckill.autoissue.notify.sms.enabled:false}")//用此注解参数灵活配置
    private boolean smsEnabled;//是否开启短信通知

    @Value("${seckill.autoissue.notify.app.enabled:false}")
    private boolean appEnabled;//是否开启app应用通知

    @Value("${seckill.autoissue.notify.sms.to:default}")//短信的默认值为default
    private String smsTo;//短信通知接收人

    @Value("${seckill.autoissue.notify.dedup.window.seconds:300}")
    private long dedupWindowSeconds;//时间间隔不可以一直通知

    @Resource
    private RedisCache redisCache;

    @Override
    public void sendAutoIssueNotify(Long voucherId, Long userId, Long orderId) {
        try {
            if (!shouldNotify(voucherId, userId)) { //如果不应该通知的话就终止
                return;
            }
            //如果应该通知的话构建一下消息内容
            String content = String.format("自动发券成功 | voucherId=%s userId=%s orderId=%s", voucherId, userId, orderId);
            //如果开启了短信通知，且短信通知接收人不为空，就发送短信通知
            if (smsEnabled && StrUtil.isNotBlank(smsTo)) {
                //todo 真正发短信肯定是调用云厂商的短信的sdk
                log.info("[AUTOISSUE_SMS] to={} content={}", smsTo, content);
            }
            //如果开启了app应用通知，就发送app应用通知（app不用短信接收人，直接用用户id即可）
            if (appEnabled) {
                log.info("[AUTOISSUE_APP] userId={} content={}", userId, content);
            }
        } catch (Exception e) {
            log.warn("发送自动发券通知异常", e);
        }
    }

    private boolean shouldNotify(Long voucherId, Long userId) {
        try {
            return redisCache.setIfAbsent(//判断有没有，如果没有的话 就设置1 一个过期时间  然后返回一个true，如果没有的话就不往里面设置了 返回false
                    RedisKeyBuild.createRedisKey(RedisKeyManage.SECKILL_AUTO_ISSUE_NOTIFY_DEDUP_KEY, voucherId, userId),
                    "1",
                    dedupWindowSeconds, //窗口当作过期时间
                    TimeUnit.SECONDS
            );
        } catch (Exception e) {
            return true;
        }
    }
}