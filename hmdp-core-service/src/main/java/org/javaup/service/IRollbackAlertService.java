package org.javaup.service;

import org.javaup.entity.RollbackFailureLog;

/**
 * @program: 黑马点评-plus升级版实战项目。添加 阿星不是程序员 微信，添加时备注 点评 来获取项目的完整资料
 * @description: 回滚失败通知服务：用于发送短信/邮件告警（可插拔实现）。
 * @author: 阿星不是程序员
 **/
public interface IRollbackAlertService {

    void sendRollbackAlert(RollbackFailureLog log);
}