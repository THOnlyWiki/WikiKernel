package cc.thonly.wikikernel.web;

import org.apache.tomcat.util.http.InvalidParameterException;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

@ControllerAdvice
public class RequestParameterExceptionAdvice {

    // 保留 Tomcat 对非法参数的状态码，避免把客户端输入错误记录成 Servlet 异常堆栈。
    @ExceptionHandler(InvalidParameterException.class)
    public ResponseEntity<Void> invalidParameter(InvalidParameterException exception) {
        return ResponseEntity.status(exception.getErrorCode()).cacheControl(CacheControl.noStore()).build();
    }
}
