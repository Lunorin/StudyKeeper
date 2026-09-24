package studykeeper.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 邮箱验证码业务层：生成 + 发信 + 校验（注册、找回密码都用它）。
 * <p>
 * 验证码**只存内存**（{@link ConcurrentHashMap}，key 是邮箱），不建表、不落库：
 * 进程重启后已发出的验证码就失效了（用户重新点一次「发送验证码」即可），这是刻意接受的代价 ——
 * 个人项目不值得为 5 分钟有效期的临时数据加一张表。
 * <p>
 * 两条硬规则（可配，见 application.yml 的 app.email.*）：
 * <ul>
 *   <li>同一个邮箱 {@code send-interval-seconds} 内只能发一次，防刷；</li>
 *   <li>验证码 {@code code-expire-seconds} 后过期；校验成功即删除（一次性）。</li>
 * </ul>
 * 发信走 {@link JavaMailSender}（由 spring-boot-starter-mail 按 spring.mail.* 自动装配），
 * 邮箱与授权码从环境变量 MAIL_USERNAME / MAIL_PASSWORD 读，不写死在仓库里。
 * <p>
 * 失败约定：邮箱格式不对 / 发得太频繁 / 没配发件人都抛 {@link IllegalArgumentException}，
 * message 可以直接给前端看，由 Controller 转成 400；**SMTP 发信失败已经不在这个口径里** ——
 * 发信是异步的（见下一段），失败只写日志。
 * <p>
 * <b>生成与发信拆成两步</b>（{@link #generateCode(String)} / {@link #sendEmail(String, String)}）：
 * 一次 SMTP 要 10 秒级，而前端 10 秒就超时，所以只有「生成 + 存内存」留在请求线程里同步做，
 * 发信交给 {@link EmailSender#sendAsync(String, String)} 丢进 emailExecutor 线程池 ——
 * 接口因此能立刻返回 200（见 docs/decisions.md §13）。
 */
@Service
public class EmailCodeService {

    private static final Logger log = LoggerFactory.getLogger(EmailCodeService.class);

    /** 邮箱格式：与 UserService 同一口径（有 @、有域名、有点），不追求 RFC 完整 */
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s.]+(\\.[^@\\s.]+)+$");

    /** email 列是 varchar(100)，先按长度拦一层 */
    private static final int MAX_EMAIL_LENGTH = 100;

    /** 6 位数字验证码：000000 - 999999 */
    private static final int CODE_BOUND = 1_000_000;

    private static final String MAIL_SUBJECT = "【学习监督】验证码";

    /** 正文模板：占位符依次是验证码、有效期（分钟） */
    private static final String MAIL_TEXT_TEMPLATE = "你的验证码是：%s，%d 分钟内有效";

    /** 邮箱不合法时给前端看的固定文案（400） */
    private static final String EMAIL_INVALID_MESSAGE = "邮箱格式不正确";

    /** 同一邮箱发得太频繁时给前端看的文案模板（400），%d 是还要等多少秒 */
    private static final String TOO_FREQUENT_TEMPLATE = "验证码发送过于频繁，请 %d 秒后再试";

    /** 发信失败（SMTP 连不上 / 授权码不对 / 没配环境变量）时给前端看的固定文案（400） */
    private static final String SEND_FAILED_MESSAGE = "邮件发送失败，请稍后再试";

    /** 内存里的验证码：key = 归一化后的邮箱（trim + 小写），value = 这一条验证码的状态 */
    private final Map<String, CodeEntry> codes = new ConcurrentHashMap<>();

    private final JavaMailSender mailSender;

    /** 发件人 = QQ 邮箱地址（spring.mail.username），没配置时发不出去 */
    private final String from;

    /** 验证码有效期（秒） */
    private final long codeExpireSeconds;

    /** 同一邮箱两次发送的最小间隔（秒） */
    private final long sendIntervalSeconds;

    private final SecureRandom random = new SecureRandom();

    public EmailCodeService(JavaMailSender mailSender,
                            @Value("${spring.mail.username:}") String from,
                            @Value("${app.email.code-expire-seconds:300}") long codeExpireSeconds,
                            @Value("${app.email.send-interval-seconds:60}") long sendIntervalSeconds) {
        this.mailSender = mailSender;
        this.from = from;
        this.codeExpireSeconds = codeExpireSeconds;
        this.sendIntervalSeconds = sendIntervalSeconds;
    }

    /**
     * 生成验证码并记到内存，**不发信**（发信见 {@link #sendEmail(String, String)}）。
     * <p>
     * 顺序：校验邮箱 → 查「有没有配发件人」→ 清过期条目 → 查发送间隔 → 生成 6 位数字 → 存内存。
     * 全是内存操作 + 一次正则匹配，毫秒级返回 —— 慢的那一步（SMTP，10 秒级）不在这里。
     * <p>
     * 「没配发件人」这类**配置错**刻意留在这里当场报错，而不是丢给异步线程：
     * 否则用户会拿到「发送成功」、白等 60 秒限流，最后还是收不到信。
     *
     * @return 生成的 6 位验证码，由调用方转给 {@link EmailSender#sendAsync(String, String)} 去发
     * @throws IllegalArgumentException 邮箱不合法 / 没配发件人 / 距离上次发送不足 {@code send-interval-seconds} 秒
     */
    public String generateCode(String email) {
        // 1. 先挡掉不合法入参：格式不对就没必要生成验证码
        String target = requireEmail(email);

        // 2. 没配发件人（环境变量 MAIL_USERNAME 没设）时直接给出明确错误，别让用户拿到成功却永远收不到信
        if (from == null || from.isBlank()) {
            log.error("未配置发件邮箱（环境变量 MAIL_USERNAME / spring.mail.username），无法发送验证码邮件");
            throw new IllegalArgumentException(SEND_FAILED_MESSAGE);
        }

        // 3. 顺手清掉已过期的条目，避免这个 Map 只增不减
        pruneExpired();

        Instant now = Instant.now();
        CodeEntry previous = codes.get(target);
        if (previous != null) {
            long remaining = sendIntervalSeconds - ChronoUnit.SECONDS.between(previous.lastSentAt(), now);
            if (remaining > 0) {
                throw new IllegalArgumentException(String.format(TOO_FREQUENT_TEMPLATE, remaining));
            }
        }

        // 4. 生成并记账：先占住 lastSentAt，后面并发调用就拿不到「发信机会」了
        String code = randomCode();
        codes.put(target, new CodeEntry(code, now.plusSeconds(codeExpireSeconds), now));
        return code;
    }

    /**
     * 发一条验证码邮件（**只发信**：不生成、不校验、不改内存里的记录）。
     * <p>
     * 由 {@link EmailSender#sendAsync(String, String)} 在 emailExecutor 线程里调用，所以这里可以
     * 心安理得地阻塞等 SMTP。失败抛 {@link MailException}，兜底交给异步路径（那里只记日志）。
     * <p>
     * 注意异步路径**不回滚** {@link #generateCode(String)} 已存下的那条验证码：SMTP 挂了的时候，
     * 回滚会让「限流」形同虚设（用户重试一次就重置一次），所以选择让记录留着 ——
     * 用户拿到的提示是成功，最坏情况是等 60 秒限流过后重发一次。
     */
    public void sendEmail(String email, String code) {
        String target = normalize(email);
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(target);
        message.setSubject(MAIL_SUBJECT);
        message.setText(String.format(MAIL_TEXT_TEMPLATE, code, codeExpireSeconds / 60));
        mailSender.send(message);
        // 只记「发给谁」，不把验证码打进日志
        log.info("[邮箱验证码] 已发送，收件人：{}", target);
    }

    /**
     * 校验验证码。四种情况：没发过 / 已过期 / 不匹配 → false；匹配 → 删除并返回 true（一次性）。
     * <p>
     * 刻意不抛异常：调用方（注册）把它当成「一个布尔条件」，失败文案固定是「验证码错误或已过期」，
     * 不区分具体原因（别让攻击者知道自己是猜错了还是过期了）。
     */
    public boolean verify(String email, String code) {
        if (email == null || code == null) {
            return false;
        }
        String target = normalize(email);
        CodeEntry entry = codes.get(target);
        if (entry == null) {
            return false;
        }
        if (!entry.expireAt().isAfter(Instant.now())) {
            codes.remove(target, entry);
            return false;
        }
        if (!entry.code().equals(code.trim())) {
            return false;
        }
        // 用掉就删：同一个验证码不能注册两次
        codes.remove(target, entry);
        return true;
    }

    /**
     * 校验并归一化邮箱：null / 空白 / 超长 / 格式不对都抛 {@link IllegalArgumentException}。
     * 返回值统一 trim + 小写 —— 邮箱在 Map 里当 key（区分大小写），而 QQ 邮箱实际上不区分大小写，
     * 不归一化会出现「发的时候写 A@qq.com、验的时候写 a@qq.com 找不到」。
     */
    private String requireEmail(String email) {
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("邮箱不能为空");
        }
        String value = normalize(email);
        if (value.length() > MAX_EMAIL_LENGTH) {
            throw new IllegalArgumentException("邮箱不能超过 " + MAX_EMAIL_LENGTH + " 个字符");
        }
        if (!EMAIL_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException(EMAIL_INVALID_MESSAGE);
        }
        return value;
    }

    /** trim + 小写（不做格式校验；verify 也要用同一套归一化口径，否则和 generateCode / sendEmail 对不上） */
    private String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    /** 6 位数字，左侧补 0（如 "001234"）；用 SecureRandom，别让验证码可预测 */
    private String randomCode() {
        return String.format("%06d", random.nextInt(CODE_BOUND));
    }

    /** 清掉所有已过期的条目（没被 verify 用掉的那些只能靠这里回收） */
    private void pruneExpired() {
        Instant now = Instant.now();
        codes.entrySet().removeIf(entry -> !entry.getValue().expireAt().isAfter(now));
    }

    /**
     * 一条验证码的状态：6 位数字、过期时间、最近一次发送时间。
     * 用 record（不可变）而不是可变对象：Map 里存的值只整体替换、不做原地修改，省掉同步问题。
     */
    private record CodeEntry(String code, Instant expireAt, Instant lastSentAt) {
    }

}
