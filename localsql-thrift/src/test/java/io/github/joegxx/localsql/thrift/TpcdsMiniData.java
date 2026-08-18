package io.github.joegxx.localsql.thrift;

import io.github.joegxx.localsql.catalog.CatalogService;
import io.github.joegxx.localsql.duckdb.DuckDbExecutor;
import io.github.joegxx.localsql.ir.type.FractionalType;
import io.github.joegxx.localsql.ir.type.IntegralType;
import io.github.joegxx.localsql.ir.type.StringType;

import java.util.List;
import java.util.Map;

/**
 * Deterministic mini TPC-DS dataset. Small enough to derive expected results by
 * hand (Spark semantics), with deliberate distractor rows so joins/filters are
 * actually exercised. Registers physical DuckDB tables and catalog metadata.
 */
final class TpcdsMiniData {

    static void register(CatalogService catalog, DuckDbExecutor executor) throws Exception {
        dateDim(catalog, executor);
        item(catalog, executor);
        storeSales(catalog, executor);
        customer(catalog, executor);
        customerAddress(catalog, executor);
        catalogSales(catalog, executor);
        warehouse(catalog, executor);
        inventory(catalog, executor);
    }

    private static void dateDim(CatalogService catalog, DuckDbExecutor executor) throws Exception {
        executor.registerTable("date_dim",
                List.of(new DuckDbExecutor.ColDef("d_date_sk", "BIGINT"),
                        new DuckDbExecutor.ColDef("d_year", "INT"),
                        new DuckDbExecutor.ColDef("d_moy", "INT"),
                        new DuckDbExecutor.ColDef("d_qoy", "INT"),
                        new DuckDbExecutor.ColDef("d_month_seq", "INT"),
                        new DuckDbExecutor.ColDef("d_date", "DATE")),
                List.<List<Object>>of(
                        List.of(1L, 1999, 11, 4, 1200, "1999-02-22"),
                        List.of(2L, 2000, 11, 4, 1201, "1999-03-10"),
                        List.of(3L, 2001, 2, 2, 9999, "2001-02-01"),
                        List.of(4L, 2000, 12, 4, 9999, "2000-12-05")));
        catalog.registerTable("default", "date_dim", "TABLE",
                List.of(new CatalogService.ColumnDef("d_date_sk", IntegralType.BIGINT, false, null),
                        new CatalogService.ColumnDef("d_year", IntegralType.INT, true, null),
                        new CatalogService.ColumnDef("d_moy", IntegralType.INT, true, null),
                        new CatalogService.ColumnDef("d_qoy", IntegralType.INT, true, null),
                        new CatalogService.ColumnDef("d_month_seq", IntegralType.INT, true, null),
                        new CatalogService.ColumnDef("d_date", new io.github.joegxx.localsql.ir.type.DateType(), true, null)),
                null, "duckdb", null, Map.of(), null);
    }

