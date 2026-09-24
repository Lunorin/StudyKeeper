package studykeeper.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 异步任务配置。
 * 目前两个用途：用户画像记忆提炼（MemoryService.extractAndSave）与验证码邮件发送（EmailSender.sendAsync）。
 * <p>
 * 只开 @EnableAsync + 专用线程池，不引入任何新依赖（Spring 自带的 ThreadPoolTaskExecutor）；
 * 不给 @Async 配默认执行器，所以每个 @Async 方法都必须显式写执行器名
 * （现在是 memoryExtractExecutor / emailExecutor 两个）。
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    /** 线程池名称：@Async("memoryExtractExecutor") 里引用的就是它 */
    private static final String EXECUTOR_BEAN_NAME = "memoryExtractExecutor";

    /** 记忆提炼线程池参数：并发量很小（一次聊天最多一次提炼），固定写在这里，不额外做配置项 */
    private static final int CORE_POOL_SIZE = 2;
    private static final int MAX_POOL_SIZE = 4;
    private static final int QUEUE_CAPACITY = 100;

    /** 线程名前缀：日志里一眼能看出是记忆提炼的线程 */
    private static final String THREAD_NAME_PREFIX = "memory-extract-";

    /** 流式对话线程池的 Bean 名：AiController 用它把 streamChat 丢到后台执行 */
    public static final String CHAT_STREAM_EXECUTOR_BEAN_NAME = "chatStreamExecutor";

    /** 流式对话线程池参数：一条 SSE 连接占一个线程（线程里阻塞地读 Python 的流） */
    private static final int CHAT_STREAM_CORE_POOL_SIZE = 4;
    private static final int CHAT_STREAM_MAX_POOL_SIZE = 8;
    private static final int CHAT_STREAM_QUEUE_CAPACITY = 50;

    /** 流式对话的线程名前缀 */
    private static final String CHAT_STREAM_THREAD_NAME_PREFIX = "chat-stream-";

    /** 验证码邮件线程池的 Bean 名：EmailSender.sendAsync 的 @Async 里引用的就是它 */
    public static final String EMAIL_EXECUTOR_BEAN_NAME = "emailExecutor";

    /** 验证码邮件线程池参数：一次发信是 10 秒级的 SMTP 往返，核心 2 / 最大 4 / 队列 100 够日常用 */
    private static final int EMAIL_CORE_POOL_SIZE = 2;
    private static final int EMAIL_MAX_POOL_SIZE = 4;
    private static final int EMAIL_QUEUE_CAPACITY = 100;

    /** 验证码邮件的线程名前缀：日志里一眼能看出是发信线程 */
    private static final String EMAIL_THREAD_NAME_PREFIX = "email-send-";

    /**
     * 记忆提炼专用线程池。
     * <p>
     * 拒绝策略用 CallerRunsPolicy：队列（100）也满时，让调用线程（也就是聊天请求线程）自己跑这次提炼 ——
     * 宁可这一次聊天慢一点，也不丢记忆。这是唯一会退化成同步的场景，属于刻意选择。
     * <p>
     * 不在这里调 initialize()：返回 ThreadPoolTaskExecutor 类型的 Bean 时，
     * Spring 会自己走 afterPropertiesSet() 初始化，手动调会多建一个线程池。
     */
    @Bean(EXECUTOR_BEAN_NAME)
    public ThreadPoolTaskExecutor memoryExtractExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(CORE_POOL_SIZE);
        executor.setMaxPoolSize(MAX_POOL_SIZE);
        executor.setQueueCapacity(QUEUE_CAPACITY);
        executor.setThreadNamePrefix(THREAD_NAME_PREFIX);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        // 停机时给正在跑的提炼一点时间收尾（最多 10 秒），避免重启时把任务直接掐断
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        return executor;
    }

    /**
     * 流式对话专用线程池（POST /api/ai/chat/stream）。
     * <p>
     * 一条 SSE 连接占一个线程：线程里阻塞地读 Python 的流、边读边往前端推，直到这轮对话结束。
     * 拒绝策略用默认的 AbortPolicy（池 + 队列都满时直接拒绝）：这时宁可让新请求快速失败
     * （Controller 会回一条 error 事件），也不要像记忆提炼那样退化成同步 —— 同步跑会把 Tomcat
     * 请求线程也一起占住，整个服务都会被拖死。
     * <p>
     * 热部署 / 停机时给正在跑的流 10 秒收尾时间；超时未结束的连接由 Tomcat 关闭（此时前端会收到断流）。
     */
    @Bean(CHAT_STREAM_EXECUTOR_BEAN_NAME)
    public ThreadPoolTaskExecutor chatStreamExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(CHAT_STREAM_CORE_POOL_SIZE);
        executor.setMaxPoolSize(CHAT_STREAM_MAX_POOL_SIZE);
        executor.setQueueCapacity(CHAT_STREAM_QUEUE_CAPACITY);
        executor.setThreadNamePrefix(CHAT_STREAM_THREAD_NAME_PREFIX);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        return executor;
    }

    /**
     * 验证码邮件专用线程池（EmailSender.sendAsync）。
     * <p>
     * 为什么要独立线程池：一次 SMTP 是 10 秒级往返，而前端 10 秒就超时了（就是「网络不太好」那条提示的根因）。
     * 丢到这里跑之后，接口只花「生成验证码 + 存内存」的毫秒级时间就返回 200。
     * <p>
     * 拒绝策略用 CallerRunsPolicy：队列（100）也满时让调用线程自己发 —— 极端情况下这一次响应会退化成同步
     * （又回到 10 秒），但验证码邮件不会静默丢掉。不丢信优先，和 memoryExtractExecutor 是同一套取舍。
     * <p>
     * 停机时给正在发的信最多 10 秒收尾：接口已经告诉用户「发送成功」，重启时更不能把在飞的邮件掐断。
     */
    @Bean(EMAIL_EXECUTOR_BEAN_NAME)
    public ThreadPoolTaskExecutor emailExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(EMAIL_CORE_POOL_SIZE);
        executor.setMaxPoolSize(EMAIL_MAX_POOL_SIZE);
        executor.setQueueCapacity(EMAIL_QUEUE_CAPACITY);
        executor.setThreadNamePrefix(EMAIL_THREAD_NAME_PREFIX);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        return executor;
    }

}
