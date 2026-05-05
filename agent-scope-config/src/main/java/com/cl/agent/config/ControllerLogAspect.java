package com.cl.agent.config;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.alibaba.fastjson2.JSON;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;

/**
 * Controller 层统一日志切面
 * 拦截所有 controller 接口，将入参和返回值序列化为 JSON 后打印，使用 Fastjson2 实现
 */
@Slf4j
@Aspect
@Component
public class ControllerLogAspect {

    /**
     * 切点定义：拦截 com.cl.agent.controller 包及其子包下所有类的所有方法
     */
    @Pointcut("execution(* com.cl.agent.controller..*.*(..))")
    public void controllerPointcut() {
    }

    /**
     * 环绕通知：在目标方法执行前后打印请求入参和响应返回值
     *
     * @param joinPoint 连接点，包含目标方法的签名和参数信息
     * @return 目标方法的返回值
     * @throws Throwable 目标方法抛出的异常，原样向上传递
     */
    @Around("controllerPointcut()")
    public Object logAround(ProceedingJoinPoint joinPoint) throws Throwable {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        // 目标类的简单类名
        String className = joinPoint.getTarget().getClass().getSimpleName();
        // 目标方法名
        String methodName = signature.getName();

        // 获取当前 HTTP 请求的方法和 URI，格式为 [GET /api/xxx]
        String requestInfo = "";
        ServletRequestAttributes attributes =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes != null) {
            HttpServletRequest request = attributes.getRequest();
            requestInfo = String.format("[%s %s] ", request.getMethod(), request.getRequestURI());
        }

        // 将入参数组序列化为 JSON 后打印
        Object[] args = joinPoint.getArgs();
        log.info("{}请求 {}.{} 入参: {}", requestInfo, className, methodName, toJson(args));

        // 记录方法开始执行时间，用于计算耗时
        long startTime = System.currentTimeMillis();
        Object result;
        try {
            result = joinPoint.proceed();
        } catch (Throwable e) {
            log.error("{}请求 {}.{} 异常: {}", requestInfo, className, methodName, e.getMessage(), e);
            throw e;
        }
        // 计算方法执行耗时（毫秒）
        long elapsed = System.currentTimeMillis() - startTime;

        // 将返回值序列化为 JSON 后打印
        log.info("{}请求 {}.{} 返回: {} (耗时 {}ms)", requestInfo, className, methodName, toJson(result), elapsed);

        return result;
    }

    /**
     * 使用 Fastjson2 将对象序列化为 JSON 字符串
     * 若序列化失败则降级为 toString()，避免影响正常请求流程
     *
     * @param obj 待序列化的对象
     * @return JSON 字符串，对象为 null 时返回 "null"
     */
    private String toJson(Object obj) {
        if (obj == null) {
            return "null";
        }
        try {
            return JSON.toJSONString(obj);
        } catch (Exception e) {
            log.warn("日志序列化失败，降级使用 toString(): {}", e.getMessage());
            return obj.toString();
        }
    }
}
