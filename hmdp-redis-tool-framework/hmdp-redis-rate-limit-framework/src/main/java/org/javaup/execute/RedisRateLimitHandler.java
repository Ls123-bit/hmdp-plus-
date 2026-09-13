package org.javaup.execute;

import jakarta.servlet.http.HttpServletRequest;
import org.javaup.config.SeckillRateLimitConfigProperties;
import org.javaup.core.RedisKeyManage;
import org.javaup.enums.BaseCode;
import org.javaup.exception.HmdpFrameException;
import org.javaup.lua.SlidingRateLimitOperate;
import org.javaup.lua.TokenBucketRateLimitOperate;
import org.javaup.ratelimit.extension.RateLimitContext;
import org.javaup.ratelimit.extension.RateLimitEventListener;
import org.javaup.ratelimit.extension.RateLimitPenaltyPolicy;
import org.javaup.ratelimit.extension.RateLimitScene;
import org.javaup.redis.RedisCache;
import org.javaup.redis.RedisKeyBuild;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * @program: 黑马点评-plus升级版实战项目。添加 阿星不是程序员 微信，添加时备注 点评 来获取项目的完整资料
 * @description: 限流执行 接口实现
 * @author: 阿星不是程序员
 **/
public class RedisRateLimitHandler implements RateLimitHandler {

    //限流配置属性
    private final SeckillRateLimitConfigProperties seckillRateLimitConfigProperties;
  //redis操作的组件
    private final RedisCache redisCache;
    //滑动窗口限流操作
    private final SlidingRateLimitOperate slidingRateLimitOperate;
    //令牌桶限流操作
    private final TokenBucketRateLimitOperate tokenBucketRateLimitOperate;

    private final RateLimitEventListener rateLimitEventListener;
    private final RateLimitPenaltyPolicy rateLimitPenaltyPolicy;

    public RedisRateLimitHandler(SeckillRateLimitConfigProperties seckillRateLimitConfigProperties,
                                 RedisCache redisCache,
                                 SlidingRateLimitOperate slidingRateLimitOperate,
                                 TokenBucketRateLimitOperate tokenBucketRateLimitOperate,
                                 RateLimitEventListener rateLimitEventListener,
                                 RateLimitPenaltyPolicy rateLimitPenaltyPolicy) {
        this.seckillRateLimitConfigProperties = seckillRateLimitConfigProperties;
        this.redisCache = redisCache;
        this.slidingRateLimitOperate = slidingRateLimitOperate;
        this.tokenBucketRateLimitOperate = tokenBucketRateLimitOperate;
        this.rateLimitEventListener = rateLimitEventListener;
        this.rateLimitPenaltyPolicy = rateLimitPenaltyPolicy;
    }


    /**
     * execute是执行限流的总流程，首先是获取ip，验证白名单（在白名单里面不参与限流），验证黑名单（黑名单通过的话获取我们的四个关键参数），获取配置项参数，判断是用滑动窗口还是动态令牌（默认是使用动态令牌桶）限流，然后构建我们的键名（键一个是ip，一个是用户键是根据滑动窗口和动态令牌桶进行区分的，）利用四个参数组装脚本数据，构建上下文，调用脚本执行限流操作
     * 验证结果是不是正确的，如果不是，就抛出对应的异常
     * @param voucherId
     * @param userId
     * @param scene
     */










