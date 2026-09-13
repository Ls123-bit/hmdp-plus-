package org.javaup.lua;

import com.alibaba.fastjson.JSON;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.javaup.redis.RedisCache;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * @program: 黑马点评-plus升级版实战项目。添加 阿星不是程序员 微信，添加时备注 点评 来获取项目的完整资料
 * @description: 秒杀
 * @author: 阿星不是程序员
 **/
@Slf4j
@Component
public class SeckillVoucherOperate {//加载lua脚本的执行器
    
    @Resource
    private RedisCache redisCache;
    
    private DefaultRedisScript<String> redisScript;//我们的脚本
    
    @PostConstruct//当项目启动的时候，就把脚本加载进去，先加载再执行
    public void init(){
        try {
            redisScript = new DefaultRedisScript<>();
            redisScript.setScriptSource(new ResourceScriptSource(new ClassPathResource("lua/seckillVoucher.lua")));
            redisScript.setResultType(String.class);
        } catch (Exception e) {
            log.error("redisScript init lua error",e);
        }
    }
    //执行秒杀券相关的 Lua 脚本并处理返回结果的核心方法
    public SeckillVoucherDomain execute(List<String> keys, String[] args){
        Object object = redisCache.getInstance().execute(redisScript, keys, args);//执行lua脚本  执行预加载的 seckillVoucher.lua 脚本。
        return JSON.parseObject((String)object, SeckillVoucherDomain.class);//解析返回结果：将脚本执行返回的 Object 转换为 String，再通过 JSON.parseObject 解析为 SeckillVoucherDomain 业务对象。
                                                                            //返回业务对象：将解析后的 SeckillVoucherDomain 对象返回给调用方。

    }
}
