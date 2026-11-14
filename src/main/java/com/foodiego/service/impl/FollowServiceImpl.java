package com.foodiego.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.foodiego.dto.Result;
import com.foodiego.dto.UserDTO;
import com.foodiego.entity.Follow;
import com.foodiego.mapper.FollowMapper;
import com.foodiego.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.foodiego.service.IUserService;
import com.foodiego.utils.RedisConstants;
import com.foodiego.utils.UserHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author FoodieGo Team
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private  FollowMapper followMapper;
    @Autowired
    private IUserService userService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result follow(Long followUserId, Boolean isFollow) {
        // 1.获取当前用户
        Long userId = UserHolder.getUser().getId();
        if (followUserId == null || followUserId <= 0 || followUserId.equals(userId)) {
            return Result.fail("关注用户无效");
        }

        // 2.判断是否关注
        if (isFollow == null) {
            return Result.fail("关注状态无效");
        }
        if (Boolean.TRUE.equals(isFollow)) {
            // 3.关注，新增数据
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(followUserId);

            try {
                boolean isSave = save(follow);
                if (isSave) {
                    // 写入Redis缓存
                    stringRedisTemplate.opsForSet().add(RedisConstants.FOLLOW_KEY + userId, followUserId.toString());
                }
            } catch (DuplicateKeyException ignored) {
                // Idempotent follow: the unique index is the authoritative concurrency guard.
                // 写入Redis缓存
                stringRedisTemplate.opsForSet().add(RedisConstants.FOLLOW_KEY + userId, followUserId.toString());
            }
        }else {
            // 4.取关，删除数据 delete from tb_follow where user_id = ? and follow_user_id = ?
            boolean isDelete = followMapper.delete(userId, followUserId);
            if (isDelete) {
                // 删除Redis缓存
                stringRedisTemplate.opsForSet().remove(RedisConstants.FOLLOW_KEY + userId, followUserId.toString());
            }
        }
        // 5.返回结果
        return Result.ok();
    }

    @Override
    public Result isFollow(Long followUserId) {
        // 1.获取当前用户
        Long userId = UserHolder.getUser().getId();

        // 2.查询是否关注
        Follow follow = query().eq("user_id", userId).eq("follow_user_id", followUserId).one();

        // 3.返回结果
        return Result.ok(follow != null);
    }

    @Override
    public Result common(Long id) {
        // 1.获取当前用户
        Long userId = UserHolder.getUser().getId();

        // 2.求交集
        Set<String> intersect = stringRedisTemplate.opsForSet()
                .intersect(RedisConstants.FOLLOW_KEY + userId, RedisConstants.FOLLOW_KEY + id);

        // 3.判断结果是否为空
        if (intersect == null || intersect.isEmpty()) {
            return Result.ok();
        }

        // 4.转换成long类型
        List<Long> ids = intersect.stream().map(Long::valueOf).collect(Collectors.toList());

        // 5.查询用户信息
        List<UserDTO> users = userService.listByIds(ids)
                .stream().map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());

        return Result.ok(users);
    }
}
