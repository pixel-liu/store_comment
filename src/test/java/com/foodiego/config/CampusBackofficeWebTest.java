package com.foodiego.config;

import com.foodiego.controller.CampusBackofficeController;
import com.foodiego.entity.Shop;
import com.foodiego.entity.User;
import com.foodiego.mapper.MealMapper;
import com.foodiego.mapper.ShopMapper;
import com.foodiego.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringJUnitConfig(CampusBackofficeWebTest.Config.class)
@WebAppConfiguration
@TestPropertySource(properties = "foodiego.security.admin-user-ids=1")
class CampusBackofficeWebTest {
    private static final String WINDOWS = "/backoffice/admin/windows";

    @Configuration
    @EnableWebMvc
    @Import({SpringMVC.class, CampusBackofficeController.class})
    static class Config {
        @Bean StringRedisTemplate redis() { return mock(StringRedisTemplate.class); }
        @Bean MealMapper meals() { return mock(MealMapper.class); }
        @Bean ShopMapper shops() { return mock(ShopMapper.class); }
        @Bean UserMapper users() { return mock(UserMapper.class); }
    }

    @Autowired WebApplicationContext context;
    @Autowired StringRedisTemplate redis;
    @Autowired MealMapper meals;
    @Autowired ShopMapper shops;
    @Autowired UserMapper users;
    MockMvc mvc;

    @BeforeEach
    void setup() {
        reset(redis, meals, shops, users);
        @SuppressWarnings("unchecked")
        HashOperations<String, Object, Object> hash = mock(HashOperations.class);
        when(redis.opsForHash()).thenReturn(hash);
        when(hash.entries("login:token:admin")).thenReturn(Map.of("id", "1", "nickName", "管理员"));
        when(hash.entries("login:token:staff")).thenReturn(Map.of("id", "2", "nickName", "窗口人员"));
        when(hash.entries("login:token:student")).thenReturn(Map.of("id", "3", "nickName", "学生"));
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void accessReportsRolesIndependently() throws Exception {
        when(meals.listStaffShops(2)).thenReturn(List.of(new Shop().setId(10L).setName("一食堂")));
        mvc.perform(get("/backoffice/access")).andExpect(status().isUnauthorized());
        mvc.perform(get("/backoffice/access").header("Authorization", "admin"))
                .andExpect(jsonPath("$.data.administrator").value(true))
                .andExpect(jsonPath("$.data.windows").isEmpty());
        mvc.perform(get("/backoffice/access").header("Authorization", "staff"))
                .andExpect(jsonPath("$.data.administrator").value(false))
                .andExpect(jsonPath("$.data.windows[0].id").value("10"));
        mvc.perform(get("/backoffice/access").header("Authorization", "student"))
                .andExpect(jsonPath("$.data.administrator").value(false))
                .andExpect(jsonPath("$.data.windows").isEmpty());
    }

    @Test
    void managementRoutesRejectGuestsAndWindowStaff() throws Exception {
        mvc.perform(get(WINDOWS)).andExpect(status().isUnauthorized());
        mvc.perform(get(WINDOWS + "/10/staff/").header("Authorization", "staff")).andExpect(status().isForbidden());
        mvc.perform(post(WINDOWS).header("Authorization", "staff")
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
        mvc.perform(delete(WINDOWS + "/10/staff/2").header("Authorization", "student")).andExpect(status().isForbidden());
        verifyNoInteractions(meals, shops, users);
    }

    @Test
    void administratorCanCreateWindowAndManageAssignments() throws Exception {
        Shop shop = new Shop().setId(10L).setName("一食堂").setAddress("二楼");
        when(shops.insert(any(Shop.class))).thenAnswer(call -> {
            call.<Shop>getArgument(0).setId(shop.getId());
            return 1;
        });
        when(shops.selectById(10L)).thenReturn(shop);
        when(shops.listPickupWindows()).thenReturn(List.of(shop));
        when(users.findIdByPhone("13800000000")).thenReturn(2L);
        when(meals.listWindowStaff(10)).thenReturn(List.of(new User().setId(2L).setNickName("窗口人员")));
        mvc.perform(post(WINDOWS).header("Authorization", "admin").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"一食堂\",\"address\":\"二楼\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value("10"));
        mvc.perform(get(WINDOWS).header("Authorization", "admin"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].address").value("二楼"));
        mvc.perform(post(WINDOWS + "/10/staff").header("Authorization", "admin").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"13800000000\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        verify(meals).bindWindowStaff(10, 2);
        mvc.perform(get(WINDOWS + "/10/staff").header("Authorization", "admin"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].id").value("2"));
        mvc.perform(delete(WINDOWS + "/10/staff/2").header("Authorization", "admin"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        verify(meals).unbindWindowStaff(10, 2);
    }
}
