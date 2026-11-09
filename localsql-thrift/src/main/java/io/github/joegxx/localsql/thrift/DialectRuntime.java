package io.github.joegxx.localsql.thrift;

import io.github.joegxx.localsql.analyzer.SemanticAnalyzer;
import io.github.joegxx.localsql.rewrite.RewriteEngine;

/**
 * 一个方言的完整运行时：前端 + analyzer + rewrite 必须成对注册、一起变更，
 * 避免三个并行字段各自漂移（例如 Spark 会话意外获得 StarRocks 专属函数降级）。
 */
record DialectRuntime(SqlFrontend frontend, SemanticAnalyzer analyzer, RewriteEngine rewriter) {
}
