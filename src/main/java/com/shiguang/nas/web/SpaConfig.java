package com.shiguang.nas.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.IOException;

/**
 * 让 Vue Router 的 history 模式能直接刷新页面。
 *
 * <p>浏览器直接访问 /photos 时服务端没有对应文件，需要回落到 index.html 由前端接管路由。
 */
@Configuration
public class SpaConfig implements WebMvcConfigurer {

    private static final String STATIC_LOCATION = "classpath:/static/";
    private static final String INDEX = "/static/index.html";

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**")
                .addResourceLocations(STATIC_LOCATION)
                .resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(String resourcePath, Resource location)
                            throws IOException {
                        // 交给父类做解析，它内置了"必须位于 location 之下"的越界校验，
                        // 自己拼 createRelative 会绕过这层保护
                        Resource resolved = super.getResource(resourcePath, location);
                        if (resolved != null) {
                            return resolved;
                        }
                        // 不存在的 API 路径必须老实返回 404，
                        // 否则前端 fetch 会拿到一个 200 的 HTML，错误极难排查
                        if (resourcePath.startsWith("api/")) {
                            return null;
                        }
                        return new ClassPathResource(INDEX);
                    }
                });
    }
}
