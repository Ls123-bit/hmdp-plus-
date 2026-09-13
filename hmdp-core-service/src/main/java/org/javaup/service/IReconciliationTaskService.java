package org.javaup.service;

/**
 * @program: 黑马点评-plus升级版实战项目。添加 阿星不是程序员 微信，添加时备注 点评 来获取项目的完整资料
 * @description: 对账执行 接口
 * @author: 阿星不是程序员
 **/
public interface IReconciliationTaskService {
    
    void reconciliationTaskExecute();

    /**
     * 删除指定券的 Redis 库存键，触发按需重载。
     */
    void delRedisStock(Long voucherId);
}
