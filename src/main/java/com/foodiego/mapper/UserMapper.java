package com.foodiego.mapper;

import com.foodiego.entity.User;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Select;

/**
 * <p>
 *  Mapper 接口
 * </p>
 *
 * @author FoodieGo Team
 * @since 2021-12-22
 */
public interface UserMapper extends BaseMapper<User> {
    @Select("SELECT id FROM tb_user WHERE phone = #{phone}")
    Long findIdByPhone(String phone);

    @Select("SELECT id, nick_name, icon FROM tb_user WHERE phone = #{phone}")
    User findLoginUserByPhone(String phone);
}
