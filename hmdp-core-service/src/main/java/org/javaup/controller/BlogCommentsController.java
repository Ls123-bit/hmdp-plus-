package org.javaup.controller;

import jakarta.annotation.Resource;
import org.javaup.dto.Result;
import org.javaup.dto.UserDTO;
import org.javaup.entity.Blog;
import org.javaup.entity.BlogComments;
import org.javaup.entity.User;
import org.javaup.service.IBlogCommentsService;
import org.javaup.service.IBlogService;
import org.javaup.service.IUserService;
import org.javaup.utils.UserHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * @program: hmdp-plus
 * @description: 博客评论api（新增功能，原控制器为空。查询与发表均需登录：
 * /blog-comments/** 未加入登录白名单，身份由 RefreshTokenInterceptor 注入）
 * @author: hmdp-plus
 **/
@RestController
@RequestMapping("/blog-comments")
public class BlogCommentsController {

    @Resource
    private IBlogCommentsService blogCommentsService;

    @Resource
    private IBlogService blogService;

    @Resource
    private IUserService userService;

    /**
     * 查询某篇笔记的评论列表（按时间倒序，带评论人昵称与头像）
     */
    @GetMapping("/of/blog/{blogId}")
    public Result commentsOfBlog(@PathVariable("blogId") Long blogId) {
        List<BlogComments> comments = blogCommentsService.lambdaQuery()
                .eq(BlogComments::getBlogId, blogId)
                .orderByDesc(BlogComments::getCreateTime)
                .list();
        if (comments == null || comments.isEmpty()) {
            return Result.ok(new ArrayList<>());
        }
        List<Long> userIds = comments.stream().map(BlogComments::getUserId).distinct().toList();
        Map<Long, User> users = userService.listByIds(userIds).stream()
                .collect(Collectors.toMap(User::getId, Function.identity(), (a, b) -> a));
        List<Map<String, Object>> result = comments.stream().map(c -> {
            User u = users.get(c.getUserId());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", c.getId());
            item.put("blogId", c.getBlogId());
            item.put("userId", c.getUserId());
            item.put("content", c.getContent());
            item.put("liked", c.getLiked());
            item.put("createTime", c.getCreateTime());
            item.put("name", u == null ? "点评用户" : u.getNickName());
            item.put("icon", u == null ? null : u.getIcon());
            return item;
        }).collect(Collectors.toList());
        return Result.ok(result);
    }

    /**
     * 发表评论（需登录），并同步累加笔记的评论数
     */
    @PostMapping
    public Result addComment(@RequestBody Map<String, Object> body) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("请先登录");
        }
        Long blogId = Long.valueOf(String.valueOf(body.get("blogId")));
        String content = body.get("content") == null ? null : body.get("content").toString().trim();
        if (content == null || content.isEmpty()) {
            return Result.fail("评论内容不能为空");
        }
        if (content.length() > 500) {
            return Result.fail("评论不能超过500字");
        }
        Blog blog = blogService.getById(blogId);
        if (blog == null) {
            return Result.fail("笔记不存在或已被删除");
        }
        BlogComments comment = new BlogComments();
        comment.setUserId(user.getId());
        comment.setBlogId(blogId);
        comment.setParentId(0L);
        comment.setAnswerId(0L);
        comment.setContent(content);
        comment.setLiked(0);
        comment.setStatus(true);
        comment.setCreateTime(LocalDateTime.now());
        comment.setUpdateTime(LocalDateTime.now());
        blogCommentsService.save(comment);
        // 同步累加笔记评论数（广播表，自动写到所有库）
        blogService.lambdaUpdate()
                .eq(Blog::getId, blogId)
                .setSql("comments = comments + 1")
                .update();
        return Result.ok(comment.getId());
    }
}