    @Override
    public void execute(Long voucherId,
                        Long userId,
                        RateLimitScene scene) {
       //获取客户端的ip地址
        String clientIp = resolveClientIp();
       //验证白名单，因为白名单不参与我们的限流，
        //如果在白名单里面，就直接返回不执行限流操作
        if (isWhitelisted(userId, clientIp)) {
            return;
        }
        //验证黑名单
        checkBans(voucherId, userId, clientIp);
        /**
         * 获取我们的配置项参数，要传到我们的脚本里面去执行  从我们的配置项里面去取，但是配置项支持两种业务，所以取的时候也要判断具体是哪种业务
         * 比如我们的ip限流窗口秒数，ip限流最大请求次数，
         * 用户限流窗口秒数，用户限流最大请求次数，
         */
        int ipLimitWindowMillis = resolveIpWindow(scene);//获取ip限流窗口毫秒数
        int ipLimitMaxAttempts = resolveIpMaxAttempts(scene);//获取ip限流最大尝试次数
        int userLimitWindowMillis = resolveUserWindow(scene);//获取用户限流窗口毫秒数
        int userLimitMaxAttempts = resolveUserMaxAttempts(scene);//获取用户限流最大尝试次数
        /**
         * 判断是用滑动窗口还是动态令牌限流  从我们的配置项里面去取
         */
        //是否启动滑动窗口限流，默认是false，用的动态令牌限流，采用动态令牌桶限流操作
        //接下来，调用我们脚本的键（key）和我们的数据，首先是构建我们的键名，
        //构建lua中的键名

        boolean useSliding = resolveSliding();
        //构建lua的键名
        List<String> keys = buildRateLimitKeys(voucherId, userId, clientIp, useSliding);
        //构建lua中的数据
        String[] args = buildArgs(ipLimitWindowMillis, ipLimitMaxAttempts, userLimitWindowMillis, userLimitMaxAttempts);
//todo 参数构建完之后有一些额外的操作比如前置，后置，因为执行限流的时候也需要前置，后置，，比如

        RateLimitContext ctx = buildContext(voucherId, userId, clientIp, keys, useSliding,
                ipLimitWindowMillis, ipLimitMaxAttempts, userLimitWindowMillis, userLimitMaxAttempts);
        safeBeforeExecute(ctx);
        //额外的前置，执行限流，在lua中执行滑动窗口或者令牌的限流功能，
        Integer result = executeLua(useSliding, keys, args);
        ctx.setResult(result);
        //验证是否触发限流，，把上下文放进去
        handleResult(ctx);
    }
    //获取ip限流窗口毫秒数
    private int resolveIpWindow(RateLimitScene scene) {//传参是业务枚举类型。
        SeckillRateLimitConfigProperties.EndpointLimit ep = 
                scene == RateLimitScene.ISSUE_TOKEN //判断是否等操作如果是token，就权限令牌的相关配置 如果不等于就是我们的下单
                        ? 
                        seckillRateLimitConfigProperties.getIssue() 
                        : 
                        seckillRateLimitConfigProperties.getSeckill();
        
        Integer v = 
                ep != null//如果不为空取我们的配置项里面的ip限流窗口毫秒数 如果为空就取默认值
                        ? 
                        ep.getIpWindowMillis() 
                        : 
                        null;
        
        return v != null //如果不为空取他就行 如果为空 取全局变量的ip窗口，如果单个业务里面的配置ip窗口如果为空的话，，就取上面的全局  在SeckillRateLimitConfigProperties类里面配置的默认值  也就是private Integer ipWindowMillis和private Integer ipMaxAttempts

                ? 
                v 
                : 
                seckillRateLimitConfigProperties.getIpWindowMillis();//如果为空，
    }
    //取ip的最大尝试次数
    private int resolveIpMaxAttempts(RateLimitScene scene) {
        SeckillRateLimitConfigProperties.EndpointLimit ep = 
                scene == RateLimitScene.ISSUE_TOKEN 
                        ? 
                        seckillRateLimitConfigProperties.getIssue() 
                        : 
                        seckillRateLimitConfigProperties.getSeckill();
        
        Integer v = 
                ep != null 
                        ? 
                        ep.getIpMaxAttempts() 
                        : 
                        null;
        return v != null 
                ? 
                v 
                : 
                seckillRateLimitConfigProperties.getIpMaxAttempts();
    }
    //用户的窗口
    private int resolveUserWindow(RateLimitScene scene) {
        SeckillRateLimitConfigProperties.EndpointLimit ep = 
                scene == RateLimitScene.ISSUE_TOKEN 
                        ? 
                        seckillRateLimitConfigProperties.getIssue() 
                        : 
                        seckillRateLimitConfigProperties.getSeckill();
        
        Integer v = 
                ep != null 
                        ? 
                        ep.getUserWindowMillis() 
                        : 
                        null;
        
        return v != null 
                ? 
                v 
                : 
                seckillRateLimitConfigProperties.getUserWindowMillis();
    }
    //取用户的的最大尝试次数
    private int resolveUserMaxAttempts(RateLimitScene scene) {
        SeckillRateLimitConfigProperties.EndpointLimit ep = 
                scene == RateLimitScene.ISSUE_TOKEN 
                        ? 
                        seckillRateLimitConfigProperties.getIssue() 
                        : 
                        seckillRateLimitConfigProperties.getSeckill();
        
        Integer v = 
                ep != null 
                        ? 
                        ep.getUserMaxAttempts() 
                        : 
                        null;
        
        return v != null 
                ? 
                v 
                : 
                seckillRateLimitConfigProperties.getUserMaxAttempts();
    }

