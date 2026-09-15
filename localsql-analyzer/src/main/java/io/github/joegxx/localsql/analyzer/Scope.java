package io.github.joegxx.localsql.analyzer;

import io.github.joegxx.localsql.ir.expression.AttributeReference;
import io.github.joegxx.localsql.ir.relation.CTERelation;
import io.github.joegxx.localsql.ir.relation.Relation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Layered name-resolution scope for one SELECT block.
 *
 * <p>Layers, from innermost to outermost:
 * <ol>
 *   <li>FROM-clause relations of the current block, each with its visible
 *       qualifier (alias, or table name) and output columns</li>
 *   <li>CTE names visible in this block (definitions resolved in their own scope)</li>
 *   <li>the parent scope, for correlated subqueries and outer WITH</li>
 * </ol>
 *
 * <p>Unqualified column names resolve against the FROM relations of this
 * scope only (never the catalog); qualified names {@code q.c} must find a
 * relation with qualifier {@code q}. A name matching more than one relation
 * output is ambiguous and fails resolution.
 */
final class Scope {

    /** One visible FROM relation: addressable under any of its qualifiers. */
    record VisibleRelation(List<String> qualifiers, List<AttributeReference> output) {}

    private final Scope parent;
    private final List<VisibleRelation> relations = new ArrayList<>();
    private final Map<String, CTERelation> ctes = new LinkedHashMap<>();
    private final String sessionDatabase;

    Scope(Scope parent, String sessionDatabase) {
        this.parent = parent;
        this.sessionDatabase = sessionDatabase;
    }

    Scope parent() { return parent; }

    String sessionDatabase() { return sessionDatabase; }

    /** Registers a relation visible under one or more qualifiers (alias and/or table name). */
    void addRelation(List<String> qualifiers, List<AttributeReference> output) {
        relations.add(new VisibleRelation(qualifiers, output));
    }

    void addCte(CTERelation cte) {
        ctes.put(cte.name().toLowerCase(), cte);
    }

    CTERelation findCte(String name) {
        Scope s = this;
        while (s != null) {
            CTERelation cte = s.ctes.get(name.toLowerCase());
            if (cte != null) return cte;
            s = s.parent;
        }
        return null;
    }

    /** All visible relations in this scope (not the parent's FROM list). */
    List<VisibleRelation> relations() { return relations; }

    /**
     * Resolve an unqualified column against this scope's relations; falls
     * back to the parent scope (correlated reference). Returns null when the
     * name is not visible anywhere on the chain.
     */
    AttributeReference resolveColumn(String name) {
        List<AttributeReference> hits = new ArrayList<>();
        for (VisibleRelation r : relations) {
            for (AttributeReference a : r.output()) {
                if (a.name().equalsIgnoreCase(name)) hits.add(a);
            }
        }
        if (hits.size() > 1) {
            throw new IllegalArgumentException(
                    "Column reference '" + name + "' is ambiguous: matches " + hits.size()
                            + " columns in the FROM clause");
        }
        if (hits.size() == 1) return hits.get(0);
        return parent != null ? parent.resolveColumn(name) : null;
    }

    /**
     * Resolve a qualified column {@code qualifier.name}. The qualifier must
     * match one of this scope's relations (alias, table name, or a CTE
     * name); falls back to the parent scope for correlated references.
     */
    AttributeReference resolveQualified(String qualifier, String name) {
        List<AttributeReference> hits = new ArrayList<>();
        for (VisibleRelation r : relations) {
            boolean matches = r.qualifiers().stream().anyMatch(q -> q.equalsIgnoreCase(qualifier));
            if (!matches) continue;
            for (AttributeReference a : r.output()) {
                if (a.name().equalsIgnoreCase(name)) hits.add(a);
            }
        }
        if (hits.size() > 1) {
            throw new IllegalArgumentException(
                    "Column reference '" + qualifier + "." + name + "' is ambiguous: matches "
                            + hits.size() + " columns");
        }
        if (hits.size() == 1) return hits.get(0);
        return parent != null ? parent.resolveQualified(qualifier, name) : null;
    }

    /**
     * All columns visible in this scope, in registration order (for Star
     * expansion). Duplicates across relations are kept: SELECT * over a join
     * exposes every column, including same-named ones. Parent scopes are NOT
     * consulted — a star never expands to outer query columns.
     */
    List<AttributeReference> allColumns() {
        List<AttributeReference> out = new ArrayList<>();
        for (VisibleRelation r : relations) out.addAll(r.output());
        return out;
    }

    /**
     * All columns of the relation addressed by {@code qualifier} (for
     * {@code q.*} expansion), or null when the qualifier is not visible here.
     */
    List<AttributeReference> columnsOfQualifier(String qualifier) {
        for (VisibleRelation r : relations) {
            if (r.qualifiers().stream().anyMatch(q -> q.equalsIgnoreCase(qualifier))) {
                return r.output();
            }
        }
        return null;
    }

    /** Whether the qualifier matches a relation in this scope (or a parent's). */
    boolean hasQualifier(String qualifier) {
        for (VisibleRelation r : relations) {
            if (r.qualifiers().stream().anyMatch(q -> q.equalsIgnoreCase(qualifier))) return true;
        }
        return parent != null && parent.hasQualifier(qualifier);
    }
}
