package com.foodiego.controller;

import org.springframework.context.annotation.Profile;


import org.springframework.web.bind.annotation.RequestMapping;

import org.springframework.web.bind.annotation.RestController;

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
@RequestMapping("/blog-comments")
public class BlogCommentsController {

}