    private boolean resolveSliding() {
        return seckillRateLimitConfigProperties.getEnableSlidingWindow();
    }



    //从request里面获取ip地址，
    private String resolveClientIp(){
        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs == null) {
                return null;
            }
            HttpServletRequest request = attrs.getRequest();//从request请求头获取内容
            String xff = request.getHeader("X-Forwarded-For");
            if (xff != null && !xff.isEmpty()) {
                String[] parts = xff.split(",");
                if (parts.length > 0) {
                    String ip = parts[0].trim();//获取第一个ip地址
                    if (!ip.isEmpty()) {
                        return ip;
                    }
                }
            }
            String realIp = request.getHeader("X-Real-IP");
            if (realIp != null && !realIp.isEmpty()) {
                return realIp;
            }
            return request.getRemoteAddr();
        } catch (Exception e) {
            return null;
        }
    }
//验证是否在白名单里面，一个是用户id一个是ip
    private boolean isWhitelisted(Long userId, 
                                  String clientIp) {
        try {//捕获，如果是异常的话，就抛出错误，不要影响主线程
            //ip不可以为空， 并且   seckillRateLimitConfigProperties配置下的白名单列表也不可以为空
            return (clientIp != null && seckillRateLimitConfigProperties.getIpWhitelist() != null
             //验证ip是否在白名单里面
                    && seckillRateLimitConfigProperties.getIpWhitelist().contains(clientIp))

                    //或的条件，两个条件，有一个为true，就返回true


                    //用户id不为空，用户id的白名单也不为空，
                    || (userId != null && seckillRateLimitConfigProperties.getUserWhitelist() != null
                    //验证用户id是否在白名单里面     用户白名单配置包含用户id，说明是在这个白名单中的，
                    && seckillRateLimitConfigProperties.getUserWhitelist().contains(userId));
        } catch (Exception e) {
            return false;
        }
    }
  //验证黑名单，
    private void checkBans(Long voucherId, 
                           Long userId, 
                           String clientIp) {
        //如果我们的ip不为空的话，就验证ip是否在黑名单里面
        if (Objects.nonNull(clientIp)) {
            //黑名单通过限流（执行完我们的脚本之后）以后会放在我们的redis里面 需要在redis里面去取
            //判断redis里面有没有黑名单
            boolean ipBlocked = Boolean.TRUE.equals(redisCache.hasKey(
                    RedisKeyBuild.createRedisKey(RedisKeyManage.SECKILL_BLOCK_IP_TAG_KEY, voucherId, clientIp)));
            if (ipBlocked) {//如果在黑名单里面，就抛出异常不让他继续执行，
                throw new HmdpFrameException(BaseCode.SECKILL_RATE_LIMIT_IP_EXCEEDED);//触发我们的限制
            }
        }
        //判断我们的用户id是不是也在黑名单，执行脚本以后也会放在我们的redis里面，
        boolean userBlocked = Boolean.TRUE.equals(redisCache.hasKey(
                RedisKeyBuild.createRedisKey(RedisKeyManage.SECKILL_BLOCK_USER_TAG_KEY, voucherId, userId)));
        if (userBlocked) {//如果在黑名单里面，就抛出异常不让他继续执行，
            throw new HmdpFrameException(BaseCode.SECKILL_RATE_LIMIT_USER_EXCEEDED);
        }
    }


    //构建lua中的键名
    private List<String> buildRateLimitKeys(Long voucherId, 
                                            Long userId, 
                                            String clientIp, 
                                            boolean useSliding) {//要不要使用滑动窗口
        List<String> keys = new ArrayList<>(2);//结果先构造出来，就两个，先初始化一下长度为2，省的他扩容
        if (Objects.nonNull(clientIp)) {//判断，如果我们的ip不为空的话，ip启动滑动窗口和使用令牌桶是取得不一样的，如果启动滑动窗口的话，取得的配置是不一样的，取的配置不一样，在redis里面去具体创建数据的时候，对应的key是不一样的，
            String ipKey = useSliding  //如果不用滑动窗口就使用令牌桶，
                    ? RedisKeyBuild.createRedisKey(RedisKeyManage.SECKILL_LIMIT_IP_SW_TAG_KEY, voucherId, clientIp).getRelKey()
                    : RedisKeyBuild.createRedisKey(RedisKeyManage.SECKILL_LIMIT_IP_TB_TAG_KEY, voucherId, clientIp).getRelKey();
            keys.add(ipKey);//接受一下ip的key，加入到我们的集合里
        }//以上是ip维度的key，下面的是用户维度的key
        String userKey = useSliding
                ? RedisKeyBuild.createRedisKey(RedisKeyManage.SECKILL_LIMIT_USER_SW_TAG_KEY, voucherId, userId).getRelKey()
                : RedisKeyBuild.createRedisKey(RedisKeyManage.SECKILL_LIMIT_USER_TB_TAG_KEY, voucherId, userId).getRelKey();
        keys.add(userKey);
        return keys;
    }
    //构建lua中的数据   数组，
    private String[] buildArgs(int ipWindowMillis, //四个参数
                               int ipMaxAttempts, 
                               int userWindowMillis, 
                               int userMaxAttempts) {
        String[] args = new String[4];
        args[0] = String.valueOf(ipWindowMillis);
        args[1] = String.valueOf(ipMaxAttempts);
        args[2] = String.valueOf(userWindowMillis);
        args[3] = String.valueOf(userMaxAttempts);
        return args;
    }
