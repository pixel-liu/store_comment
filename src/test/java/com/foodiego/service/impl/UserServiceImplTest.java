package com.foodiego.service.impl;

import com.foodiego.dto.LoginFormDTO;
import com.foodiego.dto.Result;
import com.foodiego.dto.UserDTO;
import com.foodiego.entity.User;
import com.foodiego.entity.Shop;
import com.foodiego.mapper.MealMapper;
import com.foodiego.mapper.UserMapper;
import com.foodiego.utils.AdministratorAccess;
import com.foodiego.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.foodiego.utils.RedisConstants.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link UserServiceImpl} — code verification, login flow, sign-in.
 */
@SpringBootTest(classes = {UserServiceImpl.class, UserMapper.class})
@TestPropertySource(properties = "foodiego.security.expose-login-code=true")
@DisplayName("UserServiceImpl Unit Tests")
class UserServiceImplTest {

    @SpyBean
    private UserServiceImpl userService;

    @MockBean
    private StringRedisTemplate stringRedisTemplate;

    @MockBean
    private UserMapper userMapper;

    @MockBean
    private MealMapper meals;

    @MockBean
    private AdministratorAccess administrators;

    private ValueOperations<String, String> valueOps;
    private HashOperations<String, Object, Object> hashOps;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        valueOps = mock(ValueOperations.class);
        hashOps = mock(HashOperations.class);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
        when(stringRedisTemplate.opsForHash()).thenReturn(hashOps);
    }

    @AfterEach
    void tearDown() {
        UserHolder.removeUser();
    }

    // ────────────────────── sendCode() tests ──────────────────────

    @Test
    @DisplayName("sendCode with invalid phone → returns error")
    void sendCode_InvalidPhone_ReturnsError() {
        Result result = userService.sendCode("123", null);

        assertFalse(result.getSuccess());
        assertEquals("手机号格式错误！", result.getErrorMsg());
        verify(valueOps, never()).set(anyString(), anyString(), anyLong(), any());
    }

    @Test
    @DisplayName("sendCode with valid phone → saves 6-digit code to Redis with TTL")
    void sendCode_ValidPhone_SavesCodeToRedis() {
        when(valueOps.setIfAbsent(eq(LOGIN_CODE_SEND_LIMIT_KEY + "13686869696"), eq("1"),
                eq(LOGIN_CODE_SEND_LIMIT_TTL), eq(TimeUnit.SECONDS))).thenReturn(true);
        Result result = userService.sendCode("13686869696", null);

        assertTrue(result.getSuccess());
        verify(valueOps).set(eq(LOGIN_CODE_KEY + "13686869696"), anyString());
        verify(stringRedisTemplate).expire(eq(LOGIN_CODE_KEY + "13686869696"),
                eq(LOGIN_CODE_TTL), eq(TimeUnit.MINUTES));
        assertNotNull(result.getData());
        assertTrue(result.getData().toString().length() == 6);
    }

    @Test
    @DisplayName("sendCode rejects a second request inside the server-side cooldown")
    void sendCode_RateLimited_ReturnsError() {
        when(valueOps.setIfAbsent(eq(LOGIN_CODE_SEND_LIMIT_KEY + "13686869696"), eq("1"),
                eq(LOGIN_CODE_SEND_LIMIT_TTL), eq(TimeUnit.SECONDS))).thenReturn(false);

        Result result = userService.sendCode("13686869696", null);

        assertFalse(result.getSuccess());
        assertEquals("请求过于频繁，请稍后再试", result.getErrorMsg());
        verify(valueOps, never()).set(eq(LOGIN_CODE_KEY + "13686869696"), anyString());
    }

    // ────────────────────── login() tests ──────────────────────

    @Test
    @DisplayName("Login with invalid phone → returns error")
    void login_InvalidPhone_ReturnsError() {
        LoginFormDTO form = new LoginFormDTO();
        form.setPhone("abc");
        form.setCode("123456");

        Result result = userService.login(form, null);
        assertFalse(result.getSuccess());
        assertEquals("手机号格式错误！", result.getErrorMsg());
    }

    @Test
    @DisplayName("Login with wrong verification code → returns error")
    void login_WrongCode_ReturnsError() {
        LoginFormDTO form = new LoginFormDTO();
        form.setPhone("13686869696");
        form.setCode("999999");

        when(valueOps.get(LOGIN_CODE_KEY + "13686869696")).thenReturn("123456");

        Result result = userService.login(form, null);
        assertFalse(result.getSuccess());
        assertEquals("验证码错误！", result.getErrorMsg());
    }

    @Test
    void windowAccountCanSwitchToBackofficeWithTheSameCode() {
        LoginFormDTO form = validLoginForm("student");
        when(userMapper.findLoginUserByPhone(form.getPhone())).thenReturn(new User().setId(7L).setNickName("窗口人员"));
        when(meals.listStaffShops(7L)).thenReturn(List.of(new Shop().setId(3L)));

        assertFalse(userService.login(form, null).getSuccess());
        verify(stringRedisTemplate, never()).delete(anyString());
        form.setPortal("backoffice");
        assertTrue(userService.login(form, null).getSuccess());
        verify(hashOps).putAll(startsWith(LOGIN_USER_KEY), anyMap());
    }

    @Test
    void unassignedAdministratorCanSignInToBackoffice() {
        LoginFormDTO form = validLoginForm("backoffice");
        when(userMapper.findLoginUserByPhone(form.getPhone())).thenReturn(new User().setId(1L).setNickName("管理员"));
        when(administrators.allows(1L)).thenReturn(true);

        assertTrue(userService.login(form, null).getSuccess());
        verifyNoInteractions(meals);
    }

    @Test
    void backofficeLoginDoesNotCreateAccountsOrAcceptStudents() {
        LoginFormDTO form = validLoginForm("backoffice");
        when(userMapper.findLoginUserByPhone(form.getPhone())).thenReturn(null, new User().setId(9L).setNickName("学生"));

        assertFalse(userService.login(form, null).getSuccess());
        assertFalse(userService.login(form, null).getSuccess());
        verify(userMapper, never()).insert(any(User.class));
        verify(stringRedisTemplate, never()).delete(anyString());
        verifyNoInteractions(hashOps);
    }

    private LoginFormDTO validLoginForm(String portal) {
        String phone = "13686869696";
        String codeKey = LOGIN_CODE_KEY + phone;
        when(valueOps.get(codeKey)).thenReturn("123456");
        when(stringRedisTemplate.delete(codeKey)).thenReturn(true);
        return LoginFormDTO.builder().phone(phone).code("123456").portal(portal).build();
    }

    // ────────────────────── sign() tests ──────────────────────

    @Test
    @DisplayName("sign() sets BitMap bit for current day of month")
    void sign_SetsBitInRedis() {
        UserDTO testUser = new UserDTO();
        testUser.setId(1L);
        testUser.setNickName("signer");
        UserHolder.saveUser(testUser);

        Result result = userService.sign();

        assertTrue(result.getSuccess());
        verify(valueOps).setBit(contains("sign:1:"), anyLong(), eq(true));
    }

    // ────────────────────── signCount() tests ──────────────────────

    @Test
    @DisplayName("signCount with no sign data → returns 0")
    void signCount_NoSigns_ReturnsZero() {
        UserDTO testUser = new UserDTO();
        testUser.setId(1L);
        UserHolder.saveUser(testUser);

        when(valueOps.bitField(anyString(), any())).thenReturn(null);

        Result result = userService.signCount();
        assertTrue(result.getSuccess());
        assertEquals(0, result.getData());
    }

    // ────────────────────── logout() tests ──────────────────────

    @Test
    @DisplayName("logout deletes token from Redis")
    void logout_DeletesToken() {
        javax.servlet.http.HttpServletRequest request = mock(javax.servlet.http.HttpServletRequest.class);
        when(request.getHeader("Authorization")).thenReturn("test-token-uuid");

        Result result = userService.logout(request);

        assertTrue(result.getSuccess());
        verify(stringRedisTemplate).delete(LOGIN_USER_KEY + "test-token-uuid");
    }
}
