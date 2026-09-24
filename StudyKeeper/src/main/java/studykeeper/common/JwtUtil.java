package studykeeper.common;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT 工具：签发、解析、校验。
 * 密钥与有效期从 application.yml 的 jwt.secret / jwt.expiration 读取。
 */
@Component
public class JwtUtil {

    /** 自定义 claim：用户 id */
    private static final String CLAIM_USER_ID = "userId";

    /** HS256 要求密钥至少 256 位 = 32 字节 */
    private static final int MIN_KEY_BYTES = 32;

    @Value("${jwt.secret}")
    private String secret;

    /** token 有效期，单位毫秒 */
    @Value("${jwt.expiration}")
    private long expiration;

    private SecretKey key;

    /**
     * 启动时就校验密钥，避免等到第一次登录才报错。
     * 按「字节数」校验：中文在 UTF-8 下是 3 字节/字符。
     */
    @PostConstruct
    void init() {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("jwt.secret 未配置，请在 application.yml 中设置");
        }
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < MIN_KEY_BYTES) {
            throw new IllegalStateException("jwt.secret 至少需要 " + MIN_KEY_BYTES
                    + " 字节（HS256 要求 256 位密钥），当前只有 " + keyBytes.length + " 字节");
        }
        this.key = Keys.hmacShaKeyFor(keyBytes);
    }

    /**
     * 生成 token：subject 放 username，另外带一个 userId claim。
     */
    public String generateToken(Long userId, String username) {
        Date now = new Date();
        return Jwts.builder()
                .subject(username)
                .claim(CLAIM_USER_ID, userId)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + expiration))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    /**
     * 解析 token。签名不对、格式不对、已过期都会抛 JwtException。
     */
    public Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * 校验 token 是否有效（签名正确且未过期）。
     */
    public boolean validateToken(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        try {
            parseToken(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * 从 token 取 userId。
     * 注意坑：JSON 里的小整数会被反序列化成 Integer，直接 get(..., Long.class) 会抛异常，
     * 所以这里先用 Number 接再转 long。
     */
    public Long getUserId(String token) {
        Number userId = parseToken(token).get(CLAIM_USER_ID, Number.class);
        return userId == null ? null : userId.longValue();
    }

}
