package org.javaup.lua;

import lombok.Data;

/**
 * @program: 黑马点评-plus升级版实战项目。添加 阿星不是程序员 微信，添加时备注 点评 来获取项目的完整资料
 * @description: lua秒杀返回数据
 * @author: 阿星不是程序员
 **/
@Data
public class SeckillVoucherDomain {

    private Integer code;//状态码 判断执行脚本是否成功了
    
    private Integer beforeQty;//扣减前的库存
    
    private Integer deductQty;//扣减的数量
    
    private Integer afterQty;//扣减后的库存

}
