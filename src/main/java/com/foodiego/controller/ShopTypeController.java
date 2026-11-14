package com.foodiego.controller;

import org.springframework.context.annotation.Profile;


import com.foodiego.dto.Result;
import com.foodiego.entity.ShopType;
import com.foodiego.service.IShopTypeService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import java.util.List;

/**
 * <p>
 * 商铺类型管理接口
 * </p>
 *
 * @author FoodieGo Team
 * @since 2021-12-22
 */
@Api(tags = "商铺类型管理")
@RestController
@Profile("legacy")
@RequestMapping("/shop-type")
public class ShopTypeController {
    @Resource
    private IShopTypeService typeService;

    /**
     * 查询所有商铺类型
     * @return 商铺类型列表
     */
    @ApiOperation("查询所有商铺类型列表")
    @GetMapping("list")
    public Result queryTypeList() {
//        List<ShopType> typeList = typeService
//                .query().orderByAsc("sort").list();
        return typeService.queryTypeList();
    }
}
