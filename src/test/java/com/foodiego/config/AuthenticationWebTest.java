package com.foodiego.config;

import com.foodiego.utils.UserHolder;
import com.foodiego.dto.UserDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.util.Collections;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringJUnitConfig(AuthenticationWebTest.Config.class)
@WebAppConfiguration
@TestPropertySource(properties = "foodiego.security.admin-user-ids=1")
class AuthenticationWebTest {
    @Configuration
    @EnableWebMvc
    @Import(SpringMVC.class)
    static class Config {
        @Bean StringRedisTemplate stringRedisTemplate() { return mock(StringRedisTemplate.class); }
        @Bean Probe probe() { return new Probe(); }
    }

    @RestController
    static class Probe {
        @GetMapping({"/", "/shop/1", "/shop-type/list", "/voucher/list/1", "/actuator/health"})
        String browse() { return "public"; }
        @PostMapping({"/shop", "/voucher/seckill", "/upload/blog"})
        String write() { return "written"; }
        @PostMapping("/voucher-order/seckill/1")
        String order() { return UserHolder.getUser().getId().toString(); }
        @GetMapping("/user/me")
        String me() { return UserHolder.getUser().getId().toString(); }
        @GetMapping("/upload/blog/delete")
        String deleteImage() { return "deleted"; }
        @PostMapping("/voucher-order/fail")
        String fail() { throw new IllegalStateException("test failure"); }
        @ExceptionHandler(IllegalStateException.class)
        @ResponseStatus(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR)
        void error() { }
    }

    @Autowired WebApplicationContext context;
    @Autowired StringRedisTemplate redis;
    MockMvc mvc;

    @BeforeEach
    void setup() {
        reset(redis);
        HashOperations<String, Object, Object> hash = mock(HashOperations.class);
        when(redis.opsForHash()).thenReturn(hash);
        when(hash.entries(anyString())).thenAnswer(call -> {
            String key = call.getArgument(0);
            if (key.equals("login:token:admin")) return Collections.singletonMap("id", "1");
            if (key.equals("login:token:user")) return Collections.singletonMap("id", "2");
            return Collections.emptyMap();
        });
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        UserHolder.removeUser();
    }

    @AfterEach void cleanup() { UserHolder.removeUser(); }

    @Test void publicBrowsingAndHealthRemainAccessible() throws Exception {
        for (String path : new String[]{"/", "/shop/1", "/shop-type/list", "/voucher/list/1", "/actuator/health"}) {
            mvc.perform(get(path)).andExpect(status().isOk());
        }
    }

    @Test void ordersRejectMissingAndExpiredTokens() throws Exception {
        mvc.perform(post("/voucher-order/seckill/1")).andExpect(status().isUnauthorized());
        mvc.perform(post("/voucher-order/seckill/1").header("Authorization", "expired"))
                .andExpect(status().isUnauthorized());
    }

    @Test void orderContextIsClearedBeforeNextRequestOnSameThread() throws Exception {
        mvc.perform(post("/voucher-order/seckill/1").header("Authorization", "user"))
                .andExpect(status().isOk()).andExpect(content().string("2"));
        assertNull(UserHolder.getUser());
        mvc.perform(post("/voucher-order/seckill/1")).andExpect(status().isUnauthorized());
        UserDTO stale = new UserDTO(); stale.setId(1L); UserHolder.saveUser(stale);
        mvc.perform(get("/user/me")).andExpect(status().isUnauthorized());
        assertNull(UserHolder.getUser());
    }

    @Test void contextIsClearedAfterHandlerFailureAndForbiddenWrite() throws Exception {
        mvc.perform(post("/voucher-order/fail").header("Authorization", "user"))
                .andExpect(status().isInternalServerError());
        assertNull(UserHolder.getUser());
        mvc.perform(post("/shop").header("Authorization", "user")).andExpect(status().isForbidden());
        assertNull(UserHolder.getUser());
    }

    @Test void managementWritesRequireConfiguredAdministrator() throws Exception {
        for (String path : new String[]{"/shop", "/voucher/seckill"}) {
            mvc.perform(post(path)).andExpect(status().isUnauthorized());
            mvc.perform(post(path).header("Authorization", "user")).andExpect(status().isForbidden());
            mvc.perform(post(path).header("Authorization", "admin")).andExpect(status().isOk());
        }
        mvc.perform(post("/upload/blog")).andExpect(status().isUnauthorized());
        mvc.perform(post("/upload/blog").header("Authorization", "user")).andExpect(status().isOk());
        mvc.perform(get("/upload/blog/delete")).andExpect(status().isUnauthorized());
        mvc.perform(get("/upload/blog/delete").header("Authorization", "user")).andExpect(status().isForbidden());
        mvc.perform(get("/upload/blog/delete/").header("Authorization", "user")).andExpect(status().isForbidden());
        mvc.perform(get("/upload/blog/delete;v=1").header("Authorization", "user")).andExpect(status().isForbidden());
        mvc.perform(get("/upload/blog/delete").header("Authorization", "admin")).andExpect(status().isOk());
    }
}
