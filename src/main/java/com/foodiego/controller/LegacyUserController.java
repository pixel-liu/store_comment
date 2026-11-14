package com.foodiego.controller;

import cn.hutool.core.bean.BeanUtil;
import com.foodiego.dto.Result;
import com.foodiego.dto.UserDTO;
import com.foodiego.entity.User;
import com.foodiego.entity.UserInfo;
import com.foodiego.service.IUserInfoService;
import com.foodiego.service.IUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("legacy")
@RequestMapping("/user")
@RequiredArgsConstructor
public class LegacyUserController {
    private final IUserService userService;
    private final IUserInfoService userInfoService;

    @GetMapping("/info/{id}")
    public Result info(@PathVariable("id") Long userId) {
        UserInfo info = userInfoService.getById(userId);
        if (info == null) {
            return Result.ok();
        }
        info.setCreateTime(null);
        info.setUpdateTime(null);
        return Result.ok(info);
    }

    @GetMapping("/{id}")
    public Result queryUserById(@PathVariable("id") Long userId) {
        User user = userService.getById(userId);
        return user == null ? Result.ok() : Result.ok(BeanUtil.copyProperties(user, UserDTO.class));
    }

    @PutMapping("/sign")
    public Result sign() {
        return userService.sign();
    }

    @GetMapping("/sign")
    public Result signCount() {
        return userService.signCount();
    }
}
