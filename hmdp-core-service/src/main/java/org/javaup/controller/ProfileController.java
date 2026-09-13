package org.javaup.controller;

import jakarta.annotation.Resource;
import org.javaup.dto.Result;
import org.javaup.dto.UserDTO;
import org.javaup.entity.Blog;
import org.javaup.entity.ShopFollow;
import org.javaup.entity.UserInfo;
import org.javaup.mapper.ShopFollowMapper;
import org.javaup.service.IBlogService;
import org.javaup.service.IUserInfoService;
import org.javaup.utils.UserHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * @program: hmdp-plus
 * @description: 个人资料api（新增功能，独立控制器，不改动任何原有业务接口）。
 * 注意：/profile/** 不在登录拦截白名单中，必须登录后才能调用。
 * @author: hmdp-plus
 **/
@RestController
@RequestMapping("/profile")
public class ProfileController {

    @Resource
    private IUserInfoService userInfoService;

    @Resource
    private ShopFollowMapper shopFollowMapper;

    @Resource
    private IBlogService blogService;

    /**
     * 修改当前登录用户的个性签名
     *
     * @param body { introduce: 新签名 }
     */
    @PostMapping("/introduce")
    public Result updateIntroduce(@RequestBody Map<String, String> body) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("请先登录");
        }
        String introduce = body == null ? null : body.get("introduce");
        if (introduce == null || introduce.trim().isEmpty()) {
            return Result.fail("个性签名不能为空");
        }
        introduce = introduce.trim();
        if (introduce.length() > 200) {
            return Result.fail("个性签名不能超过200字");
        }
        UserInfo info = userInfoService.lambdaQuery()
                .eq(UserInfo::getUserId, user.getId())
                .one();
        if (info == null) {
            info = new UserInfo();
            info.setUserId(user.getId());
            info.setIntroduce(introduce);
            userInfoService.save(info);
        } else {
            // 只更新简介列，避免把分片键 user_id 带进 SET 子句（ShardingSphere 禁止）
            userInfoService.lambdaUpdate()
                    .eq(UserInfo::getUserId, user.getId())
                    .set(UserInfo::getIntroduce, introduce)
                    .update();
        }
        return Result.ok();
    }

    // ==================== 商户关注（新增功能） ====================

    /**
     * 关注/取消关注商户（切换状态）
     *
     * @return 关注 true / 已取消 false
     */
    @PutMapping("/shop/{shopId}/follow")
    public Result followShop(@PathVariable("shopId") Long shopId) {
        UserDTO user = UserHolder.getUser();
        ShopFollow record = shopFollowMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ShopFollow>()
                        .eq(ShopFollow::getUserId, user.getId())
                        .eq(ShopFollow::getShopId, shopId));
        if (record != null) {
            shopFollowMapper.deleteById(record.getId());
            return Result.ok(false);
        }
        ShopFollow follow = new ShopFollow();
        follow.setUserId(user.getId());
        follow.setShopId(shopId);
        follow.setCreateTime(LocalDateTime.now());
        shopFollowMapper.insert(follow);
        return Result.ok(true);
    }

    /**
     * 查询当前用户是否关注了该商户
     */
    @GetMapping("/shop/{shopId}/followed")
    public Result isShopFollowed(@PathVariable("shopId") Long shopId) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.ok(false);
        }
        Long count = shopFollowMapper.selectCount(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ShopFollow>()
                        .eq(ShopFollow::getUserId, user.getId())
                        .eq(ShopFollow::getShopId, shopId));
        return Result.ok(count != null && count > 0);
    }

    // ==================== 探店笔记的编辑与删除（新增功能） ====================

    /**
     * 修改自己的探店笔记（标题/内容）
     */
    @PostMapping("/blog/{id}")
    public Result updateBlog(@PathVariable("id") Long id, @RequestBody Map<String, String> body) {
        UserDTO user = UserHolder.getUser();
        Blog blog = blogService.getById(id);
        if (blog == null) {
            return Result.fail("笔记不存在或已被删除");
        }
        if (!user.getId().equals(blog.getUserId())) {
            return Result.fail("只能修改自己发布的笔记");
        }
        String title = body == null ? null : body.get("title");
        String content = body == null ? null : body.get("content");
        if (title == null || title.trim().isEmpty()) {
            return Result.fail("标题不能为空");
        }
        blogService.lambdaUpdate()
                .eq(Blog::getId, id)
                .set(Blog::getTitle, title.trim())
                .set(content != null, Blog::getContent, content)
                .set(Blog::getUpdateTime, LocalDateTime.now())
                .update();
        return Result.ok();
    }

    /**
     * 删除自己的探店笔记
     */
    @DeleteMapping("/blog/{id}")
    public Result deleteBlog(@PathVariable("id") Long id) {
        UserDTO user = UserHolder.getUser();
        Blog blog = blogService.getById(id);
        if (blog == null) {
            return Result.ok();
        }
        if (!user.getId().equals(blog.getUserId())) {
            return Result.fail("只能删除自己发布的笔记");
        }
        blogService.removeById(id);
        return Result.ok();
    }
}
