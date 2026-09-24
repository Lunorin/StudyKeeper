package studykeeper.common;

/**
 * AI 工具调用执行失败：未知工具名、必填参数缺失 / 类型不对、目标任务不存在等。
 * <p>
 * message 是可以直接展示给前端的说明（只写「任务不存在」这类信息，不含 SQL / 堆栈等内部细节），
 * Controller 会把它转成 3002。
 */
public class AiToolCallException extends RuntimeException {

    public AiToolCallException(String message) {
        super(message);
    }

}
