package com.foodiego.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

/** Database source of truth for one-user-one-like. */
public interface BlogLikeMapper {

    @Insert("INSERT IGNORE INTO tb_blog_like(blog_id, user_id) VALUES(#{blogId}, #{userId})")
    int insertIgnore(@Param("blogId") Long blogId, @Param("userId") Long userId);

    @Delete("DELETE FROM tb_blog_like WHERE blog_id = #{blogId} AND user_id = #{userId}")
    int delete(@Param("blogId") Long blogId, @Param("userId") Long userId);
}
