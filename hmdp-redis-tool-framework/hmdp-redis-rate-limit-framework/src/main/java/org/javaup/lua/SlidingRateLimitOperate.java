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
 * @description: 滑动
 * @author: 阿星不是程序员
 **/


/**
 * 滑动窗口
 */
@Slf4j
public class SlidingRateLimitOperate {
    //redis操作的组件
    private final RedisCache redisCache;
    //这个构造函数是 SlidingRateLimitOperate 类的依赖注入构造函数，主要作用是：参数 redisCache：是一个封装了 Redis 操作的工具类，用于与 Redis 进行交互。
    //赋值操作：将传入的 redisCache 实例赋值给类的成员变量 this.redisCache，供后续方法使用。
    public SlidingRateLimitOperate(RedisCache redisCache) {
        this.redisCache = redisCache;
    }
    //在lua脚本中进行执行的操作脚本
    private DefaultRedisScript<Integer> redisScript;
    //初始化方法，    主要作用是：加载 lua 脚本，将脚本编译为可执行的字节码。
    @PostConstruct
    public void init(){
        try {
            redisScript = new DefaultRedisScript<>();//构造
            redisScript.setScriptSource(new ResourceScriptSource(new ClassPathResource("lua/rateLimitSliding.lua")));
            redisScript.setResultType(Integer.class);//设置结果类型为 Integer 类型
        } catch (Exception e) {
            log.error("SlidingRateLimitOperate init lua error", e);
        }
    }
    //执行方法，    主要   作用是：执行 lua 脚本，将结果转换为 Long 类型。
    public Long execute(List<String> keys, String[] args){
        return (Long)redisCache.getInstance().execute(redisScript, keys, args);
    }
}