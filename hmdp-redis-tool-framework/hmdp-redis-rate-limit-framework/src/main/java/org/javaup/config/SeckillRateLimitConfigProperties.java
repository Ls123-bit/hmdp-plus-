package org.javaup.config;


import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.io.Serializable;
import java.util.Collections;
import java.util.Set;
/**
 * @program: 黑马点评-plus升级版实战项目。添加 阿星不是程序员 微信，添加时备注 点评 来获取项目的完整资料
 * @description: 限流配置
 * @author: 阿星不是程序员
 **/
@Data
@ConfigurationProperties(prefix = SeckillRateLimitConfigProperties.PREFIX)
public class SeckillRateLimitConfigProperties implements Serializable {



    public static final String PREFIX = "rate-limit";


    /**
     * 是否启动滑动窗口限流，默认关闭，使用令牌(令牌在限流高并发场景比滑动窗口更稳定) 可开启可关闭，
     */
    private Boolean enableSlidingWindow = false;

    /**
     * ip限流窗口毫秒数
     */
    private Integer ipWindowMillis = 5000;


    /**
     * ip限流最大尝试次数
     */
    private Integer ipMaxAttempts = 3;

    /**
     * 用户限流窗口毫秒数
     */
    private Integer userWindowMillis = 60000;

    /**
     * 用户限流最大尝试次数
     */
    private Integer userMaxAttempts = 5;

    /**
     * ip白名单   白名单是特殊用户，命中就直接放行 这种的就不参与限流，  默认是空的
     */
    private Set<String> ipWhitelist = Collections.emptySet();

    /**
     * 用户白名单   白名单是特殊用户，命中就直接放行 这种的就不参与限流，  默认是空的
     */
    private Set<Long> userWhitelist = Collections.emptySet();

    /**
     * 黑名单开关，是否开启黑名单封禁状态（临时封禁惩罚策略） 封禁启用不启用要有开关  默认关闭
     */
    private Boolean enablePenalty = false;

    /**
     * 统计违规，被限流阻断，计数的时间窗口 单位：秒
     * 违规窗口秒数，默认60秒     违规的话统计被限流阻断的计时的时间窗口
     */
    private Integer violationWindowSeconds = 60;

    /**
     * ip维度的封禁阈值，（统计窗口内累计被阻断次数达到该值触发封禁）
     * ip限流违规次数阈值，默认5次  达到该值，触发违禁
     */
    private Integer ipBlockThreshold = 5;

    /**
     * 用户维度的封禁阈值，（统计窗口内累计被阻断次数达到该值触发封禁）
     */
    private Integer userBlockThreshold = 5;

    /**
     * ip维度的封禁时间，单位：秒
     */
    private Integer ipBlockTtlSeconds = 300;

    /**
     * 用户维度的封禁时间，单位：秒
     */
    private Integer userBlockTtlSeconds = 300;









    //权限令牌限流配置（发令牌接口的限流覆盖配置）
    private EndpointLimit issue = new EndpointLimit();
    //下单限流配 置（下单接口的限流覆盖配置）
    private EndpointLimit seckill = new EndpointLimit();
    //全级别，有两个维度，一个是令牌一个是下单，这是两个维度，要获取动态权限令牌  要限流，下单也要限流，
    //两种业务，最好他们的参数元进行两种配置，
    //内部的静态类来承担这两个配置，    应该覆盖限流窗口和最大尝试次数

    @Data
    public static class EndpointLimit implements Serializable {
        //ip限流窗口毫秒数（如果此配置为空，则使用全局的ipWindowMillis配置）
        private Integer ipWindowMillis;
        //ip限流最大尝试次数（如果此配置为空，则使用全局的ipMaxAttempts配置）
        private Integer ipMaxAttempts;
        //用户限流窗口毫秒数
        private Integer userWindowMillis;
        //用户限流最大尝试次数
        private Integer userMaxAttempts;
    }
}