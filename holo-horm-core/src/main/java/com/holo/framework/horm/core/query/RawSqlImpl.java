package com.holo.framework.horm.core.query;

import com.holo.framework.horm.core.HormContext;
import com.holo.framework.horm.core.JdbcOperations;
import com.holo.framework.horm.meta.RowMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Default {@link RawSql} implementation bound to a datasource name.
 */
public final class RawSqlImpl implements RawSql {

    private final HormContext ctx;
    private final String dataSourceName;

    public RawSqlImpl(HormContext ctx, String dataSourceName) {
        this.ctx = ctx;
        this.dataSourceName = dataSourceName;
    }

    @Override
    public <R> List<R> query(String sql, RowMapper<R> mapper, Object... bindings) {
        return JdbcOperations.query(ctx, dataSourceName, sql, List.of(bindings),
            rs -> {
                List<R> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(mapper.map(rs));
                }
                return result;
            },
            "rawSql query");
    }

    @Override
    public <R> Optional<R> queryOne(String sql, RowMapper<R> mapper, Object... bindings) {
        return JdbcOperations.query(ctx, dataSourceName, sql, List.of(bindings),
            rs -> rs.next() ? Optional.of(mapper.map(rs)) : Optional.empty(),
            "rawSql queryOne");
    }

    @Override
    public long update(String sql, Object... bindings) {
        return JdbcOperations.update(ctx, dataSourceName, sql, List.of(bindings),
            "rawSql update");
    }
}
