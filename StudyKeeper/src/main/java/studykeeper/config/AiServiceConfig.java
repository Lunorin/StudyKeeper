package studykeeper.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

/**
 * AI 服务（Python / FastAPI）调用配置。
 * 只声明一个给 AI 服务用的 RestTemplate：连接超时与读取超时都取 ai.service.timeout（毫秒）。
 */
@Configuration
public class AiServiceConfig {

    /**
     * AI 服务专用 RestTemplate。
     * 注意用 Duration 重载：int 重载自 Spring Framework 6.1 起已标记 @Deprecated。
     */
    @Bean
    public RestTemplate aiRestTemplate(@Value("${ai.service.timeout}") long timeout) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(timeout));
        requestFactory.setReadTimeout(Duration.ofMillis(timeout));
        return new RestTemplate(requestFactory);
    }

}
