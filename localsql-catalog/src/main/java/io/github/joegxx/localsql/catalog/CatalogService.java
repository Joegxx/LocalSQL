package io.github.joegxx.localsql.catalog;

import io.github.joegxx.localsql.ir.type.StringType;

import java.util.List;

public final class CatalogService {

    private final Catalog catalog = new Catalog();

    public Catalog catalog() { return catalog; }

    public void registerSampleTable(String name, String... columns) {
        var colList = new java.util.ArrayList<Catalog.Column>();
        for (String c : columns) colList.add(new Catalog.Column(c, new io.github.joegxx.localsql.ir.type.StringType(), true, null));
        catalog.createTable(new Catalog.Table(List.of("default", name), colList, null, "parquet"));
    }
}
