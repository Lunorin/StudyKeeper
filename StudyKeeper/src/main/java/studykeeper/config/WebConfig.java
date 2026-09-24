package studykeeper.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import studykeeper.interceptor.JwtInterceptor;

/**
 * Web 配置：注册 JWT 拦截器、声明密码编码器。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final JwtInterceptor jwtInterceptor;

    public WebConfig(JwtInterceptor jwtInterceptor) {
        this.jwtInterceptor = jwtInterceptor;
    }

    /**
     * /api/** 全部需要 token，只放行注册、登录、发验证码、找回密码（发码 + 重置）；
     * logout 与 me 也要 token（按需求）。这几条是邮箱注册 / 找回密码的入口，必须在登录之前就能调。
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(jwtInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/auth/register", "/api/auth/login",
                        "/api/auth/send-email-code", "/api/auth/forgot-password",
                        "/api/auth/forgot-password/send-code", "/api/auth/forgot-password/reset");
    }

    /**
     * BCrypt 密码编码器：无状态单例，交给 Spring 管理。
     */
    @Bean
    public BCryptPasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

}
