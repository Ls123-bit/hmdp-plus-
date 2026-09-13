package org.javaup.service;

import org.javaup.dto.Result;
import org.javaup.entity.Follow;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * @program: 黑马点评-plus升级版实战项目。添加 阿星不是程序员 微信，添加时备注 点评 来获取项目的完整资料
 * @description: 关注接口
 * @author: 阿星不是程序员
 **/
public interface IFollowService extends IService<Follow> {

    Result follow(Long followUserId, Boolean isFollow);

    Result isFollow(Long followUserId);

    Result followCommons(Long id);
}
