package io.github.joegxx.localsql.ir.relation;

import io.github.joegxx.localsql.ir.expression.AttributeReference;
import io.github.joegxx.localsql.ir.expression.Expression;

import java.util.List;

public final class Project extends Relation {
    private final Relation child;
    private final List<Expression> projectList;
    private final boolean distinct;
    private List<AttributeReference> output;

    public Project(Relation child, List<Expression> projectList) {
        this(child, projectList, false);
    }

    public Project(Relation child, List<Expression> projectList, boolean distinct) {
        this.child = child;
        this.projectList = List.copyOf(projectList);
        this.distinct = distinct;
    }

    public Relation child() { return child; }
    public List<Expression> projectList() { return projectList; }
    public boolean distinct() { return distinct; }

    /**
     * The projection's output columns. Computed by the analyzer from the
     * select list; before analysis this falls back to the child output
     * (unparsed trees must still be printable/walkable).
     */
    @Override
    public List<AttributeReference> output() {
        return output != null ? output : child.output();
    }

    /** Set by the analyzer; must cover every select item exactly once. */
    public void setOutput(List<AttributeReference> output) { this.output = List.copyOf(output); }

    @Override
    public String toString() {
        return (distinct ? "ProjectDistinct[" : "Project[") + projectList + "](" + child + ")";
    }
}