package com.foodiego.mapper;

import com.foodiego.entity.Shop;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * <p>
 *  Mapper 接口
 * </p>
 *
 * @author FoodieGo Team
 * @since 2021-12-22
 */
public interface ShopMapper extends BaseMapper<Shop> {
    @Select("SELECT id, name, address FROM tb_shop ORDER BY name, id")
    List<Shop> listPickupWindows();
}
