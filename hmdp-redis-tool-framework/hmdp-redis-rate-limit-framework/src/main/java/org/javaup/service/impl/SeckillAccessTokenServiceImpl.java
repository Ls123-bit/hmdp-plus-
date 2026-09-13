package org.javaup.service.impl;

import cn.hutool.core.util.IdUtil;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.javaup.core.RedisKeyManage;
import org.javaup.lua.SeckillAccessTokenOperate;
import org.javaup.redis.RedisCache;
import org.javaup.redis.RedisKeyBuild;
import org.javaup.service.ISeckillAccessTokenService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * @program: 黑马点评-plus升级版实战项目。添加 阿星不是程序员 微信，添加时备注 点评 来获取项目的完整资料
 * @description: 令牌实现 接口
 * @author: 阿星不是程序员
 **/
@Slf4j
@Service
public class SeckillAccessTokenServiceImpl implements ISeckillAccessTokenService {

    @Value("${seckill.access.token.enabled:true}")  //默认是true是开启的 开关是可配置化的，用此注解，将配置项配置在我们的配置中心里（比如nacos或者阿波罗）
    private boolean enabled;

    @Value("${seckill.access.token.ttl-seconds:30}")
    private long ttlSeconds; //过期时间 默认30秒

    @Resource
    private RedisCache redisCache;
    
    @Resource
    private MeterRegistry meterRegistry;//上传可视化指标到普罗米修斯  可以获取像我们每一个实例中的请求数，还有jvm的相关指标，比如我们的堆内存，线程数，GC次数，垃圾回收等，然后通过可视化大屏grafana来查看
                                            //springboot可以集成，还有我们的k8s的一些指标也可以上传到普罗米修斯

    private SeckillAccessTokenOperate operate;

    @PostConstruct
    public void init() {
        operate = new SeckillAccessTokenOperate(redisCache);
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }





    //生成我们的权限令牌
    @Override
    public String issueAccessToken(Long voucherId, Long userId) {
        String token = IdUtil.simpleUUID();
        //如果之前没有，才放，否则返回false  详情见setIfAbsent
        boolean ok = redisCache.setIfAbsent(
                RedisKeyBuild.createRedisKey(RedisKeyManage.SECKILL_ACCESS_TOKEN_TAG_KEY, voucherId, userId), 
                token, 
                ttlSeconds, 
                TimeUnit.SECONDS);
        if (!ok) {//如果是false的话，说明之前已经有，要在这里取一下
            String existing = redisCache.get(
                    RedisKeyBuild.createRedisKey(RedisKeyManage.SECKILL_ACCESS_TOKEN_TAG_KEY, voucherId, userId), 
                    String.class);


            safeInc("seckill_access_token_issue_conflict", "component", "service_impl");
            return existing != null ? existing : token;//如果不为空才返回，否则返回token，说明之前没有，说明当前token是新的
        }
        safeInc("seckill_access_token_issue_success", "component", "service_impl");
        log.info("获取到令牌成功！令牌：{}", token);
        return token;
    }

    //生成令牌完之后
    //消耗令牌
    @Override
    public boolean validateAndConsume(Long voucherId, Long userId, String token) {
        String key = RedisKeyBuild.createRedisKey(RedisKeyManage.SECKILL_ACCESS_TOKEN_TAG_KEY, voucherId, userId).getRelKey();
        boolean success = operate.validateAndConsume(key, token);
        safeInc(success ? "seckill_access_token_consume_success" : "seckill_access_token_consume_fail",
                "component", "service_impl");
        return success;
    }



    //参数：指标名，标签，（分组统计）然后就是我们的值
    private void safeInc(String name, String tagKey, String tagValue) {
        try {//捕获一下，不能影响主流程
            if (meterRegistry != null) {//如果引进成功，部位空的话就调用统计  进行自增
                meterRegistry.counter(name, tagKey, tagValue).increment();
            }
        } catch (Exception ignore) {
        }
    }
}