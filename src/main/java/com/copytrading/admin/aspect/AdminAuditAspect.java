package com.copytrading.admin.aspect;

import com.copytrading.admin.model.AdminAuditLog;
import com.copytrading.admin.repository.AdminAuditLogRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Aspect
@Component
public class AdminAuditAspect {

    private static final Logger log = LoggerFactory.getLogger(AdminAuditAspect.class);

    private final AdminAuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;

    public AdminAuditAspect(AdminAuditLogRepository auditLogRepository, ObjectMapper objectMapper) {
        this.auditLogRepository = auditLogRepository;
        this.objectMapper = objectMapper;
    }

    @Around("@annotation(adminAudit)")
    public Object audit(ProceedingJoinPoint joinPoint, AdminAudit adminAudit) throws Throwable {
        Object result = joinPoint.proceed();

        if (result instanceof Mono) {
            return ReactiveSecurityContextHolder.getContext()
                .map(ctx -> ctx.getAuthentication().getPrincipal().toString())
                .flatMap(userIdStr -> {
                    String action = adminAudit.action();
                    String paramsJson = serializeParameters(joinPoint);

                    return ((Mono<?>) result)
                        .flatMap(response -> {
                            AdminAuditLog auditLog = new AdminAuditLog();
                            auditLog.setUserId(UUID.fromString(userIdStr));
                            auditLog.setAction(action);
                            auditLog.setParameters(paramsJson);
                            auditLog.setCreatedAt(Instant.now());

                            return auditLogRepository.save(auditLog)
                                .doOnSuccess(saved -> log.info("Audit log written: action={}, user={}", action, userIdStr))
                                .doOnError(err -> log.error("Failed to write audit log: {}", err.getMessage()))
                                .thenReturn(response);
                        });
                })
                .switchIfEmpty((Mono) result);
        }

        return result;
    }

    private String serializeParameters(ProceedingJoinPoint joinPoint) {
        try {
            MethodSignature signature = (MethodSignature) joinPoint.getSignature();
            Method method = signature.getMethod();
            String[] parameterNames = signature.getParameterNames();
            Object[] args = joinPoint.getArgs();

            Map<String, Object> params = new HashMap<>();
            for (int i = 0; i < args.length; i++) {
                // Skip objects that are not serializable or contain large data streams (like SecurityContext, etc.)
                Object arg = args[i];
                if (arg == null) {
                    continue;
                }
                String name = parameterNames != null && parameterNames.length > i ? parameterNames[i] : "arg" + i;
                params.put(name, sanitizeObject(arg));
            }
            return objectMapper.writeValueAsString(params);
        } catch (Exception e) {
            log.warn("Failed to serialize admin audit log parameters: {}", e.getMessage());
            return "{}";
        }
    }

    private Object sanitizeObject(Object obj) {
        return sanitizeObject(obj, java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()));
    }

    private Object sanitizeObject(Object obj, java.util.Set<Object> visited) {
        if (obj == null) {
            return null;
        }
        if (isSimpleType(obj)) {
            return obj;
        }
        if (!visited.add(obj)) {
            return "[CIRCULAR_REFERENCE]";
        }
        if (obj instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) obj;
            Map<Object, Object> sanitizedMap = new HashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                Object key = entry.getKey();
                Object val = entry.getValue();
                if (key instanceof String) {
                    String keyStr = ((String) key).toLowerCase();
                    if (keyStr.contains("password") || keyStr.contains("secret") || keyStr.contains("token")
                            || keyStr.contains("pwd") || keyStr.contains("totp") || keyStr.contains("auth")) {
                        sanitizedMap.put(key, "[MASKED]");
                    } else {
                        sanitizedMap.put(key, sanitizeObject(val, visited));
                    }
                } else {
                    sanitizedMap.put(key, sanitizeObject(val, visited));
                }
            }
            return sanitizedMap;
        }
        if (obj instanceof java.util.Collection) {
            java.util.Collection<?> collection = (java.util.Collection<?>) obj;
            List<Object> sanitizedList = new ArrayList<>();
            for (Object item : collection) {
                sanitizedList.add(sanitizeObject(item, visited));
            }
            return sanitizedList;
        }
        if (obj.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(obj);
            List<Object> sanitizedList = new ArrayList<>(length);
            for (int i = 0; i < length; i++) {
                Object element = java.lang.reflect.Array.get(obj, i);
                sanitizedList.add(sanitizeObject(element, visited));
            }
            return sanitizedList;
        }
        try {
            Map<String, Object> map = objectMapper.convertValue(obj, Map.class);
            return sanitizeObject(map, visited);
        } catch (Exception e) {
            return obj;
        }
    }

    private boolean isSimpleType(Object obj) {
        if (obj == null) return true;
        Class<?> clazz = obj.getClass();
        return clazz.isPrimitive() || 
               clazz.isEnum() ||
               Number.class.isAssignableFrom(clazz) ||
               clazz == String.class ||
               clazz == Boolean.class ||
               clazz == Character.class ||
               clazz == java.util.UUID.class ||
               java.time.temporal.TemporalAccessor.class.isAssignableFrom(clazz) ||
               clazz == Void.class;
    }
}
