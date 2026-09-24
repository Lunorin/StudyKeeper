package studykeeper.interceptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import studykeeper.common.JwtUtil;
import studykeeper.common.Result;

import java.io.IOException;

/**
 * JWT 校验拦截器：从 Authorization 头取 "Bearer xxx"，
 * 校验通过后把 userId 放进 request attribute，供后续接口使用。
 */
@Component
public class JwtInterceptor implements HandlerInterceptor {

    /** 校验通过后，userId 存放在 request attribute 里的 key */
    public static final String USER_ID_ATTRIBUTE = "userId";

    private static final String HEADER_NAME = "Authorization";
    private static final String TOKEN_PREFIX = "Bearer ";

    private final JwtUtil jwtUtil;
    private final ObjectMapper objectMapper;

    public JwtInterceptor(JwtUtil jwtUtil, ObjectMapper objectMapper) {
        this.jwtUtil = jwtUtil;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                             @NonNull Object handler) throws Exception {
        // 跨域预检请求不带 Authorization，直接放行，否则跨域会失败
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            return true;
        }

        String header = request.getHeader(HEADER_NAME);
        if (header == null || !header.startsWith(TOKEN_PREFIX)) {
            writeUnauthorized(response);
            return false;
        }

        String token = header.substring(TOKEN_PREFIX.length()).trim();
        if (!jwtUtil.validateToken(token)) {
            writeUnauthorized(response);
            return false;
        }

        request.setAttribute(USER_ID_ATTRIBUTE, jwtUtil.getUserId(token));
        return true;
    }

    /**
     * 直接往 response 写 Result 结构的 401，同时把 HTTP 状态码也置为 401
     * （这是 decisions.md §2.3「业务错误 HTTP 恒 200」的刻意例外：前端靠 HTTP 401 判断要清 token / 跳登录页）。
     */
    private void writeUnauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(Result.error(401, "未登录")));
    }

}
