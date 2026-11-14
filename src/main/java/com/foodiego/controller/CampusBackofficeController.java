package com.foodiego.controller;

import com.foodiego.dto.Result;
import com.foodiego.entity.Shop;
import com.foodiego.mapper.MealMapper;
import com.foodiego.mapper.ShopMapper;
import com.foodiego.mapper.UserMapper;
import com.foodiego.utils.AdministratorAccess;
import com.foodiego.utils.RegexUtils;
import com.foodiego.utils.UserHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/backoffice")
@RequiredArgsConstructor
public class CampusBackofficeController {
    private final AdministratorAccess administrators;
    private final MealMapper meals;
    private final ShopMapper shops;
    private final UserMapper users;

    public record WindowInput(String name, String address) {}
    public record StaffInput(String phone) {}
    public record WindowView(String id, String name, String address) {}
    public record StaffView(String id, String nickName) {}

    @GetMapping("/access")
    public Result access() {
        var user = UserHolder.getUser();
        List<Map<String, String>> windows = meals.listStaffShops(user.getId()).stream()
                .map(shop -> Map.of("id", shop.getId().toString(), "name", shop.getName())).toList();
        return Result.ok(Map.of("nickName", user.getNickName() == null ? "" : user.getNickName(),
                "administrator", administrators.allows(user.getId()), "windows", windows));
    }

    @GetMapping("/admin/windows")
    public Result windows() {
        return Result.ok(shops.listPickupWindows().stream()
                .map(CampusBackofficeController::windowView).toList());
    }

    @PostMapping("/admin/windows")
    public Result createWindow(@RequestBody WindowInput input) {
        String name = input.name() == null ? "" : input.name().strip();
        String address = input.address() == null ? "" : input.address().strip();
        if (name.isEmpty() || name.length() > 128 || address.isEmpty() || address.length() > 255) {
            return Result.fail("请填写窗口名称（最多 128 字）和取餐地址（最多 255 字）");
        }
        // Campus windows use the existing shop model and its required fields.
        Shop shop = new Shop().setName(name).setAddress(address).setTypeId(1L).setImages("")
                .setX(0.0).setY(0.0).setSold(0).setComments(0).setScore(0);
        if (shops.insert(shop) != 1) {
            throw new IllegalStateException("食堂窗口保存失败");
        }
        return Result.ok(windowView(shop));
    }

    @GetMapping("/admin/windows/{shopId}/staff")
    public Result staff(@PathVariable long shopId) {
        return Result.ok(meals.listWindowStaff(shopId).stream()
                .map(user -> new StaffView(user.getId().toString(), user.getNickName())).toList());
    }

    @PostMapping("/admin/windows/{shopId}/staff")
    public Result bind(@PathVariable long shopId, @RequestBody StaffInput input) {
        String phone = input.phone() == null ? "" : input.phone().strip();
        if (RegexUtils.isPhoneInvalid(phone)) return Result.fail("请输入已注册账号的手机号");
        if (shops.selectById(shopId) == null) return Result.fail("找不到该食堂窗口");
        Long userId = users.findIdByPhone(phone);
        if (userId == null) return Result.fail("该手机号尚未注册，请先登录一次");
        meals.bindWindowStaff(shopId, userId);
        return Result.ok();
    }

    @DeleteMapping("/admin/windows/{shopId}/staff/{userId}")
    public Result unbind(@PathVariable long shopId, @PathVariable long userId) {
        meals.unbindWindowStaff(shopId, userId);
        return Result.ok();
    }

    private static WindowView windowView(Shop shop) {
        return new WindowView(shop.getId().toString(), shop.getName(), shop.getAddress());
    }
}
