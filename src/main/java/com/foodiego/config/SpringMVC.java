package com.foodiego.config;

import com.foodiego.utils.LoginInterceptor;
import com.foodiego.utils.AdminInterceptor;
import com.foodiego.utils.AdministratorAccess;
import com.foodiego.utils.RefreshTokenInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.annotation.Resource;
import java.nio.file.Paths;

@Configuration
@Import(AdministratorAccess.class)
public class SpringMVC implements WebMvcConfigurer {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private Environment environment;

    @Resource
    private AdministratorAccess administrators;

    @Value("${foodiego.upload-dir:./uploads}")
    private String uploadDir;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        if (environment.acceptsProfiles(Profiles.of("legacy"))) {
            String location = Paths.get(uploadDir).toAbsolutePath().normalize().toUri().toString();
            registry.addResourceHandler("/imgs/**")
                    .addResourceLocations(location.endsWith("/") ? location : location + "/");
        }
        registry.addResourceHandler("/swagger-ui/**")
                .addResourceLocations("classpath:/META-INF/resources/webjars/springfox-swagger-ui/")
                .resourceChain(false);
    }


    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new RefreshTokenInterceptor(stringRedisTemplate)).order(0);
        registry.addInterceptor(new LoginInterceptor()).excludePathPatterns(
                "/user/code",
                "/user/login",
                "/blog/hot",
                "/",
                "/index.html",
                "/error",
                "/campus.css",
                "/campus.js",
                "/favicon.svg",
                "/imgs/**",
                "/actuator/health",
                "/swagger-ui.html",
                "/swagger-ui/**",
                "/swagger-resources/**",
                "/v3/api-docs/**"
        ).order(1);

        registry.addInterceptor(new AdminInterceptor(administrators, false))
                .addPathPatterns("/backoffice/admin/**").order(2);

        if (environment.acceptsProfiles(Profiles.of("legacy"))) {
            registry.addInterceptor(new AdminInterceptor(administrators, true))
                    .addPathPatterns("/shop/**", "/voucher/**").order(2);
            registry.addInterceptor(new AdminInterceptor(administrators, false))
                    .addPathPatterns("/upload/blog/delete", "/upload/blog/delete/").order(2);
        }
    }
}
