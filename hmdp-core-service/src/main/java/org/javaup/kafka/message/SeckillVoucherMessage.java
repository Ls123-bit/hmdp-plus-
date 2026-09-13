package org.javaup.kafka.message;

import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import lombok.Data;

/**
 * @program: 黑马点评-plus升级版实战项目。添加 阿星不是程序员 微信，添加时备注 点评 来获取项目的完整资料
 * @description: 秒杀券消息
 * @author: 阿星不是程序员
 **/

@Data
@AllArgsConstructor
@NoArgsConstructor
public class SeckillVoucherMessage {  //秒杀券消息   构建一个消息对象，用于发送到kafka
//为什么要要有这个参数：因为发送到kafka，除了正常是在redis扣减完放到kafka发送了，这边消费以后，他还有重试机制，还有别的比如死信队列里面，所以不是这里面的每一个参数都得执行通知
    private Long userId;
    
    private Long voucherId;
    
    private Long orderId;
//唯一追踪标识 与订单记录关联
    private Long traceId;
//扣减前库存数量
    private Integer beforeQty;
    //本次扣减数量
    private Integer changeQty;
    //扣减后库存数量
    private Integer afterQty;
    //是否为回滚后自动发券流程产生的消息    订阅之后如果有新库存会进行自动的领券，领券后也要发送到kafka 通过autoIssue字段决定要不要通知这个用户
    //如果是正常购买的话，就不用通知了   前端不断地轮询我们的redis，kafka消费以后把一个订单的id放到我们的redis中轮询到，轮循到就告诉用户通知了
    private Boolean autoIssue;
}
