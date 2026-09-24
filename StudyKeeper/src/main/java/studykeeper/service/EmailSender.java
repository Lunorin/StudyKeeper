package studykeeper.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import studykeeper.config.AsyncConfig;

/**
 * 验证码邮件的异步发送入口。
 * <p>
 * <b>刻意单独一个类</b>（不并进 {@link EmailCodeService}）：{@code @Async} 靠 Spring 代理生效，
 * 同类内部自调用不走代理、会静默退化成同步 —— 而「发信」这一步正是要被异步掉的那个。
 * 所以它必须由别的 Bean（AuthController）调用。
 * <p>
 * 语义是「丢出去就不管了」：返回 void，调用方拿到 200 立刻返回，SMTP 那 10 秒留在
 * {@code emailExecutor} 线程里跑（前端 10 秒超时的根因就在这里）。
 * 异步线程里抛异常没人接，所以这里**吞掉所有异常只记日志**：用户已经收到「验证码已发送」，
 * 邮件没到就等 60 秒限流过后重发一次。
 * <p>
 * 刻意不做：不重试、不做邮件队列、不动 SMTP 配置（见 docs/decisions.md §13）。
 */
@Service
public class EmailSender {

    private static final Logger log = LoggerFactory.getLogger(EmailSender.class);

    /** 真正发信的那一步在 EmailCodeService：主题 / 正文模板 / 发件人都只留一份，这里不复制 */
    private final EmailCodeService emailCodeService;

    public EmailSender(EmailCodeService emailCodeService) {
        this.emailCodeService = emailCodeService;
    }

    /**
     * 把「发这条验证码邮件」交给 emailExecutor 线程池，立刻返回。
     * <p>
     * 邮箱与验证码只是两个普通字符串：验证码已经存进内存 Map 了，异步线程只负责发信，
     * 所以不存在「任务还没跑、数据就被改掉」的问题。
     * <p>
     * 任何失败（SMTP 连不上 / 授权码不对 / 发件人没配）都只写日志，不往外抛 ——
     * 调用方（Controller）早在毫秒级就返回 200 了。
     */
    @Async(AsyncConfig.EMAIL_EXECUTOR_BEAN_NAME)
    public void sendAsync(String email, String code) {
        try {
            emailCodeService.sendEmail(email, code);
        } catch (Exception e) {
            // 只记日志：不重试、不队列，也不把内存里那条验证码撤掉（用户可等限流过后自己重发）
            log.error("[邮箱验证码] 异步发信失败，收件人：{}（接口已返回成功，用户需稍后重发）", email, e);
        }
    }

}
