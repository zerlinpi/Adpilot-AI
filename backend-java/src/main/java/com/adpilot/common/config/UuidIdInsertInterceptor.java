package com.adpilot.common.config;

import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.springframework.stereotype.Component;

import java.lang.reflect.Field;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * Ensures every entity inserted via MyBatis-Plus gets a {@code java.util.UUID}
 * primary key when one is not already set.
 *
 * <p>MyBatis-Plus's built-in id strategies don't fit {@code UUID} fields:
 * {@code assign_uuid} produces a String (type mismatch) and {@code input}
 * leaves the id null (NOT NULL violation). This executor-level interceptor
 * fills a null {@code UUID id} field before the INSERT runs — globally, for all
 * entities, without per-entity annotations.</p>
 */
@Component
@Intercepts({
        @Signature(type = Executor.class, method = "update",
                args = {MappedStatement.class, Object.class})
})
public class UuidIdInsertInterceptor implements Interceptor {

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        Object[] args = invocation.getArgs();
        MappedStatement ms = (MappedStatement) args[0];
        Object parameter = args[1];

        if (ms.getSqlCommandType() == SqlCommandType.INSERT && parameter != null) {
            fillIds(parameter);
        }
        return invocation.proceed();
    }

    private void fillIds(Object parameter) {
        // MyBatis wraps multi-params / batch inserts in a Map or Collection.
        if (parameter instanceof Map<?, ?> map) {
            for (Object v : map.values()) {
                fillIfEntity(v);
            }
        } else if (parameter instanceof Collection<?> coll) {
            for (Object v : coll) {
                fillIfEntity(v);
            }
        } else {
            fillIfEntity(parameter);
        }
    }

    private void fillIfEntity(Object entity) {
        if (entity == null) return;
        if (entity instanceof Collection<?> coll) {
            coll.forEach(this::fillIfEntity);
            return;
        }
        Class<?> type = entity.getClass();
        String pkg = type.getPackageName();
        if (!pkg.startsWith("com.adpilot")) {
            return;
        }
        try {
            Field idField = findIdField(type);
            if (idField == null) return;
            if (!UUID.class.equals(idField.getType())) return;
            idField.setAccessible(true);
            if (idField.get(entity) == null) {
                idField.set(entity, UUID.randomUUID());
            }
        } catch (Exception ignored) {
            // Never block the insert because of id-fill reflection issues.
        }
    }

    private Field findIdField(Class<?> type) {
        Class<?> c = type;
        while (c != null && c != Object.class) {
            try {
                return c.getDeclaredField("id");
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        return null;
    }
}
