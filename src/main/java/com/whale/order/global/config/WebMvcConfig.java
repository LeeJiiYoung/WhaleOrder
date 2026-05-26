package com.whale.order.global.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Paths;

/**
 * 업로드된 이미지 파일을 정적 리소스로 서빙하기 위한 설정.
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Value("${app.upload.menu-dir:./uploads/menus}")
    private String menuUploadDir;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String absolutePath = Paths.get(menuUploadDir).toAbsolutePath().normalize().toString();
        registry.addResourceHandler("/uploads/menus/**")
                .addResourceLocations("file:" + absolutePath + "/");
    }
}
