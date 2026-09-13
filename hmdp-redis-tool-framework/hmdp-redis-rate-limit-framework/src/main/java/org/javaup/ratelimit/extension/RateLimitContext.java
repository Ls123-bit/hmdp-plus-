package org.javaup.ratelimit.extension;

import lombok.Data;

import java.util.List;

/**
 * @program: 黑马点评-plus升级版实战项目。添加 阿星不是程序员 微信，添加时备注 点评 来获取项目的完整资料
 * @description: 限流执行上下文
 * @author: 阿星不是程序员
 **/
//上下文里面要传一些必要的信息， 在这里构建上下文，
@Data
public class RateLimitContext {

    private Long voucherId;
    private Long userId;
    private String clientIp;

    private List<String> keys;
    private boolean useSliding;

    private int ipWindowMillis;
    private int ipMaxAttempts;
    private int userWindowMillis;
    private int userMaxAttempts;

    private Integer result;

    public RateLimitContext() {}

    public RateLimitContext(Long voucherId, Long userId, String clientIp,
                            List<String> keys, boolean useSliding,
                            int ipWindowMillis, int ipMaxAttempts,
                            int userWindowMillis, int userMaxAttempts) {
        this.voucherId = voucherId;
        this.userId = userId;
        this.clientIp = clientIp;
        this.keys = keys;
        this.useSliding = useSliding;
        this.ipWindowMillis = ipWindowMillis;
        this.ipMaxAttempts = ipMaxAttempts;
        this.userWindowMillis = userWindowMillis;
        this.userMaxAttempts = userMaxAttempts;
    }
}