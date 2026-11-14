package com.foodiego.controller;

import org.springframework.context.annotation.Profile;


import cn.hutool.core.util.StrUtil;
import com.alibaba.csp.sentinel.annotation.SentinelResource;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.foodiego.dto.Result;
import com.foodiego.entity.Shop;
import com.foodiego.service.IShopService;
import com.foodiego.utils.SystemConstants;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;

/**
 * <p>
 * 商铺管理接口
 * </p>
 *
 * @author FoodieGo Team
 * @since 2021-12-22
 */
@Api(tags = "商铺管理")
@Slf4j
@RestController
@Profile("legacy")
@RequestMapping("/shop")
public class ShopController {

    @Resource
    public IShopService shopService;

    /**
     * 根据id查询商铺信息
     * @param id 商铺id
     * @return 商铺详情数据
     */
    @ApiOperation("根据ID查询商铺详情 - 含缓存穿透/击穿/雪崩防护")
    @GetMapping("/{id}")
    @SentinelResource(value = "queryShopById", blockHandler = "queryShopByIdBlock")
    public Result queryShopById(@ApiParam(value = "商铺ID", required = true) @PathVariable("id") Long id) {
        return shopService.queryById(id);
    }

    /**
     * 查询店铺限流兜底 — 返回降级提示
     */
    public Result queryShopByIdBlock(Long id, BlockException e) {
        log.warn("查询店铺接口被限流, id={}", id);
        return Result.fail("系统繁忙，请稍后再试");
    }

    /**
     * 新增商铺信息
     * @param shop 商铺数据
     * @return 商铺id
     */
    @ApiOperation("新增商铺")
    @PostMapping
    public Result saveShop(@ApiParam(value = "商铺信息", required = true) @RequestBody Shop shop) {
        // 写入数据库
        shopService.save(shop);
        // 返回店铺id
        return Result.ok(shop.getId());
    }

    /**
     * 更新商铺信息
     * @param shop 商铺数据
     * @return 无
     */
    @ApiOperation("更新商铺信息 - 同步更新数据库和Redis缓存")
    @PutMapping
    public Result updateShop(@ApiParam(value = "商铺信息", required = true) @RequestBody Shop shop) {

        return shopService.updateShop(shop);
    }

    /**
     * 根据商铺类型分页查询商铺信息
     * @param typeId 商铺类型
     * @param current 页码
     * @param x 用户经度（可选）
     * @param y 用户纬度（可选）
     * @return 商铺列表
     */
    @ApiOperation("按类型分页查询商铺列表 - 支持按距离排序")
    @GetMapping("/of/type")
    @SentinelResource(value = "queryShopByType", blockHandler = "queryShopByTypeBlock")
    public Result queryShopByType(
            @ApiParam(value = "商铺类型ID", required = true) @RequestParam("typeId") Integer typeId,
            @ApiParam(value = "页码", defaultValue = "1") @RequestParam(value = "current", defaultValue = "1") Integer current,
            @ApiParam(value = "用户经度", required = false) @RequestParam(value = "x", required = false) Double x,
            @ApiParam(value = "用户纬度", required = false) @RequestParam(value = "y", required = false) Double y
    ) {
        return shopService.queryShopByType(typeId, current, x, y);
    }

    /**
     * 按类型查询限流兜底
     */
    public Result queryShopByTypeBlock(Integer typeId, Integer current, Double x, Double y, BlockException e) {
        log.warn("按类型查询店铺被限流, typeId={}", typeId);
        return Result.fail("系统繁忙，请稍后再试");
    }

    /**
     * 根据商铺名称关键字分页查询商铺信息
     * @param name 商铺名称关键字
     * @param current 页码
     * @return 商铺列表
     */
    @ApiOperation("按名称关键字搜索商铺")
    @GetMapping("/of/name")
    public Result queryShopByName(
            @ApiParam(value = "商铺名称关键字", required = false) @RequestParam(value = "name", required = false) String name,
            @ApiParam(value = "页码", defaultValue = "1") @RequestParam(value = "current", defaultValue = "1") Integer current
    ) {
        // 根据类型分页查询
        Page<Shop> page = shopService.query()
                .like(StrUtil.isNotBlank(name), "name", name)
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 返回数据
        return Result.ok(page.getRecords());
    }
}
