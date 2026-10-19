package com.foodiego.controller;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.foodiego.dto.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.beans.factory.annotation.Value;

import java.io.IOException;
import java.util.UUID;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.Files;
import java.util.Locale;
import java.util.Arrays;

@Slf4j
@RestController
@RequestMapping("upload")
public class UploadController {
    private final Path uploadRoot;

    public UploadController(@Value("${foodiego.upload-dir:./uploads}") String uploadDir) {
        this.uploadRoot = Paths.get(uploadDir).toAbsolutePath().normalize();
    }

    @PostMapping("blog")
    public Result uploadImage(@RequestParam("file") MultipartFile image) {
        try {
            // 获取原始文件名称
            String originalFilename = image.getOriginalFilename();
            // 生成新文件名
            String fileName = createNewFileName(originalFilename);
            // 保存文件
            Path target = resolveImage(fileName);
            Files.createDirectories(target.getParent());
            image.transferTo(target.toFile());
            // 返回结果
            log.debug("文件上传成功，{}", fileName);
            return Result.ok(fileName);
        } catch (IOException e) {
            throw new RuntimeException("文件上传失败", e);
        }
    }

    @GetMapping("/blog/delete")
    public Result deleteBlogImg(@RequestParam("name") String filename) {
        Path file = resolveImage(filename);
        if (Files.isDirectory(file)) {
            return Result.fail("错误的文件名称");
        }
        FileUtil.del(file.toFile());
        return Result.ok();
    }

    private String createNewFileName(String originalFilename) {
        // 获取后缀
        if (originalFilename == null) {
            throw new IllegalArgumentException("缺少文件名");
        }
        String suffix = StrUtil.subAfter(originalFilename, ".", true).toLowerCase(Locale.ROOT);
        if (!Arrays.asList("jpg", "jpeg", "png", "gif", "webp").contains(suffix)) {
            throw new IllegalArgumentException("不支持的图片格式");
        }
        // 生成目录
        String name = UUID.randomUUID().toString();
        int hash = name.hashCode();
        int d1 = hash & 0xF;
        int d2 = (hash >> 4) & 0xF;
        // 生成文件名
        return StrUtil.format("/blogs/{}/{}/{}.{}", d1, d2, name, suffix);
    }

    private Path resolveImage(String filename) {
        if (filename == null || !filename.matches("/?blogs/[0-9]+/[0-9]+/[a-fA-F0-9-]+\\.(jpg|jpeg|png|gif|webp)")) {
            throw new IllegalArgumentException("错误的文件名称");
        }
        Path target = uploadRoot.resolve(filename.startsWith("/") ? filename.substring(1) : filename).normalize();
        if (!target.startsWith(uploadRoot)) {
            throw new IllegalArgumentException("错误的文件名称");
        }
        return target;
    }
}
