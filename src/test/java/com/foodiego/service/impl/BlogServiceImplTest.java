package com.foodiego.service.impl;

import com.foodiego.dto.Result;
import com.foodiego.dto.UserDTO;
import com.foodiego.entity.Blog;
import com.foodiego.entity.User;
import com.foodiego.mapper.BlogMapper;
import com.foodiego.mapper.BlogLikeMapper;
import com.foodiego.service.IFollowService;
import com.foodiego.service.IUserService;
import com.foodiego.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.util.HashSet;
import java.util.Set;

import static com.foodiego.utils.RedisConstants.BLOG_LIKED_KEY;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link BlogServiceImpl} — blog query, like status.
 * <p>
 * Like/unlike toggle and save-feed-push logic are tested via integration tests
 * due to MyBatis-Plus query/update chain complexity.
 */
@SpringBootTest(classes = {BlogServiceImpl.class, BlogMapper.class})
@DisplayName("BlogServiceImpl Unit Tests")
class BlogServiceImplTest {

    @SpyBean
    private BlogServiceImpl blogService;

    @MockBean
    private StringRedisTemplate stringRedisTemplate;

    @MockBean
    private IUserService userService;

    @MockBean
    private IFollowService followService;

    @MockBean
    private BlogMapper blogMapper;

    @MockBean
    private BlogLikeMapper blogLikeMapper;

    private ZSetOperations<String, String> zSetOps;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        zSetOps = mock(ZSetOperations.class);
        when(stringRedisTemplate.opsForZSet()).thenReturn(zSetOps);

        UserDTO testUser = new UserDTO();
        testUser.setId(1L);
        testUser.setNickName("blogger");
        UserHolder.saveUser(testUser);
    }

    @AfterEach
    void tearDown() {
        UserHolder.removeUser();
    }

    // ────────────────────── queryBlogById() tests ──────────────────────

    @Test
    @DisplayName("Query non-existent blog → returns fail")
    void queryBlogById_NotFound_ReturnsFail() {
        doReturn(null).when(blogService).getById(999L);

        Result result = blogService.queryBlogById(999L);

        assertFalse(result.getSuccess());
        assertEquals("笔记不存在！", result.getErrorMsg());
    }

    @Test
    @DisplayName("Query existing blog → returns blog with user info and like status enriched")
    void queryBlogById_Found_ReturnsWithUserInfo() {
        Blog blog = new Blog();
        blog.setId(1L);
        blog.setUserId(10L);
        blog.setTitle("Great Food!");

        User author = new User();
        author.setId(10L);
        author.setNickName("foodLover");
        author.setIcon("/icons/chef.png");

        doReturn(blog).when(blogService).getById(1L);
        doReturn(author).when(userService).getById(10L);

        // User has not liked this blog
        when(zSetOps.score(BLOG_LIKED_KEY + 1, "1")).thenReturn(null);

        Result result = blogService.queryBlogById(1L);

        assertTrue(result.getSuccess());
        Blog returned = (Blog) result.getData();
        assertEquals("foodLover", returned.getName());
        assertEquals("/icons/chef.png", returned.getIcon());
    }

    // ────────────────────── queryBlogLikes() tests ──────────────────────

    @Test
    @DisplayName("Query blog likes with empty like set → returns empty ok")
    void queryBlogLikes_EmptyLikes_ReturnsOk() {
        when(zSetOps.range(BLOG_LIKED_KEY + 10, 0, 4)).thenReturn(null);

        Result result = blogService.queryBlogLikes(10L);

        assertTrue(result.getSuccess());
    }
}
