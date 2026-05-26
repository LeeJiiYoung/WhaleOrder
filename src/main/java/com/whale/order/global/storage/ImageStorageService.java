package com.whale.order.global.storage;

import org.springframework.web.multipart.MultipartFile;

/**
 * 이미지 저장소 추상화 인터페이스.
 * 로컬(개발) / S3(운영) 간 전환을 위해 분리.
 */
public interface ImageStorageService {

    /**
     * 이미지 파일을 저장하고 접근 가능한 URL 경로를 반환한다.
     */
    String store(MultipartFile file);

    /**
     * 저장된 이미지를 URL 경로로 삭제한다.
     */
    void delete(String imageUrl);
}