    private static void item(CatalogService catalog, DuckDbExecutor executor) throws Exception {
        executor.registerTable("item",
                List.of(new DuckDbExecutor.ColDef("i_item_sk", "BIGINT"),
                        new DuckDbExecutor.ColDef("i_item_id", "VARCHAR"),
                        new DuckDbExecutor.ColDef("i_item_desc", "VARCHAR"),
                        new DuckDbExecutor.ColDef("i_current_price", "DOUBLE"),
                        new DuckDbExecutor.ColDef("i_product_name", "VARCHAR"),
                        new DuckDbExecutor.ColDef("i_brand_id", "INT"),
                        new DuckDbExecutor.ColDef("i_brand", "VARCHAR"),
                        new DuckDbExecutor.ColDef("i_class", "VARCHAR"),
                        new DuckDbExecutor.ColDef("i_manufact_id", "INT"),
                        new DuckDbExecutor.ColDef("i_category_id", "INT"),
                        new DuckDbExecutor.ColDef("i_category", "VARCHAR"),
                        new DuckDbExecutor.ColDef("i_manager_id", "INT")),
                List.<List<Object>>of(
                        List.of(10L, "I10", "descA", 10.0, "P1", 100, "brandA", "B1", 128, 1, "Books", 1),
                        List.of(11L, "I11", "descB", 20.0, "P2", 101, "brandB", "B2", 128, 2, "Sports", 1),
                        List.of(12L, "I12", "descC", 30.0, "P3", 102, "brandC", "B3", 200, 3, "Home", 2)));
        catalog.registerTable("default", "item", "TABLE",
                List.of(new CatalogService.ColumnDef("i_item_sk", IntegralType.BIGINT, false, null),
                        new CatalogService.ColumnDef("i_item_id", new StringType(), true, null),
                        new CatalogService.ColumnDef("i_item_desc", new StringType(), true, null),
                        new CatalogService.ColumnDef("i_current_price", FractionalType.DOUBLE, true, null),
                        new CatalogService.ColumnDef("i_product_name", new StringType(), true, null),
                        new CatalogService.ColumnDef("i_brand_id", IntegralType.INT, true, null),
                        new CatalogService.ColumnDef("i_brand", new StringType(), true, null),
                        new CatalogService.ColumnDef("i_class", new StringType(), true, null),
                        new CatalogService.ColumnDef("i_manufact_id", IntegralType.INT, true, null),
                        new CatalogService.ColumnDef("i_category_id", IntegralType.INT, true, null),
                        new CatalogService.ColumnDef("i_category", new StringType(), true, null),
                        new CatalogService.ColumnDef("i_manager_id", IntegralType.INT, true, null)),
                null, "duckdb", null, Map.of(), null);
    }

    private static void storeSales(CatalogService catalog, DuckDbExecutor executor) throws Exception {
        executor.registerTable("store_sales",
                List.of(new DuckDbExecutor.ColDef("ss_sold_date_sk", "BIGINT"),
                        new DuckDbExecutor.ColDef("ss_item_sk", "BIGINT"),
                        new DuckDbExecutor.ColDef("ss_ext_sales_price", "DOUBLE")),
                List.<List<Object>>of(
                        List.of(1L, 10L, 10.50),
                        List.of(1L, 10L, 20.00),
                        List.of(1L, 11L, 5.00),
                        List.of(2L, 10L, 7.25),
                        List.of(4L, 12L, 99.99),   // d_moy=12 distractor
                        List.of(3L, 10L, 300.00))); // d_moy=2 distractor
        catalog.registerTable("default", "store_sales", "TABLE",
                List.of(new CatalogService.ColumnDef("ss_sold_date_sk", IntegralType.BIGINT, true, null),
                        new CatalogService.ColumnDef("ss_item_sk", IntegralType.BIGINT, true, null),
                        new CatalogService.ColumnDef("ss_ext_sales_price", FractionalType.DOUBLE, true, null)),
                null, "duckdb", null, Map.of(), null);
    }

    private static void customer(CatalogService catalog, DuckDbExecutor executor) throws Exception {
        executor.registerTable("customer",
                List.of(new DuckDbExecutor.ColDef("c_customer_sk", "BIGINT"),
                        new DuckDbExecutor.ColDef("c_current_addr_sk", "BIGINT")),
                List.<List<Object>>of(
                        List.of(1L, 101L),
                        List.of(2L, 102L)));
        catalog.registerTable("default", "customer", "TABLE",
                List.of(new CatalogService.ColumnDef("c_customer_sk", IntegralType.BIGINT, false, null),
                        new CatalogService.ColumnDef("c_current_addr_sk", IntegralType.BIGINT, true, null)),
                null, "duckdb", null, Map.of(), null);
    }

