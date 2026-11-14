package com.foodiego.controller;

import org.springframework.context.annotation.Profile;


import com.foodiego.dto.Result;
import com.foodiego.service.IFollowService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author FoodieGo Team
 * @since 2021-12-22
 */
@RestController
@Profile("legacy")
@RequestMapping("/follow")
public class FollowController {

    @Autowired
    private IFollowService followService;

    @PutMapping("/{id}/{isFollow}")
    public Result follow(@PathVariable("id") Long followUserId, @PathVariable("isFollow") Boolean isFollow) {

        return followService.follow(followUserId, isFollow);
    }
  @GetMapping("/or/not/{id}")
    public Result isfFollow(@PathVariable("id") Long followUserId) {

        return followService.isFollow(followUserId);
    }

    @GetMapping("/common/{id}")
    public Result common(@PathVariable("id") Long id) {

        return followService.common(id);
    }

}
