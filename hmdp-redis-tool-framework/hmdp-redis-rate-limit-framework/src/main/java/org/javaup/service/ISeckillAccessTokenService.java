package org.javaup.service;

/**
 * @program: 黑马点评-plus升级版实战项目。添加 阿星不是程序员 微信，添加时备注 点评 来获取项目的完整资料
 * @description: 令牌
 * @author: 阿星不是程序员
 **/
public interface ISeckillAccessTokenService {
    /**
     * 是否启用访问令牌校验
     * @return
     */
    boolean isEnabled();

    /**
     * 为用户申请指定voucher的访问令牌
     * @param voucherId
     * @param userId
     * @return
     */
    String issueAccessToken(Long voucherId, Long userId);

    /**
     * 校验并消费令牌（原子）   校验过就废了，一次一用
     * @param voucherId
     * @param userId
     * @param token
     * @return
     */
    boolean validateAndConsume(Long voucherId, Long userId, String token);
}