    private static void customerAddress(CatalogService catalog, DuckDbExecutor executor) throws Exception {
        executor.registerTable("customer_address",
                List.of(new DuckDbExecutor.ColDef("ca_address_sk", "BIGINT"),
                        new DuckDbExecutor.ColDef("ca_zip", "VARCHAR"),
                        new DuckDbExecutor.ColDef("ca_state", "VARCHAR")),
                List.<List<Object>>of(
                        List.of(101L, "85669", "NY"),
                        List.of(102L, "99999", "CA")));
        catalog.registerTable("default", "customer_address", "TABLE",
                List.of(new CatalogService.ColumnDef("ca_address_sk", IntegralType.BIGINT, false, null),
                        new CatalogService.ColumnDef("ca_zip", new StringType(), true, null),
                        new CatalogService.ColumnDef("ca_state", new StringType(), true, null)),
                null, "duckdb", null, Map.of(), null);
    }

    private static void catalogSales(CatalogService catalog, DuckDbExecutor executor) throws Exception {
        executor.registerTable("catalog_sales",
                List.of(new DuckDbExecutor.ColDef("cs_sold_date_sk", "BIGINT"),
                        new DuckDbExecutor.ColDef("cs_bill_customer_sk", "BIGINT"),
                        new DuckDbExecutor.ColDef("cs_sales_price", "DOUBLE")),
                List.<List<Object>>of(
                        List.of(3L, 1L, 400.00),  // matches zip-prefix branch
                        List.of(3L, 2L, 600.00),  // matches state branch (and >500)
                        List.of(3L, 2L, 100.00),  // matches state branch
                        List.of(1L, 1L, 700.00))); // >500 but wrong date (distractor)
        catalog.registerTable("default", "catalog_sales", "TABLE",
                List.of(new CatalogService.ColumnDef("cs_sold_date_sk", IntegralType.BIGINT, true, null),
                        new CatalogService.ColumnDef("cs_bill_customer_sk", IntegralType.BIGINT, true, null),
                        new CatalogService.ColumnDef("cs_sales_price", FractionalType.DOUBLE, true, null)),
                null, "duckdb", null, Map.of(), null);
    }

    private static void warehouse(CatalogService catalog, DuckDbExecutor executor) throws Exception {
        executor.registerTable("warehouse",
                List.of(new DuckDbExecutor.ColDef("w_warehouse_sk", "BIGINT")),
                List.<List<Object>>of(List.of(1L)));
        catalog.registerTable("default", "warehouse", "TABLE",
                List.of(new CatalogService.ColumnDef("w_warehouse_sk", IntegralType.BIGINT, false, null)),
                null, "duckdb", null, Map.of(), null);
    }

    private static void inventory(CatalogService catalog, DuckDbExecutor executor) throws Exception {
        executor.registerTable("inventory",
                List.of(new DuckDbExecutor.ColDef("inv_date_sk", "BIGINT"),
                        new DuckDbExecutor.ColDef("inv_item_sk", "BIGINT"),
                        new DuckDbExecutor.ColDef("inv_warehouse_sk", "BIGINT"),
                        new DuckDbExecutor.ColDef("inv_quantity_on_hand", "INT")),
                List.<List<Object>>of(
                        List.of(1L, 10L, 1L, 10),
                        List.of(1L, 10L, 1L, 20),
                        List.of(2L, 10L, 1L, 30),
                        List.of(1L, 11L, 1L, 40)));
        catalog.registerTable("default", "inventory", "TABLE",
                List.of(new CatalogService.ColumnDef("inv_date_sk", IntegralType.BIGINT, true, null),
                        new CatalogService.ColumnDef("inv_item_sk", IntegralType.BIGINT, true, null),
                        new CatalogService.ColumnDef("inv_warehouse_sk", IntegralType.BIGINT, true, null),
                        new CatalogService.ColumnDef("inv_quantity_on_hand", IntegralType.INT, true, null)),
                null, "duckdb", null, Map.of(), null);
    }

    private TpcdsMiniData() {}
}
