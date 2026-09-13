package org.javaup.execute;
import org.javaup.ratelimit.extension.RateLimitScene;

/**
 * @program: 黑马点评-plus升级版实战项目。添加 阿星不是程序员 微信，添加时备注 点评 来获取项目的完整资料
 * @description: 限流执行 接口
 * @author: 阿星不是程序员
 **/


//具体执行，定义行为
public interface RateLimitHandler {


    //第三个参数，业务有两种，发令牌接口和下单接口（建立一个枚举）
    void execute(Long voucherId, Long userId, RateLimitScene scene);
}
