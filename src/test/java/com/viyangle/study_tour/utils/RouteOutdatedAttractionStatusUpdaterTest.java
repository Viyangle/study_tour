package com.viyangle.study_tour.utils;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RouteOutdatedAttractionStatusUpdaterTest {

    @Test
    void ensureColumnAddsMissingFlag() throws Exception {
        Connection connection = mock(Connection.class);
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        ResultSet columns = mock(ResultSet.class);
        Statement statement = mock(Statement.class);
        when(connection.getMetaData()).thenReturn(metadata);
        when(connection.getCatalog()).thenReturn("study_tour");
        when(metadata.getColumns("study_tour", null, "routes", "contains_outdated_attractions"))
                .thenReturn(columns);
        when(columns.next()).thenReturn(false);
        when(connection.createStatement()).thenReturn(statement);

        RouteOutdatedAttractionStatusUpdater.ensureColumn(connection);

        verify(statement).executeUpdate(contains("ADD COLUMN contains_outdated_attractions"));
    }

    @Test
    void refreshAllUpdatesRoutesFromCurrentAttractionStatuses() throws Exception {
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeUpdate(RouteOutdatedAttractionStatusUpdater.refreshSql())).thenReturn(3);

        int refreshed = RouteOutdatedAttractionStatusUpdater.refreshAll(connection);

        assertEquals(3, refreshed);
        assertTrue(RouteOutdatedAttractionStatusUpdater.refreshSql().contains("COALESCE(a.status, 'ACTIVE') != 'ACTIVE'"));
    }
}
