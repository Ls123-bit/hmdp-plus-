package org.javaup.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * @program: 黑马点评-plus升级版实战项目。添加 阿星不是程序员 微信，添加时备注 点评 来获取项目的完整资料
 * @description: 秒杀优惠券订单
 * @author: 阿星不是程序员
 **/
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_voucher_order")
public class VoucherOrder implements Serializable {

    private static final long serialVersionUID = 1L;
    
    @TableId(value = "id")
    private Long id;
    
    private Long userId;
    
    private Long voucherId;
    
    private Integer payType;
    
    private Integer status;
    
    private Integer reconciliationStatus;
    
    private LocalDateTime createTime;
    
    private LocalDateTime payTime;
    
    private LocalDateTime useTime;
    
    private LocalDateTime refundTime;
    
    private LocalDateTime updateTime;
    
}
