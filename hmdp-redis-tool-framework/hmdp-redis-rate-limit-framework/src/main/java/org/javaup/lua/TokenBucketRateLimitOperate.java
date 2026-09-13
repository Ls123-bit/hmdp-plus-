package org.javaup.lua;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.javaup.redis.RedisCache;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;

import java.util.List;

/**
 * @program: 黑马点评-plus升级版实战项目。添加 阿星不是程序员 微信，添加时备注 点评 来获取项目的完整资料
 * @description: 令牌
 * @author: 阿星不是程序员
 **/
@Slf4j
public class TokenBucketRateLimitOperate {

    private final RedisCache redisCache;
    //令牌桶限流操作 引入
    public TokenBucketRateLimitOperate(RedisCache redisCache) {
        this.redisCache = redisCache;
    }

    private DefaultRedisScript<Integer> redisScript;

    @PostConstruct
    public void init(){
        try {
            redisScript = new DefaultRedisScript<>();
            redisScript.setScriptSource(new ResourceScriptSource(new ClassPathResource("lua/tokenBucket.lua")));
            redisScript.setResultType(Integer.class);
        } catch (Exception e) {
            log.error("TokenBucketRateLimitOperate init lua error", e);
        }
    }
  //执行方法，
    public Long execute(List<String> keys, String[] args){
        return (Long)redisCache.getInstance().execute(redisScript, keys, args);
    }
}