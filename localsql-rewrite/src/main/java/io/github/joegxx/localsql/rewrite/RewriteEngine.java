package io.github.joegxx.localsql.rewrite;

import io.github.joegxx.localsql.ir.expression.*;
import io.github.joegxx.localsql.ir.relation.*;
import io.github.joegxx.localsql.ir.visitor.IrVisitor;

import java.util.List;

public final class RewriteEngine {

    public Relation rewrite(Relation rel) {
        RewriteVisitor v = new RewriteVisitor();
        v.visitRelation(rel, null);
        return rel;
    }

    private static final class RewriteVisitor extends IrVisitor<Void, Void> {

        @Override
        public Void visitFunctionCall(FunctionCall e, Void ctx) {
            String name = e.name().toLowerCase();
            switch (name) {
                case "size" -> rename(e, "array_length");
                case "explode" -> rename(e, "unnest");
                case "posexplode" -> rename(e, "unnest");
                case "instr" -> {}
                case "substr", "substring" -> {}
                case "length" -> {}
                case "concat" -> {}
                case "coalesce" -> {}
                case "if" -> {}
                case "date_format" -> {}
                case "to_date" -> {}
                case "unix_timestamp" -> {}
                case "from_unixtime" -> {}
                case "get_json_object" -> {}
                case "json_extract" -> {}
                default -> {}
            }
            return super.visitFunctionCall(e, ctx);
        }

        private void rename(FunctionCall e, String newName) {
            e.rename(newName);
        }
    }
}
