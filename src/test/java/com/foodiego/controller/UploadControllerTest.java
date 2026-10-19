package com.foodiego.controller;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import java.nio.file.Path;
import java.nio.file.Files;
import static org.junit.jupiter.api.Assertions.*;

class UploadControllerTest {
    @TempDir Path temp;

    @Test void configuredDirectoryStoresAndDeletesImages() {
        UploadController controller = new UploadController(temp.toString());
        String name = (String) controller.uploadImage(new MockMultipartFile(
                "file", "photo.JPG", "image/jpeg", new byte[]{1,2,3})).getData();
        Path file = temp.resolve(name.substring(1));
        assertTrue(Files.exists(file));
        assertTrue(controller.deleteBlogImg(name).getSuccess());
        assertFalse(Files.exists(file));
    }
    @Test void deletionRejectsTraversalAndAbsolutePaths() {
        UploadController controller = new UploadController(temp.toString());
        for (String name : new String[]{"../secret.txt", "C:\\secret.txt", "/blogs/1/2/../../secret.jpg", ""}) {
            assertThrows(IllegalArgumentException.class, () -> controller.deleteBlogImg(name));
        }
    }
    @Test void uploadRejectsUnsupportedExtension() {
        UploadController controller = new UploadController(temp.toString());
        assertThrows(IllegalArgumentException.class, () -> controller.uploadImage(
                new MockMultipartFile("file", "payload.html", "text/html", new byte[]{1})));
    }
}
