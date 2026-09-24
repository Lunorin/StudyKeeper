package studykeeper.common;

import lombok.Data;

/**
 * 统一返回包装。
 *
 * @param <T> 业务数据类型
 */
@Data
public class Result<T> {

    /** 成功状态码。 */
    public static final int SUCCESS_CODE = 0;

    /** 成功提示信息。 */
    public static final String SUCCESS_MESSAGE = "success";

    private int code;

    private String message;

    private T data;

    public Result() {
    }

    public Result(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    /**
     * 成功并携带业务数据。
     */
    public static <T> Result<T> success(T data) {
        return new Result<>(SUCCESS_CODE, SUCCESS_MESSAGE, data);
    }

    /**
     * 成功但不携带业务数据。
     */
    public static <T> Result<T> success() {
        return new Result<>(SUCCESS_CODE, SUCCESS_MESSAGE, null);
    }

    /**
     * 失败，data 恒为 null。
     */
    public static <T> Result<T> error(int code, String message) {
        return new Result<>(code, message, null);
    }

}
