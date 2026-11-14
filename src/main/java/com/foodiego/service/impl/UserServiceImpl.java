package com.foodiego.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.foodiego.dto.LoginFormDTO;
import com.foodiego.dto.Result;
import com.foodiego.dto.UserDTO;
import com.foodiego.entity.User;
import com.foodiego.mapper.MealMapper;
import com.foodiego.mapper.UserMapper;
import com.foodiego.service.IUserService;
import com.foodiego.utils.AdministratorAccess;
import com.foodiego.utils.RedisConstants;
import com.foodiego.utils.RegexUtils;
import com.foodiego.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.cache.CacheProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.foodiego.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author FoodieGo Team
 * @since 2021-12-22
 */
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private AdministratorAccess administrators;

    @Resource
    private MealMapper meals;

    /** Only enabled explicitly for local development; production must use an SMS provider. */
    @Value("${foodiego.security.expose-login-code:false}")
    private boolean exposeLoginCode;
    /**
     * 发送手机验证码
     */
    @Override
    public Result sendCode(String phone, HttpSession session) {
        // 校验手机号是否合法
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式错误！");
        }
        Boolean allowed = stringRedisTemplate.opsForValue().setIfAbsent(
                RedisConstants.LOGIN_CODE_SEND_LIMIT_KEY + phone,
                "1",
                RedisConstants.LOGIN_CODE_SEND_LIMIT_TTL,
                TimeUnit.SECONDS);
        if (!Boolean.TRUE.equals(allowed)) {
            return Result.fail("请求过于频繁，请稍后再试");
        }

        // 生成验证码
        String code = RandomUtil.randomNumbers(6);

        // 保存验证码到redis
        stringRedisTemplate.opsForValue().set(RedisConstants.LOGIN_CODE_KEY + phone, code);
        stringRedisTemplate.expire(RedisConstants.LOGIN_CODE_KEY + phone, RedisConstants.LOGIN_CODE_TTL, TimeUnit.MINUTES);

        // 生产环境在此调用短信服务；验证码绝不能进入日志或 HTTP 响应。
        if (exposeLoginCode) {
            log.debug("本地开发验证码已生成，phone={}", phone);
            return Result.ok(code);
        }
        return Result.ok();

    }

    /**
     * 登录功能
     * @param loginForm 登录参数，包含手机号、验证码；或者手机号、密码
     */
    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        // 校验手机号是否合法
        if (RegexUtils.isPhoneInvalid(loginForm.getPhone())){
            return Result.fail("手机号格式错误！");
        }

        String portal = loginForm.getPortal();
        boolean studentPortal = "student".equals(portal);
        boolean backofficePortal = "backoffice".equals(portal);
        if (portal != null && !studentPortal && !backofficePortal) {
            return Result.fail("请选择学生登录或商家／管理员登录");
        }

        String phone = loginForm.getPhone();
        String codeKey = RedisConstants.LOGIN_CODE_KEY + phone;
        String cacheCode = stringRedisTemplate.opsForValue().get(codeKey);
        String code = loginForm.getCode();
        if (cacheCode == null || !cacheCode.equals(code)) {
            return Result.fail("验证码错误！");
        }

        User user = baseMapper.findLoginUserByPhone(phone);
        if (backofficePortal && !hasBackofficeAccess(user)) {
            return Result.fail("此账号没有后台权限。学生请切换到学生登录，窗口人员请联系管理员绑定窗口。");
        }
        if (studentPortal && hasBackofficeAccess(user)) {
            return Result.fail("此账号有运营权限，请切换到「商家 / 管理员登录」。");
        }
        // Keep the code usable after a portal mismatch, and issue a token only once.
        if (!Boolean.TRUE.equals(stringRedisTemplate.delete(codeKey))) {
            return Result.fail("验证码错误！");
        }
        if (user == null) {
            try {
                user = createNewUser(phone);
            } catch (DuplicateKeyException ignored) {
                // A concurrent login may create the same phone first; read the winner.
                user = baseMapper.findLoginUserByPhone(phone);
                if (user == null) {
                    throw ignored;
                }
                if (studentPortal && hasBackofficeAccess(user)) {
                    return Result.fail("此账号有运营权限，请切换到「商家 / 管理员登录」。");
                }
            }
        }

        // 保存UserDTO信息到redis中
        // 随机生成token令牌 UUID
        String token = UUID.randomUUID().toString(true);

        // 将user对象转成hashMap
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO);
        // 将map中的value转成string
        Map<String, String> stringUserMap = userMap.entrySet().stream()
                .filter(entry -> entry.getValue() != null)
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().toString()));


        // 存储到Redis中
        String tokenKey = RedisConstants.LOGIN_USER_KEY + token;
        stringRedisTemplate.opsForHash().putAll(tokenKey, stringUserMap);

        // 设置过期时间
        stringRedisTemplate.expire(tokenKey, RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);

        // 返回结果
        return Result.ok(token);
    }

    private boolean hasBackofficeAccess(User user) {
        return user != null && (administrators.allows(user.getId()) || !meals.listStaffShops(user.getId()).isEmpty());
    }

    @Override
    public Result logout(HttpServletRequest request) {
        String token = request.getHeader("Authorization");
        stringRedisTemplate.delete(RedisConstants.LOGIN_USER_KEY + token);
        return Result.ok("登出成功");
    }

    @Override
    public Result sign() {
        // 获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        // 获取当前日期
        LocalDateTime now = LocalDateTime.now();
        String date = now.format(DateTimeFormatter.ofPattern(":yyyy/MM"));
        // 拼接key
        String key = RedisConstants.USER_SIGN_KEY + userId + date;
        // 获取当前是这个月第几天
        int dayOfMonth = now.getDayOfMonth(); // 1 - 31
        // 写入Redis中 命令：SETBIT KEY OFFSET VALUE
        stringRedisTemplate.opsForValue().setBit(key, dayOfMonth - 1, true);

        return Result.ok();
    }

    @Override
    public Result signCount() {
        // 获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        // 获取当前日期
        LocalDateTime now = LocalDateTime.now();
        String date = now.format(DateTimeFormatter.ofPattern(":yyyy/MM"));
        // 拼接key
        String key = RedisConstants.USER_SIGN_KEY + userId + date;
        // 获取当前是这个月第几天
        int dayOfMonth = now.getDayOfMonth(); // 1 - 31
        // 获取这个月到今天为止的签到数据 十进制
        List<Long> list = stringRedisTemplate.opsForValue()
                .bitField(key,
                        BitFieldSubCommands.create().get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0)
                );
        if (list == null || list.isEmpty()) {
            return Result.ok(0);
        }
        Long num = list.get(0);
        if (num == null || num == 0) {
            return Result.ok(0);
        }
        // 遍历循环 num与1进行位运算，判断这个用户今天是否签到
        int count = 0;
        while(true) {
            if ((num & 1) == 0) {
                break;
            }else {
                count++;
            }
            num >>>= 1; // 逻辑右移
        }
        return Result.ok(count);
    }

    // 创建新用户
    private User createNewUser(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX+ RandomUtil.randomString(10));
        save(user);
        return user;
    }
}
