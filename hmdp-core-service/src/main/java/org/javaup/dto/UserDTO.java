package org.javaup.dto;

import lombok.Data;
/**
 * @program: 黑马点评-plus升级版实战项目。添加 阿星不是程序员 微信，添加时备注 点评 来获取项目的完整资料
 * @description: 用户-入参
 * @author: 阿星不是程序员
 **/
@Data
public class UserDTO {
    private Long id;
    private String nickName;
    private String icon;
}
