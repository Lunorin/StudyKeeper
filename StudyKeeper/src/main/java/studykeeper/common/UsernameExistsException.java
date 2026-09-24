package studykeeper.common;

/**
 * 用户名已被**其他**用户占用：改资料时校验到重名。
 * <p>
 * 单独一个异常类是为了和「参数不合法（400）」区分开 —— 重名要返回 1001。
 * message 是可以直接展示给前端的说明，Controller 负责转成 1001。
 */
public class UsernameExistsException extends RuntimeException {

    public UsernameExistsException(String message) {
        super(message);
    }

}
