package org.javaup.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.javaup.entity.UserPhone;
import org.javaup.mapper.UserPhoneMapper;
import org.javaup.service.IUserPhoneService;
import org.springframework.stereotype.Service;

/**
 * @program: 黑马点评-plus升级版实战项目。添加 阿星不是程序员 微信，添加时备注 点评 来获取项目的完整资料
 * @description: 用户手机 接口实现
 * @author: 阿星不是程序员
 **/
@Slf4j
@Service
public class UserPhoneServiceImpl extends ServiceImpl<UserPhoneMapper, UserPhone> implements IUserPhoneService {
    
}
