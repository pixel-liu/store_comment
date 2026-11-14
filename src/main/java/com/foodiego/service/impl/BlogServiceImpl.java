package com.foodiego.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.foodiego.dto.Result;
import com.foodiego.dto.ScrollDTO;
import com.foodiego.dto.UserDTO;
import com.foodiego.entity.Blog;
import com.foodiego.entity.User;
import com.foodiego.mapper.BlogMapper;
import com.foodiego.mapper.BlogLikeMapper;
import com.foodiego.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.foodiego.service.IFollowService;
import com.foodiego.service.IUserService;
import com.foodiego.utils.RedisConstants;
import com.foodiego.utils.SystemConstants;
import com.foodiego.utils.UserHolder;
import jodd.util.StringUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author FoodieGo Team
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private IUserService userService;
    @Autowired
    private IFollowService followService;
    @Resource
    private BlogLikeMapper blogLikeMapper;

    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach((blog -> {
            queryBlogUser(blog);
            isBlogLiked(blog);
        }));
        return Result.ok(records);
    }

    @Override
    public Result queryBlogById(Long id) {
        // 1.查询blog
        Blog blog = getById(id);

        // 2.判断blog是否为空
        if (blog == null) {
            return Result.fail("笔记不存在！");
        }
        // 3.查询blog有关的用户
        queryBlogUser(blog);

        // 4.查询blog是否被点赞
        isBlogLiked(blog);

        return Result.ok(blog);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result likeBlog(Long id) {
        // 获取登录用户
        Long userId = UserHolder.getUser().getId();
        if (id == null || id <= 0 || getById(id) == null) {
            return Result.fail("笔记不存在！");
        }

        // INSERT IGNORE plus a unique index makes concurrent likes deterministic.
        boolean liked = blogLikeMapper.insertIgnore(id, userId) == 1;
        if (liked) {
            if (!update().setSql("liked = COALESCE(liked, 0) + 1").eq("id", id).update()) {
                throw new IllegalStateException("点赞计数更新失败");
            }
            stringRedisTemplate.opsForZSet().add(RedisConstants.BLOG_LIKED_KEY + id,
                    userId.toString(), System.currentTimeMillis());
        } else {
            int removed = blogLikeMapper.delete(id, userId);
            if (removed == 1) {
                if (!update().setSql("liked = GREATEST(COALESCE(liked, 0) - 1, 0)").eq("id", id).update()) {
                    throw new IllegalStateException("点赞计数更新失败");
                }
                stringRedisTemplate.opsForZSet().remove(RedisConstants.BLOG_LIKED_KEY + id, userId.toString());
            }
        }
        return Result.ok();
    }

    @Override
    public Result queryBlogLikes(Long id) {
        String key = RedisConstants.BLOG_LIKED_KEY + id;

        // 获取前5个点赞用户
        Set<String> range = stringRedisTemplate.opsForZSet().range(key, 0, 4);

        // 判断set是否为空
        if (range == null || range.isEmpty()) {
            return Result.ok();
        }

        // 获取用户id
        List<Long> ids = range.stream().map(Long::valueOf).collect(Collectors.toList());

        // 将ids列表拼接成字符串
        String idStr = StringUtil.join(ids, ",");

        // 查询 用户
        List<UserDTO> userDTOList = userService.query()
                .in("id", ids).last("order by field (id, " + idStr + " )")
                .list().stream().map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(userDTOList);
    }

    @Override
    public Result saveBlog(Blog blog) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        // 保存探店博文
        boolean isSaveBlog = save(blog);
        // 判断博文是否保存成功
        if (!isSaveBlog) {
            return Result.fail("发布笔记失败！");
        }
        // 将博文推给关注的粉丝
        Long followedUserId = UserHolder.getUser().getId();
        // 获取所有粉丝
        followService.query().eq("follow_user_id", followedUserId).list().forEach(follow -> {
            // 获取粉丝id
            Long userId = follow.getUserId();
            // 推送
            stringRedisTemplate.opsForZSet()
                    .add(RedisConstants.FEED_KEY + userId, blog.getId().toString(), System.currentTimeMillis());
        });
        // 返回id
        return Result.ok(blog.getId());
    }

    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        // 获取当前用户
        Long userId = UserHolder.getUser().getId();
        String key = RedisConstants.FEED_KEY + userId;
        // 查询收件箱
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key, 0, max, offset, 2);
        if (typedTuples == null || typedTuples.isEmpty()) {
            return Result.ok();
        }

        // 获取blog和最小时间以及offset
        ArrayList<Long> ids = new ArrayList<>(typedTuples.size());

        long minTime = 0;
        int os = 1;
        for (ZSetOperations.TypedTuple<String> typedTuple : typedTuples) {

            ids.add(Long.valueOf(typedTuple.getValue()));

            long time = typedTuple.getScore().longValue();
            // 获取offset
            if (time == minTime) {
                os++;
            }else {
                minTime = time;
                os = 1;
            }
        }
        if (ids.isEmpty()) {
            return Result.ok();
        }

        String idStr = StrUtil.join(",", ids);
        // 根据id查询blog
        List<Blog> blogs = query().in("id", ids).last("order by field (id, " + idStr + " )").list();
        // 查询博文的用户和点赞情况
        for (Blog blog : blogs) {
            isBlogLiked(blog);
            queryBlogUser(blog);
        }

        // 封装到ScrollDTO
        ScrollDTO scrollDTO = new ScrollDTO();
        scrollDTO.setList(blogs);
        scrollDTO.setOffset(os);
        scrollDTO.setMinTime(minTime);

        return Result.ok(scrollDTO);
    }

    private void isBlogLiked(Blog blog) {
        // 获取登录用户
        UserDTO userDTO = UserHolder.getUser();

        // 判断是否为空
        if (userDTO == null) {
            return;
        }
        Long userId = userDTO.getId();
        // 判断用户是否已经点赞
        Double isLiked = stringRedisTemplate
                .opsForZSet()
                .score(RedisConstants.BLOG_LIKED_KEY + blog.getId(), userId.toString());

        // 设置blog是否点赞
        blog.setIsLike(BooleanUtil.isTrue(isLiked != null));
    }

    private void queryBlogUser(Blog blog) {

        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }
}
