package io.github.joegxx.localsql.thrift;

import java.util.Locale;
import java.util.Optional;

/**
 * SQL 方言。连接时经 session conf 指定（JDBC URL session var），会话内不可切换。
 * 每个方言在 SqlPipeline 中对应一个 DialectRuntime（前端 + analyzer + rewrite）。
 */
enum Dialect {
    SPARK,
    STARROCKS;

    /**
     * hive-jdbc 会把 session var 以多种 key 形态放进 TOpenSessionReq.configuration
     * （裸名 / set:hiveconf: / set:var:），三种都接受。未知值返回 empty，由调用方
     * 告警并回落 SPARK，绝不静默误配。
     */
    static Optional<Dialect> fromSessionValue(String value) {
        if (value == null || value.isBlank()) return Optional.empty();
        switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "spark": return Optional.of(SPARK);
            case "starrocks": return Optional.of(STARROCKS);
            default: return Optional.empty();
        }
    }
}
