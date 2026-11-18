package com.viyangle.study_tour.handler;

import org.apache.ibatis.type.JdbcType;
import org.junit.jupiter.api.Test;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StringListJsonTypeHandlerTest {

    private final StringListJsonTypeHandler handler = new StringListJsonTypeHandler();

    @Test
    void getNullableResult_readsJsonStringArray() throws Exception {
        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.getString("study_points")).thenReturn("[\"知识点一\",\"知识点二\"]");

        assertThat(handler.getNullableResult(resultSet, "study_points"))
                .containsExactly("知识点一", "知识点二");
    }

    @Test
    void getNullableResult_mapsNullToEmptyList() throws Exception {
        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.getString(1)).thenReturn(null);

        assertThat(handler.getNullableResult(resultSet, 1)).isEmpty();
    }

    @Test
    void getNullableResult_rejectsNonStringArray() throws Exception {
        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.getString("questions")).thenReturn("[{\"question\":\"错误结构\"}]");

        assertThatThrownBy(() -> handler.getNullableResult(resultSet, "questions"))
                .isInstanceOf(SQLException.class)
                .hasMessage("数据库中的 JSON 字符串数组格式不正确");
    }

    @Test
    void setNonNullParameter_writesJsonStringArray() throws Exception {
        PreparedStatement statement = mock(PreparedStatement.class);

        handler.setNonNullParameter(statement, 1, List.of("思考题一"), JdbcType.VARCHAR);

        verify(statement).setString(1, "[\"思考题一\"]");
    }
}
