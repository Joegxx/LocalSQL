package io.github.joegxx.localsql.thrift;

import io.github.joegxx.localsql.ir.statement.Statement;

/**
 * 方言前端：SQL 文本 -> IR Statement。产出同一套 IR，方言差异在前端归一，
 * analyzer/rewrite/generator/executor 对方言无感知。
 */
@FunctionalInterface
interface SqlFrontend {
    Statement parse(String sql);
}
