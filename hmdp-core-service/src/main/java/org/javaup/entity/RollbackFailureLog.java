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
 * @description: 回滚失败日志-实体
 * @author: 阿星不是程序员
 **/
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_rollback_failure_log")
public class RollbackFailureLog implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 主键 */
    @TableId(value = "id")
    private Long id;

    /** 优惠券id */
    private Long voucherId;

    /** 用户id */
    private Long userId;

    /** 订单id */
    private Long orderId;

    /** 追踪唯一标识 */
    private Long traceId;

    /** 失败原因或详情 */
    private String detail;

    /** Lua返回码（BaseCode），用于判定失败类型 */
    private Integer resultCode;

    /** 已尝试的重试次数 */
    private Integer retryAttempts;

    /** 来源组件，例如：redis_voucher_data / producer */
    private String source;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;
}