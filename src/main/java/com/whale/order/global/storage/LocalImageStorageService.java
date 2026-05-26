package com.whale.order.global.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

/**
 * 로컬 파일 시스템에 이미지를 저장하는 구현체.
 * 운영 환경에서는 S3ImageStorageService로 교체한다.
 */
@Service
public class LocalImageStorageService implements ImageStorageService {

    private static final long MAX_FILE_SIZE = 10 * 1024 * 1024; // 10MB

    @Value("${app.upload.menu-dir:./uploads/menus}")
    private String menuUploadDir;

    @Value("${app.upload.url-prefix:/uploads/menus}")
    private String urlPrefix;

    @Override
    public String store(MultipartFile file) {
        validateFile(file);

        String extension = extractExtension(file.getOriginalFilename());
        String filename = UUID.randomUUID() + "." + extension;

        Path uploadPath = Paths.get(menuUploadDir).toAbsolutePath().normalize();
        try {
            Files.createDirectories(uploadPath);
            Files.copy(file.getInputStream(), uploadPath.resolve(filename));
        } catch (IOException e) {
            throw new RuntimeException("이미지 저장에 실패했습니다.", e);
        }

        return urlPrefix + "/" + filename;
    }

    @Override
    public void delete(String imageUrl) {
        if (imageUrl == null || !imageUrl.startsWith(urlPrefix)) {
            return;
        }

        String filename = imageUrl.substring(urlPrefix.length() + 1);
        Path filePath = Paths.get(menuUploadDir).toAbsolutePath().normalize().resolve(filename);
        try {
            Files.deleteIfExists(filePath);
        } catch (IOException e) {
            // 파일 삭제 실패는 무시 (이미 삭제됐거나 외부 경로)
        }
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("이미지 파일이 비어 있습니다.");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new IllegalArgumentException("이미지 파일 크기는 10MB를 초과할 수 없습니다.");
        }
        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            throw new IllegalArgumentException("이미지 파일만 업로드할 수 있습니다.");
        }
    }

    private String extractExtension(String originalFilename) {
        if (originalFilename == null || !originalFilename.contains(".")) {
            return "jpg";
        }
        return originalFilename.substring(originalFilename.lastIndexOf('.') + 1).toLowerCase();
    }
}
