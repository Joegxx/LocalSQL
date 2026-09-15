package io.github.joegxx.localsql.thrift;

import io.github.joegxx.localsql.catalog.Catalog;
import io.github.joegxx.localsql.catalog.CatalogService;

import java.util.List;
import java.util.Map;

/**
 * Full TPC-DS v2.7.0 schema (all 24 tables) as catalog metadata only.
 * Column lists and types come from the standard TPC-DS schema; no
 * physical DuckDB tables are created, so L1 translation tests can bind
 * every column reference without a database file.
 */
final class TpcdsCatalogFixture {

    private TpcdsCatalogFixture() {}

    static void register(CatalogService catalog) throws Exception {
        register(catalog, "call_center", List.of(
                col("cc_call_center_sk", "BIGINT"),
                col("cc_call_center_id", "VARCHAR"),
                col("cc_rec_start_date", "DATE"),
                col("cc_rec_end_date", "DATE"),
                col("cc_closed_date_sk", "BIGINT"),
                col("cc_open_date_sk", "BIGINT"),
                col("cc_name", "VARCHAR"),
                col("cc_class", "VARCHAR"),
                col("cc_employees", "BIGINT"),
                col("cc_sq_ft", "BIGINT"),
                col("cc_hours", "VARCHAR"),
                col("cc_manager", "VARCHAR"),
                col("cc_mkt_id", "BIGINT"),
                col("cc_mkt_class", "VARCHAR"),
                col("cc_mkt_desc", "VARCHAR"),
                col("cc_market_manager", "VARCHAR"),
                col("cc_division", "BIGINT"),
                col("cc_division_name", "VARCHAR"),
                col("cc_company", "BIGINT"),
                col("cc_company_name", "VARCHAR"),
                col("cc_street_number", "VARCHAR"),
                col("cc_street_name", "VARCHAR"),
                col("cc_street_type", "VARCHAR"),
                col("cc_suite_number", "VARCHAR"),
                col("cc_city", "VARCHAR"),
                col("cc_county", "VARCHAR"),
                col("cc_state", "VARCHAR"),
                col("cc_zip", "VARCHAR"),
                col("cc_country", "VARCHAR"),
                col("cc_gmt_offset", "DECIMAL(5,2)"),
                col("cc_tax_percentage", "DECIMAL(5,2)")
        ));
        register(catalog, "catalog_page", List.of(
                col("cp_catalog_page_sk", "BIGINT"),
                col("cp_catalog_page_id", "VARCHAR"),
                col("cp_start_date_sk", "BIGINT"),
                col("cp_end_date_sk", "BIGINT"),
                col("cp_department", "VARCHAR"),
                col("cp_catalog_number", "BIGINT"),
                col("cp_catalog_page_number", "BIGINT"),
                col("cp_description", "VARCHAR"),
                col("cp_type", "VARCHAR")
        ));
        register(catalog, "catalog_returns", List.of(
                col("cr_returned_date_sk", "BIGINT"),
                col("cr_returned_time_sk", "BIGINT"),
                col("cr_item_sk", "BIGINT"),
                col("cr_refunded_customer_sk", "BIGINT"),
                col("cr_refunded_cdemo_sk", "BIGINT"),
                col("cr_refunded_hdemo_sk", "BIGINT"),
                col("cr_refunded_addr_sk", "BIGINT"),
                col("cr_returning_customer_sk", "BIGINT"),
                col("cr_returning_cdemo_sk", "BIGINT"),
                col("cr_returning_hdemo_sk", "BIGINT"),
                col("cr_returning_addr_sk", "BIGINT"),
                col("cr_call_center_sk", "BIGINT"),
                col("cr_catalog_page_sk", "BIGINT"),
                col("cr_ship_mode_sk", "BIGINT"),
                col("cr_warehouse_sk", "BIGINT"),
                col("cr_reason_sk", "BIGINT"),
                col("cr_order_number", "BIGINT"),
                col("cr_return_quantity", "BIGINT"),
                col("cr_return_amount", "DECIMAL(7,2)"),
                col("cr_return_tax", "DECIMAL(7,2)"),
                col("cr_return_amt_inc_tax", "DECIMAL(7,2)"),
                col("cr_fee", "DECIMAL(7,2)"),
                col("cr_return_ship_cost", "DECIMAL(7,2)"),
                col("cr_refunded_cash", "DECIMAL(7,2)"),
                col("cr_reversed_charge", "DECIMAL(7,2)"),
                col("cr_store_credit", "DECIMAL(7,2)"),
                col("cr_net_loss", "DECIMAL(7,2)")
        ));
        register(catalog, "catalog_sales", List.of(
                col("cs_sold_date_sk", "BIGINT"),
                col("cs_sold_time_sk", "BIGINT"),
                col("cs_ship_date_sk", "BIGINT"),
                col("cs_bill_customer_sk", "BIGINT"),
                col("cs_bill_cdemo_sk", "BIGINT"),
                col("cs_bill_hdemo_sk", "BIGINT"),
                col("cs_bill_addr_sk", "BIGINT"),
                col("cs_ship_customer_sk", "BIGINT"),
                col("cs_ship_cdemo_sk", "BIGINT"),
                col("cs_ship_hdemo_sk", "BIGINT"),
                col("cs_ship_addr_sk", "BIGINT"),
                col("cs_call_center_sk", "BIGINT"),
                col("cs_catalog_page_sk", "BIGINT"),
                col("cs_ship_mode_sk", "BIGINT"),
                col("cs_warehouse_sk", "BIGINT"),
                col("cs_item_sk", "BIGINT"),
                col("cs_promo_sk", "BIGINT"),
                col("cs_order_number", "BIGINT"),
                col("cs_quantity", "BIGINT"),
                col("cs_wholesale_cost", "DECIMAL(7,2)"),
                col("cs_list_price", "DECIMAL(7,2)"),
                col("cs_sales_price", "DECIMAL(7,2)"),
                col("cs_ext_discount_amt", "DECIMAL(7,2)"),
                col("cs_ext_sales_price", "DECIMAL(7,2)"),
                col("cs_ext_wholesale_cost", "DECIMAL(7,2)"),
                col("cs_ext_list_price", "DECIMAL(7,2)"),
                col("cs_ext_tax", "DECIMAL(7,2)"),
                col("cs_coupon_amt", "DECIMAL(7,2)"),
                col("cs_ext_ship_cost", "DECIMAL(7,2)"),
                col("cs_net_paid", "DECIMAL(7,2)"),
                col("cs_net_paid_inc_tax", "DECIMAL(7,2)"),
                col("cs_net_paid_inc_ship", "DECIMAL(7,2)"),
                col("cs_net_paid_inc_ship_tax", "DECIMAL(7,2)"),
                col("cs_net_profit", "DECIMAL(7,2)")
        ));
        register(catalog, "customer", List.of(
                col("c_customer_sk", "BIGINT"),
                col("c_customer_id", "VARCHAR"),
                col("c_current_cdemo_sk", "BIGINT"),
                col("c_current_hdemo_sk", "BIGINT"),
                col("c_current_addr_sk", "BIGINT"),
                col("c_first_shipto_date_sk", "BIGINT"),
                col("c_first_sales_date_sk", "BIGINT"),
                col("c_salutation", "VARCHAR"),
                col("c_first_name", "VARCHAR"),
                col("c_last_name", "VARCHAR"),
                col("c_preferred_cust_flag", "VARCHAR"),
                col("c_birth_day", "BIGINT"),
                col("c_birth_month", "BIGINT"),
                col("c_birth_year", "BIGINT"),
                col("c_birth_country", "VARCHAR"),
                col("c_login", "VARCHAR"),
                col("c_email_address", "VARCHAR"),
                col("c_last_review_date_sk", "INTEGER"),
                col("c_last_review_date", "DATE")
        ));
        register(catalog, "customer_address", List.of(
                col("ca_address_sk", "BIGINT"),
                col("ca_address_id", "VARCHAR"),
                col("ca_street_number", "VARCHAR"),
                col("ca_street_name", "VARCHAR"),
                col("ca_street_type", "VARCHAR"),
                col("ca_suite_number", "VARCHAR"),
                col("ca_city", "VARCHAR"),
                col("ca_county", "VARCHAR"),
                col("ca_state", "VARCHAR"),
                col("ca_zip", "VARCHAR"),
                col("ca_country", "VARCHAR"),
                col("ca_gmt_offset", "DECIMAL(5,2)"),
                col("ca_location_type", "VARCHAR")
        ));
        register(catalog, "customer_demographics", List.of(
                col("cd_demo_sk", "BIGINT"),
                col("cd_gender", "VARCHAR"),
                col("cd_marital_status", "VARCHAR"),
                col("cd_education_status", "VARCHAR"),
                col("cd_purchase_estimate", "BIGINT"),
                col("cd_credit_rating", "VARCHAR"),
                col("cd_dep_count", "BIGINT"),
                col("cd_dep_employed_count", "BIGINT"),
                col("cd_dep_college_count", "INTEGER")
        ));
        register(catalog, "date_dim", List.of(
                col("d_date_sk", "BIGINT"),
                col("d_date_id", "VARCHAR"),
                col("d_date", "DATE"),
                col("d_month_seq", "BIGINT"),
                col("d_week_seq", "BIGINT"),
                col("d_quarter_seq", "BIGINT"),
                col("d_year", "BIGINT"),
                col("d_dow", "BIGINT"),
                col("d_moy", "BIGINT"),
                col("d_dom", "BIGINT"),
                col("d_qoy", "BIGINT"),
                col("d_fy_year", "BIGINT"),
                col("d_fy_quarter_seq", "BIGINT"),
                col("d_fy_week_seq", "BIGINT"),
                col("d_day_name", "VARCHAR"),
                col("d_quarter_name", "VARCHAR"),
                col("d_holiday", "VARCHAR"),
                col("d_weekend", "VARCHAR"),
                col("d_following_holiday", "VARCHAR"),
                col("d_first_dom", "BIGINT"),
                col("d_last_dom", "BIGINT"),
                col("d_same_day_ly", "BIGINT"),
                col("d_same_day_lq", "BIGINT"),
                col("d_current_day", "VARCHAR"),
                col("d_current_week", "VARCHAR"),
                col("d_current_month", "VARCHAR"),
                col("d_current_quarter", "VARCHAR"),
                col("d_current_year", "VARCHAR")
        ));
        register(catalog, "household_demographics", List.of(
                col("hd_demo_sk", "BIGINT"),
                col("hd_income_band_sk", "BIGINT"),
                col("hd_buy_potential", "VARCHAR"),
                col("hd_dep_count", "BIGINT"),
                col("hd_vehicle_count", "INTEGER")
        ));
        register(catalog, "income_band", List.of(
                col("ib_income_band_sk", "BIGINT"),
                col("ib_lower_bound", "BIGINT"),
                col("ib_upper_bound", "INTEGER")
        ));
        register(catalog, "inventory", List.of(
                col("inv_date_sk", "BIGINT"),
                col("inv_item_sk", "BIGINT"),
                col("inv_warehouse_sk", "BIGINT"),
                col("inv_quantity_on_hand", "INTEGER")
        ));
        register(catalog, "item", List.of(
                col("i_item_sk", "BIGINT"),
                col("i_item_id", "VARCHAR"),
                col("i_rec_start_date", "DATE"),
                col("i_rec_end_date", "DATE"),
                col("i_item_desc", "VARCHAR"),
                col("i_current_price", "DECIMAL(7,2)"),
                col("i_wholesale_cost", "DECIMAL(7,2)"),
                col("i_brand_id", "BIGINT"),
                col("i_brand", "VARCHAR"),
                col("i_class_id", "BIGINT"),
                col("i_class", "VARCHAR"),
                col("i_category_id", "BIGINT"),
                col("i_category", "VARCHAR"),
                col("i_manufact_id", "BIGINT"),
                col("i_manufact", "VARCHAR"),
                col("i_size", "VARCHAR"),
                col("i_formulation", "VARCHAR"),
                col("i_color", "VARCHAR"),
                col("i_units", "VARCHAR"),
                col("i_container", "VARCHAR"),
                col("i_manager_id", "BIGINT"),
                col("i_product_name", "VARCHAR")
        ));
        register(catalog, "promotion", List.of(
                col("p_promo_sk", "BIGINT"),
                col("p_promo_id", "VARCHAR"),
                col("p_start_date_sk", "BIGINT"),
                col("p_end_date_sk", "BIGINT"),
                col("p_item_sk", "BIGINT"),
                col("p_cost", "DECIMAL(15,2)"),
                col("p_response_target", "BIGINT"),
                col("p_promo_name", "VARCHAR"),
                col("p_channel_dmail", "VARCHAR"),
                col("p_channel_email", "VARCHAR"),
                col("p_channel_catalog", "VARCHAR"),
                col("p_channel_tv", "VARCHAR"),
                col("p_channel_radio", "VARCHAR"),
                col("p_channel_press", "VARCHAR"),
                col("p_channel_event", "VARCHAR"),
                col("p_channel_demo", "VARCHAR"),
                col("p_channel_details", "VARCHAR"),
                col("p_purpose", "VARCHAR"),
                col("p_discount_active", "VARCHAR")
        ));
        register(catalog, "reason", List.of(
                col("r_reason_sk", "BIGINT"),
                col("r_reason_id", "VARCHAR"),
                col("r_reason_desc", "VARCHAR")
        ));
        register(catalog, "ship_mode", List.of(
                col("sm_ship_mode_sk", "BIGINT"),
                col("sm_ship_mode_id", "VARCHAR"),
                col("sm_type", "VARCHAR"),
                col("sm_code", "VARCHAR"),
                col("sm_carrier", "VARCHAR"),
                col("sm_contract", "VARCHAR")
        ));
        register(catalog, "store", List.of(
                col("s_store_sk", "BIGINT"),
                col("s_store_id", "VARCHAR"),
                col("s_rec_start_date", "DATE"),
                col("s_rec_end_date", "DATE"),
                col("s_closed_date_sk", "BIGINT"),
                col("s_store_name", "VARCHAR"),
                col("s_number_employees", "BIGINT"),
                col("s_floor_space", "BIGINT"),
                col("s_hours", "VARCHAR"),
                col("s_manager", "VARCHAR"),
                col("s_market_id", "BIGINT"),
                col("s_geography_class", "VARCHAR"),
                col("s_market_desc", "VARCHAR"),
                col("s_market_manager", "VARCHAR"),
                col("s_division_id", "BIGINT"),
                col("s_division_name", "VARCHAR"),
                col("s_company_id", "BIGINT"),
                col("s_company_name", "VARCHAR"),
                col("s_street_number", "VARCHAR"),
                col("s_street_name", "VARCHAR"),
                col("s_street_type", "VARCHAR"),
                col("s_suite_number", "VARCHAR"),
                col("s_city", "VARCHAR"),
                col("s_county", "VARCHAR"),
                col("s_state", "VARCHAR"),
                col("s_zip", "VARCHAR"),
                col("s_country", "VARCHAR"),
                col("s_gmt_offset", "DECIMAL(5,2)"),
                col("s_tax_percentage", "DECIMAL(5,2)")
        ));
        register(catalog, "store_returns", List.of(
                col("sr_returned_date_sk", "BIGINT"),
                col("sr_return_time_sk", "BIGINT"),
                col("sr_item_sk", "BIGINT"),
                col("sr_customer_sk", "BIGINT"),
                col("sr_cdemo_sk", "BIGINT"),
                col("sr_hdemo_sk", "BIGINT"),
                col("sr_addr_sk", "BIGINT"),
                col("sr_store_sk", "BIGINT"),
                col("sr_reason_sk", "BIGINT"),
                col("sr_ticket_number", "BIGINT"),
                col("sr_return_quantity", "BIGINT"),
                col("sr_return_amt", "DECIMAL(7,2)"),
                col("sr_return_tax", "DECIMAL(7,2)"),
                col("sr_return_amt_inc_tax", "DECIMAL(7,2)"),
                col("sr_fee", "DECIMAL(7,2)"),
                col("sr_return_ship_cost", "DECIMAL(7,2)"),
                col("sr_refunded_cash", "DECIMAL(7,2)"),
                col("sr_reversed_charge", "DECIMAL(7,2)"),
                col("sr_store_credit", "DECIMAL(7,2)"),
                col("sr_net_loss", "DECIMAL(7,2)")
        ));
        register(catalog, "store_sales", List.of(
                col("ss_sold_date_sk", "BIGINT"),
                col("ss_sold_time_sk", "BIGINT"),
                col("ss_item_sk", "BIGINT"),
                col("ss_customer_sk", "BIGINT"),
                col("ss_cdemo_sk", "BIGINT"),
                col("ss_hdemo_sk", "BIGINT"),
                col("ss_addr_sk", "BIGINT"),
                col("ss_store_sk", "BIGINT"),
                col("ss_promo_sk", "BIGINT"),
                col("ss_ticket_number", "BIGINT"),
                col("ss_quantity", "BIGINT"),
                col("ss_wholesale_cost", "DECIMAL(7,2)"),
                col("ss_list_price", "DECIMAL(7,2)"),
                col("ss_sales_price", "DECIMAL(7,2)"),
                col("ss_ext_discount_amt", "DECIMAL(7,2)"),
                col("ss_ext_sales_price", "DECIMAL(7,2)"),
                col("ss_ext_wholesale_cost", "DECIMAL(7,2)"),
                col("ss_ext_list_price", "DECIMAL(7,2)"),
                col("ss_ext_tax", "DECIMAL(7,2)"),
                col("ss_coupon_amt", "DECIMAL(7,2)"),
                col("ss_net_paid", "DECIMAL(7,2)"),
                col("ss_net_paid_inc_tax", "DECIMAL(7,2)"),
                col("ss_net_profit", "DECIMAL(7,2)")
        ));
        register(catalog, "time_dim", List.of(
                col("t_time_sk", "BIGINT"),
                col("t_time_id", "VARCHAR"),
                col("t_time", "BIGINT"),
                col("t_hour", "BIGINT"),
                col("t_minute", "BIGINT"),
                col("t_second", "BIGINT"),
                col("t_am_pm", "VARCHAR"),
                col("t_shift", "VARCHAR"),
                col("t_sub_shift", "VARCHAR"),
                col("t_meal_time", "VARCHAR")
        ));
        register(catalog, "warehouse", List.of(
                col("w_warehouse_sk", "BIGINT"),
                col("w_warehouse_id", "VARCHAR"),
                col("w_warehouse_name", "VARCHAR"),
                col("w_warehouse_sq_ft", "BIGINT"),
                col("w_street_number", "VARCHAR"),
                col("w_street_name", "VARCHAR"),
                col("w_street_type", "VARCHAR"),
                col("w_suite_number", "VARCHAR"),
                col("w_city", "VARCHAR"),
                col("w_county", "VARCHAR"),
                col("w_state", "VARCHAR"),
                col("w_zip", "VARCHAR"),
                col("w_country", "VARCHAR"),
                col("w_gmt_offset", "DECIMAL(5,2)")
        ));
        register(catalog, "web_page", List.of(
                col("wp_web_page_sk", "BIGINT"),
                col("wp_web_page_id", "VARCHAR"),
                col("wp_rec_start_date", "DATE"),
                col("wp_rec_end_date", "DATE"),
                col("wp_creation_date_sk", "BIGINT"),
                col("wp_access_date_sk", "BIGINT"),
                col("wp_autogen_flag", "VARCHAR"),
                col("wp_customer_sk", "BIGINT"),
                col("wp_url", "VARCHAR"),
                col("wp_type", "VARCHAR"),
                col("wp_char_count", "BIGINT"),
                col("wp_link_count", "BIGINT"),
                col("wp_image_count", "BIGINT"),
                col("wp_max_ad_count", "INTEGER")
        ));
        register(catalog, "web_returns", List.of(
                col("wr_returned_date_sk", "BIGINT"),
                col("wr_returned_time_sk", "BIGINT"),
                col("wr_item_sk", "BIGINT"),
                col("wr_refunded_customer_sk", "BIGINT"),
                col("wr_refunded_cdemo_sk", "BIGINT"),
                col("wr_refunded_hdemo_sk", "BIGINT"),
                col("wr_refunded_addr_sk", "BIGINT"),
                col("wr_returning_customer_sk", "BIGINT"),
                col("wr_returning_cdemo_sk", "BIGINT"),
                col("wr_returning_hdemo_sk", "BIGINT"),
                col("wr_returning_addr_sk", "BIGINT"),
                col("wr_web_page_sk", "BIGINT"),
                col("wr_reason_sk", "BIGINT"),
                col("wr_order_number", "BIGINT"),
                col("wr_return_quantity", "BIGINT"),
                col("wr_return_amt", "DECIMAL(7,2)"),
                col("wr_return_tax", "DECIMAL(7,2)"),
                col("wr_return_amt_inc_tax", "DECIMAL(7,2)"),
                col("wr_fee", "DECIMAL(7,2)"),
                col("wr_return_ship_cost", "DECIMAL(7,2)"),
                col("wr_refunded_cash", "DECIMAL(7,2)"),
                col("wr_reversed_charge", "DECIMAL(7,2)"),
                col("wr_account_credit", "DECIMAL(7,2)"),
                col("wr_net_loss", "DECIMAL(7,2)")
        ));
        register(catalog, "web_sales", List.of(
                col("ws_sold_date_sk", "BIGINT"),
                col("ws_sold_time_sk", "BIGINT"),
                col("ws_ship_date_sk", "BIGINT"),
                col("ws_item_sk", "BIGINT"),
                col("ws_bill_customer_sk", "BIGINT"),
                col("ws_bill_cdemo_sk", "BIGINT"),
                col("ws_bill_hdemo_sk", "BIGINT"),
                col("ws_bill_addr_sk", "BIGINT"),
                col("ws_ship_customer_sk", "BIGINT"),
                col("ws_ship_cdemo_sk", "BIGINT"),
                col("ws_ship_hdemo_sk", "BIGINT"),
                col("ws_ship_addr_sk", "BIGINT"),
                col("ws_web_page_sk", "BIGINT"),
                col("ws_web_site_sk", "BIGINT"),
                col("ws_ship_mode_sk", "BIGINT"),
                col("ws_warehouse_sk", "BIGINT"),
                col("ws_promo_sk", "BIGINT"),
                col("ws_order_number", "BIGINT"),
                col("ws_quantity", "BIGINT"),
                col("ws_wholesale_cost", "DECIMAL(7,2)"),
                col("ws_list_price", "DECIMAL(7,2)"),
                col("ws_sales_price", "DECIMAL(7,2)"),
                col("ws_ext_discount_amt", "DECIMAL(7,2)"),
                col("ws_ext_sales_price", "DECIMAL(7,2)"),
                col("ws_ext_wholesale_cost", "DECIMAL(7,2)"),
                col("ws_ext_list_price", "DECIMAL(7,2)"),
                col("ws_ext_tax", "DECIMAL(7,2)"),
                col("ws_coupon_amt", "DECIMAL(7,2)"),
                col("ws_ext_ship_cost", "DECIMAL(7,2)"),
                col("ws_net_paid", "DECIMAL(7,2)"),
                col("ws_net_paid_inc_tax", "DECIMAL(7,2)"),
                col("ws_net_paid_inc_ship", "DECIMAL(7,2)"),
                col("ws_net_paid_inc_ship_tax", "DECIMAL(7,2)"),
                col("ws_net_profit", "DECIMAL(7,2)")
        ));
        register(catalog, "web_site", List.of(
                col("web_site_sk", "BIGINT"),
                col("web_site_id", "VARCHAR"),
                col("web_rec_start_date", "DATE"),
                col("web_rec_end_date", "DATE"),
                col("web_name", "VARCHAR"),
                col("web_open_date_sk", "BIGINT"),
                col("web_close_date_sk", "BIGINT"),
                col("web_class", "VARCHAR"),
                col("web_manager", "VARCHAR"),
                col("web_mkt_id", "BIGINT"),
                col("web_mkt_class", "VARCHAR"),
                col("web_mkt_desc", "VARCHAR"),
                col("web_market_manager", "VARCHAR"),
                col("web_company_id", "BIGINT"),
                col("web_company_name", "VARCHAR"),
                col("web_street_number", "VARCHAR"),
                col("web_street_name", "VARCHAR"),
                col("web_street_type", "VARCHAR"),
                col("web_suite_number", "VARCHAR"),
                col("web_city", "VARCHAR"),
                col("web_county", "VARCHAR"),
                col("web_state", "VARCHAR"),
                col("web_zip", "VARCHAR"),
                col("web_country", "VARCHAR"),
                col("web_gmt_offset", "DECIMAL(5,2)"),
                col("web_tax_percentage", "DECIMAL(5,2)")
        ));
    }

    private static void register(CatalogService catalog, String table, List<Catalog.Column> columns) throws Exception {
        catalog.registerTable("default", table, "TABLE", columns.stream()
                .map(c -> new CatalogService.ColumnDef(c.name(), c.type(), true, null))
                .toList(), null, "duckdb", null, Map.of(), null);
    }

    private static Catalog.Column col(String name, String type) {
        return new Catalog.Column(name, Catalog.parseDataType(type), true, null, 0, null, null);
    }
}
