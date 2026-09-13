package org.javaup.lua;

import org.javaup.redis.RedisCache;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;

import java.util.Collections;
import java.util.List;

/**
 * @program: 黑马点评-plus升级版实战项目。添加 阿星不是程序员 微信，添加时备注 点评 来获取项目的完整资料
 * @description: 校验 并消费秒杀访问令牌
 * @author: 阿星不是程序员
 **/
public class SeckillAccessTokenOperate {

    private final DefaultRedisScript<Long> script;
    private final RedisCache redisCache;

    public SeckillAccessTokenOperate(RedisCache redisCache) {
        this.redisCache = redisCache;
        this.script = new DefaultRedisScript<>();
        this.script.setScriptSource(new ResourceScriptSource(new ClassPathResource("lua/seckill_access_token.lua")));
        this.script.setResultType(Long.class);
    }
    //执行验证我们的权限令牌
    public boolean validateAndConsume(String key, String expected) {
        List<String> keys = Collections.singletonList(key);
        Long ret = (Long)redisCache.getInstance().execute(script, keys, expected);
        return ret == 1L;
    }
}