package com.viyangle.study_tour.utils;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

public final class RouteOutdatedAttractionStatusUpdater {

    private static final String COLUMN_NAME = "contains_outdated_attractions";

    private RouteOutdatedAttractionStatusUpdater() {
    }

    public static void ensureColumn(Connection connection) throws Exception {
        try (ResultSet rs = connection.getMetaData()
                .getColumns(connection.getCatalog(), null, "routes", COLUMN_NAME)) {
            if (rs.next()) {
                return;
            }
        }

        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("ALTER TABLE routes ADD COLUMN " + COLUMN_NAME
                    + " TINYINT(1) NOT NULL DEFAULT 0 COMMENT 'Whether the route contains stale attractions' AFTER tag");
        }
    }

    public static int refreshAll(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            return statement.executeUpdate(refreshSql());
        }
    }

    static String refreshSql() {
        return """
                UPDATE routes r
                SET contains_outdated_attractions = EXISTS (
                    SELECT 1
                    FROM route_attractions ra
                    JOIN attractions a ON a.poi_id = ra.poi_id
                    WHERE ra.route_id = r.id
                      AND COALESCE(a.status, 'ACTIVE') != 'ACTIVE'
                )
                """;
    }
}
