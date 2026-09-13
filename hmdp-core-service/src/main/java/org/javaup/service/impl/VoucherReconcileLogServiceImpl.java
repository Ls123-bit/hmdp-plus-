package org.javaup.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import jakarta.annotation.Resource;
import org.javaup.dto.VoucherReconcileLogDto;
import org.javaup.entity.VoucherReconcileLog;
import org.javaup.enums.LogType;
import org.javaup.kafka.message.SeckillVoucherMessage;
import org.javaup.mapper.VoucherReconcileLogMapper;
import org.javaup.message.MessageExtend;
import org.javaup.service.IVoucherReconcileLogService;
import org.javaup.toolkit.SnowflakeIdGenerator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * @program: 黑马点评-plus升级版实战项目。添加 阿星不是程序员 微信，添加时备注 点评 来获取项目的完整资料
 * @description: 对账日志 接口实现
 * @author: 阿星不是程序员
 **/
@Service
public class VoucherReconcileLogServiceImpl extends ServiceImpl<VoucherReconcileLogMapper, VoucherReconcileLog>
        implements IVoucherReconcileLogService {
    
    @Resource
    private SnowflakeIdGenerator snowflakeIdGenerator;//id生成器
    
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean saveReconcileLog(final Integer logType, final Integer businessType, final String detail, final MessageExtend<SeckillVoucherMessage> message) {
        SeckillVoucherMessage messageBody = message.getMessageBody();
        VoucherReconcileLogDto voucherReconcileLogDto = new VoucherReconcileLogDto();
        voucherReconcileLogDto.setOrderId(messageBody.getOrderId());
        voucherReconcileLogDto.setUserId(messageBody.getUserId());
        voucherReconcileLogDto.setVoucherId(messageBody.getVoucherId());
        voucherReconcileLogDto.setMessageId(message.getUuid());
        voucherReconcileLogDto.setDetail(detail);
        voucherReconcileLogDto.setBeforeQty(messageBody.getBeforeQty());
        voucherReconcileLogDto.setChangeQty(messageBody.getChangeQty());
        voucherReconcileLogDto.setAfterQty(messageBody.getAfterQty());
        voucherReconcileLogDto.setTraceId(messageBody.getTraceId());//
        voucherReconcileLogDto.setLogType(logType);
        voucherReconcileLogDto.setBusinessType(businessType);
        if (voucherReconcileLogDto.getLogType().equals(LogType.RESTORE.getCode())) {
            voucherReconcileLogDto.setBeforeQty(messageBody.getAfterQty());
            voucherReconcileLogDto.setAfterQty(messageBody.getBeforeQty());
        }
        return saveReconcileLog(voucherReconcileLogDto);
    }
    
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean saveReconcileLog(final Integer logType, final Integer businessType, final String detail, final Long traceId, final MessageExtend<SeckillVoucherMessage> message) {
        SeckillVoucherMessage messageBody = message.getMessageBody();//获取消息体 从消息体里面拿取信息
        VoucherReconcileLogDto voucherReconcileLogDto = new VoucherReconcileLogDto();
        voucherReconcileLogDto.setOrderId(messageBody.getOrderId());
        voucherReconcileLogDto.setUserId(messageBody.getUserId());
        voucherReconcileLogDto.setVoucherId(messageBody.getVoucherId());
        voucherReconcileLogDto.setMessageId(message.getUuid());
        voucherReconcileLogDto.setDetail(detail);
        voucherReconcileLogDto.setBeforeQty(messageBody.getBeforeQty());
        voucherReconcileLogDto.setChangeQty(messageBody.getChangeQty());
        voucherReconcileLogDto.setAfterQty(messageBody.getAfterQty());
        voucherReconcileLogDto.setTraceId(traceId);//关联id是从外面传进来的
        voucherReconcileLogDto.setLogType(logType);
        voucherReconcileLogDto.setBusinessType(businessType);
        if (voucherReconcileLogDto.getLogType().equals(LogType.RESTORE.getCode())) {//如果是恢复日志，需要交换 beforeQty 和 afterQty 将扣减前和扣减后的库存反转回来
            voucherReconcileLogDto.setBeforeQty(messageBody.getAfterQty());
            voucherReconcileLogDto.setAfterQty(messageBody.getBeforeQty());
        }
        return saveReconcileLog(voucherReconcileLogDto);
    }
    
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean saveReconcileLog(VoucherReconcileLogDto voucherReconcileLogDto) {
        VoucherReconcileLog logEntity = new VoucherReconcileLog();//创建实例对象，实例化VoucherReconcileLog，用于存储对账日志数据
        logEntity.setId(snowflakeIdGenerator.nextId())   //链式设计属性：setId：使用雪花算法生成全局唯一 ID。业务字段：从 DTO 中提取订单 ID、用户 ID、优惠券 ID、消息 ID 等业务数据。类型字段：设置业务类型（如订单创建、库存扣减）、日志类型（如正常、失败）。时间字段：设置创建时间和更新时间为当前时间。库存字段：记录库存变化的详细信息（扣减前、变化量、扣减后）。
                .setOrderId(voucherReconcileLogDto.getOrderId())
                .setUserId(voucherReconcileLogDto.getUserId())
                .setVoucherId(voucherReconcileLogDto.getVoucherId())
                .setMessageId(voucherReconcileLogDto.getMessageId())
                .setBusinessType(voucherReconcileLogDto.getBusinessType())
                .setDetail(voucherReconcileLogDto.getDetail())
                .setTraceId(voucherReconcileLogDto.getTraceId())
                .setLogType(voucherReconcileLogDto.getLogType())
                .setCreateTime(LocalDateTime.now())
                .setUpdateTime(LocalDateTime.now())
                .setBeforeQty(voucherReconcileLogDto.getBeforeQty())
                .setChangeQty(voucherReconcileLogDto.getChangeQty())
                .setAfterQty(voucherReconcileLogDto.getAfterQty());
        return save(logEntity);//保存到数据库：调用父类的 save 方法将实体持久化到数据库。
    }
}