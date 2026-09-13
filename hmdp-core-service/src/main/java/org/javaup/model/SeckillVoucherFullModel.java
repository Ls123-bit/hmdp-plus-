package org.javaup.model;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * @program: 黑马点评-plus升级版实战项目。添加 阿星不是程序员 微信，添加时备注 点评 来获取项目的完整资料
 * @description: 秒杀优惠券的全部信息
 * @author: 阿星不是程序员
 **/
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
public class SeckillVoucherFullModel implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;
    
    private Long id;
    
    private Long voucherId;

    private Integer initStock;
    
    private Integer stock;
    
    private String allowedLevels;
    
    private Integer minLevel;
    
    private LocalDateTime createTime;
    
    private LocalDateTime beginTime;
    
    private LocalDateTime endTime;
    
    private Integer status;
    
    private Long shopId;

}
