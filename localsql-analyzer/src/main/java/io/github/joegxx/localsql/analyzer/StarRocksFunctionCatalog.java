package io.github.joegxx.localsql.analyzer;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * StarRocks 会话的函数元数据目录 = Spark 共享名单（BuiltInFunctionCatalog，
 * 两个方言共用同一套翻译）∪ StarRocks 专属名单（仅 StarRocks 会话可绑定，
 * 对应 Rewrite 的方言作用域表）。
 *
 * <p>碰撞纪律：进入专属名单的名字必须不存在于 Spark 函数词汇表——否则 StarRocks
 * 专属降级会绕过共享表的语义。新增条目时须核对 BuiltInFunctionCatalog。
 */
public final class StarRocksFunctionCatalog implements FunctionCatalog {

    private static final List<String> NO_ALIASES = List.of();
    private static final int MAX = Integer.MAX_VALUE;

    private static final Map<String, FunctionInfo> EXTRA = new HashMap<>();

    static {
        agg("group_concat");
        scalar("get_json_string");
        scalar("get_json_int");
        scalar("get_json_double");
        scalar("str_to_date");
        scalar("curdate");
    }

    private static void scalar(String name) {
        EXTRA.put(name, new FunctionInfo(name, NO_ALIASES, 0, MAX, FunctionKind.SCALAR));
    }

    private static void agg(String name) {
        EXTRA.put(name, new FunctionInfo(name, NO_ALIASES, 0, MAX, FunctionKind.AGGREGATE));
    }

    @Override
    public Optional<FunctionInfo> lookup(String name) {
        if (name == null) return Optional.empty();
        String lower = name.toLowerCase(Locale.ROOT);
        Optional<FunctionInfo> own = Optional.ofNullable(EXTRA.get(lower));
        if (own.isPresent()) return own;
        return BuiltInFunctionCatalog.instance().lookup(lower);
    }

    public static StarRocksFunctionCatalog instance() { return new StarRocksFunctionCatalog(); }
}
