package org.javaup.servicelock.factory;

import org.javaup.core.ManageLocker;
import org.javaup.servicelock.LockType;
import org.javaup.servicelock.ServiceLocker;
import lombok.AllArgsConstructor;

/**
 * @program: 黑马点评-plus升级版实战项目。添加 阿星不是程序员 微信，添加时备注 点评 来获取项目的完整资料 
 * @description: 工厂
 * @author: 阿星不是程序员
 * 锁的工厂
 **/
@AllArgsConstructor
public class ServiceLockFactory {
    
    private final ManageLocker manageLocker;
    

    public ServiceLocker getLock(LockType lockType){
        ServiceLocker lock;
        switch (lockType) {
            case Fair://公平锁
                lock = manageLocker.getFairLocker();
                break;
            case Write://写锁
                lock = manageLocker.getWriteLocker();
                break;
            case Read: //读锁
                lock = manageLocker.getReadLocker();
                break;
            default://默认是非公平锁
                lock = manageLocker.getReentrantLocker();
                break;
        }
        return lock;
    }
}
