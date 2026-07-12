package com.holo.framework.horm.migration.internal;

import com.holo.framework.horm.migration.Schema;
import com.holo.framework.horm.migration.TableBuilder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

/**
 * Schema 的 DDL 收集实现。
 *
 * <p>所有 Schema 操作被转换为 DDL SQL 语句并收集到列表中，
 * 最终交由 Flyway 执行。
 */
public final class DdlSchema implements Schema {

    private final SchemaRenderer renderer;
    private final List<String> statements = new ArrayList<>();

    public DdlSchema(SchemaRenderer renderer) {
        this.renderer = renderer;
    }

    @Override
    public void createTable(String tableName, Consumer<TableBuilder> builder) {
        TableDefinition tableDef = new TableDefinition(tableName);
        DefaultTableBuilder tableBuilder = new DefaultTableBuilder(tableDef);
        builder.accept(tableBuilder);

        statements.add(renderer.renderCreateTable(tableDef));

        for (ColumnDefinition col : tableDef.getColumns()) {
            if (col.isCreateIndex()) {
                String indexName = "idx_" + tableName + "_" + col.getName();
                statements.add(renderer.renderCreateIndex(indexName, tableName, col.getName()));
            }
        }
    }

    @Override
    public void alterTable(String tableName, Consumer<TableBuilder> builder) {
        TableDefinition tableDef = new TableDefinition(tableName);
        DefaultTableBuilder tableBuilder = new DefaultTableBuilder(tableDef);
        builder.accept(tableBuilder);

        for (ColumnDefinition col : tableDef.getColumns()) {
            if ("DROP".equals(col.getType())) {
                statements.add(renderer.renderDropColumn(tableName, col.getName()));
            } else if ("MODIFY".equals(col.getType())) {
                statements.add(renderer.renderModifyColumn(tableName, col));
            } else {
                statements.add(renderer.renderAddColumn(tableName, col));
                if (col.isCreateIndex()) {
                    String indexName = "idx_" + tableName + "_" + col.getName();
                    statements.add(renderer.renderCreateIndex(indexName, tableName, col.getName()));
                }
            }
        }
    }

    @Override
    public void dropTable(String tableName) {
        statements.add(renderer.renderDropTable(tableName));
    }

    @Override
    public void createIndex(String indexName, String tableName, String... columns) {
        statements.add(renderer.renderCreateIndex(indexName, tableName, columns));
    }

    @Override
    public void dropIndex(String indexName, String tableName) {
        statements.add(renderer.renderDropIndex(indexName, tableName));
    }

    @Override
    public void renameTable(String oldName, String newName) {
        statements.add(renderer.renderRenameTable(oldName, newName));
    }

    @Override
    public void renameColumn(String tableName, String oldName, String newName) {
        statements.add(renderer.renderRenameColumn(tableName, oldName, newName));
    }

    public List<String> getStatements() {
        return statements;
    }

    public String toSql() {
        return String.join(";\n", statements) + ";";
    }
}
