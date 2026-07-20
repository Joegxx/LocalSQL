package io.github.joegxx.localsql.ir.relation;

import io.github.joegxx.localsql.ir.expression.AttributeReference;
import io.github.joegxx.localsql.ir.expression.Expression;

import java.util.List;

public final class Project extends Relation {
    private final Relation child;
    private final List<Expression> projectList;

    public Project(Relation child, List<Expression> projectList) {
        this.child = child;
        this.projectList = List.copyOf(projectList);
    }

    public Relation child() { return child; }
    public List<Expression> projectList() { return projectList; }

    @Override
    public List<AttributeReference> output() { return child.output(); }

    @Override
    public String toString() { return "Project[" + projectList + "](" + child + ")"; }
}
