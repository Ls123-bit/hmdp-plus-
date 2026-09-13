package org.javaup.controller;

import cn.hutool.core.bean.BeanUtil;
import jakarta.annotation.Resource;
import org.javaup.dto.ChatRequestDTO;
import org.javaup.dto.Result;
import org.javaup.dto.UserDTO;
import org.javaup.entity.Blog;
import org.javaup.entity.Follow;
import org.javaup.entity.Shop;
import org.javaup.entity.User;
import org.javaup.entity.UserInfo;
import org.javaup.service.IAssistantService;
import org.javaup.service.IBlogService;
import org.javaup.service.IFollowService;
import org.javaup.service.IShopService;
import org.javaup.service.IUserInfoService;
import org.javaup.service.IUserService;
import org.javaup.utils.UserHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * @program: hmdp-plus
 * @description: 智能商户推荐客服api（新增功能，独立控制器，不改动任何原有业务接口）
 * @author: hmdp-plus
 **/
@RestController
@RequestMapping("/cs")
public class AssistantController {

    @Resource
    private IAssistantService assistantService;

    @Resource
    private IShopService shopService;

    @Resource
    private IBlogService blogService;

    @Resource
    private IUserService userService;

    @Resource
    private IFollowService followService;

    @Resource
    private IUserInfoService userInfoService;

    /**
     * 与智能商户推荐客服对话
     *
     * @param request 用户消息与上下文
     * @return 回复文本 + 推荐商户卡片
     */
    @PostMapping("/chat")
    public Result chat(@RequestBody ChatRequestDTO request) {
        return assistantService.chat(request);
    }

    /**
     * 客服能力状态（大模型是否在线），供前端提示
     */
    @GetMapping("/status")
    public Result status() {
        return assistantService.status();
    }

    /**
     * 全量商户列表（只读），供“附近商户”地图页展示与测距
     */
    @GetMapping("/shops")
    public Result shops() {
        List<Shop> list = shopService.list();
        return Result.ok(list);
    }

    /**
     * 商户下的探店笔记（网友评价，只读），供商户详情页评价区展示
     */
    @GetMapping("/blogs")
    public Result shopBlogs(@RequestParam("shopId") Long shopId) {
        List<Blog> blogs = blogService.lambdaQuery()
                .eq(Blog::getShopId, shopId)
                .orderByDesc(Blog::getLiked)
                .last("limit 10")
                .list();
        if (blogs != null && !blogs.isEmpty()) {
            // 补充作者昵称与头像（只读查询）
            List<Long> userIds = blogs.stream().map(Blog::getUserId).distinct().toList();
            Map<Long, User> users = userService.listByIds(userIds).stream()
                    .collect(Collectors.toMap(User::getId, Function.identity(), (a, b) -> a));
            for (Blog b : blogs) {
                User u = users.get(b.getUserId());
                if (u != null) {
                    b.setName(u.getNickName());
                    b.setIcon(u.getIcon());
                }
            }
        }
        return Result.ok(blogs);
    }

    /**
     * 查询某用户的关注列表（用户卡片信息，只读），供消息中心展示
     */
    @GetMapping("/follows/{userId}")
    public Result followsOf(@PathVariable("userId") Long userId) {
        List<Follow> follows = followService.lambdaQuery()
                .eq(Follow::getUserId, userId)
                .list();
        if (follows == null || follows.isEmpty()) {
            return Result.ok(new ArrayList<>());
        }
        List<Long> ids = follows.stream().map(Follow::getFollowUserId).distinct().toList();
        List<UserDTO> dtos = userService.listByIds(ids).stream().map(u -> {
            UserDTO dto = new UserDTO();
            BeanUtil.copyProperties(u, dto);
            return dto;
        }).collect(Collectors.toList());
        return Result.ok(dtos);
    }

    /**
     * 查询某用户的粉丝列表（用户卡片信息，只读），供个人主页粉丝数展示
     */
    @GetMapping("/fans/{userId}")
    public Result fansOf(@PathVariable("userId") Long userId) {
        List<Follow> fans = followService.lambdaQuery()
                .eq(Follow::getFollowUserId, userId)
                .list();
        if (fans == null || fans.isEmpty()) {
            return Result.ok(new ArrayList<>());
        }
        List<Long> ids = fans.stream().map(Follow::getUserId).distinct().toList();
        List<UserDTO> dtos = userService.listByIds(ids).stream().map(u -> {
            UserDTO dto = new UserDTO();
            BeanUtil.copyProperties(u, dto);
            return dto;
        }).collect(Collectors.toList());
        return Result.ok(dtos);
    }

    /**
     * 查询当前登录用户的资料（按 userId 正确路由，含个性签名），供个人主页展示
     */
    @GetMapping("/profile")
    public Result myProfile() {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.ok();
        }
        UserInfo info = userInfoService.lambdaQuery()
                .eq(UserInfo::getUserId, user.getId())
                .one();
        return Result.ok(info);
    }
}