//构建上下文
    private RateLimitContext buildContext(Long voucherId, 
                                          Long userId, 
                                          String clientIp, 
                                          List<String> keys,
                                          boolean useSliding,
                                          int ipWindowMillis, int ipMaxAttempts,
                                          int userWindowMillis, int userMaxAttempts) {
        return new RateLimitContext(
                voucherId,
                userId,
                clientIp,
                keys,
                useSliding,
                ipWindowMillis,
                ipMaxAttempts,
                userWindowMillis,
                userMaxAttempts
        );
    }

    private void safeBeforeExecute(RateLimitContext ctx) {
        rateLimitEventListener.onBeforeExecute(ctx);
    }
    //执行限流，在lua中执行滑动窗口或者令牌的限流功能   把开关传进去，使用滑动窗口还是令牌，把构建的key还有数据传进去，
    private Integer executeLua(boolean useSliding, List<String> keys, String[] args) {
        return useSliding //返回，看调用哪一个脚本，如果是滑动窗口，就调用滑动窗口的脚本，如果是令牌桶，就调用令牌桶的脚本，
                ?
                slidingRateLimitOperate.execute(keys, args).intValue() 
                :
                tokenBucketRateLimitOperate.execute(keys, args).intValue();
    }
//判断是否触发限流？   结果要先把上下文构造出来 RateLimitContext
    private void handleResult(RateLimitContext ctx) {
        Integer result = ctx.getResult();
        if (BaseCode.SUCCESS.getCode().equals(result)) {//如果成功，就调用成功的方法，
            rateLimitEventListener.onAllowed(ctx);
            return;
        }
        if (BaseCode.SECKILL_RATE_LIMIT_IP_EXCEEDED.getCode().equals(result)) {//如果ip维度的限流被触发了，
            rateLimitEventListener.onBlocked(ctx, BaseCode.SECKILL_RATE_LIMIT_IP_EXCEEDED);
            rateLimitPenaltyPolicy.apply(ctx, BaseCode.SECKILL_RATE_LIMIT_IP_EXCEEDED);
            throw new HmdpFrameException(BaseCode.SECKILL_RATE_LIMIT_IP_EXCEEDED);
        }
        if (BaseCode.SECKILL_RATE_LIMIT_USER_EXCEEDED.getCode().equals(result)) {//如果用户维度的限流被触发了，
            rateLimitEventListener.onBlocked(ctx, BaseCode.SECKILL_RATE_LIMIT_USER_EXCEEDED);
            rateLimitPenaltyPolicy.apply(ctx, BaseCode.SECKILL_RATE_LIMIT_USER_EXCEEDED);
            throw new HmdpFrameException(BaseCode.SECKILL_RATE_LIMIT_USER_EXCEEDED);
        }
        throw new HmdpFrameException("操作频繁，请稍后再试");
    }
}
