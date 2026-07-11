[根目录](../../../CLAUDE.md) > [holo-horm](../CLAUDE.md) > **holo-horm-codegen**

# Holo :: HORM :: Codegen (代码生成 CLI)

## 模块职责

代码生成 CLI 工具，支持 DDL→Entity（从数据库表生成实体类）、Entity→DDL（从实体类生成建表语句）、Migration 脚本生成等功能。当前为规划阶段，仅 POM 已建，有预编译 jar。

- **parent**: `holo-horm` (1.0.0-SNAPSHOT)
- **artifactId**: `holo-horm-codegen`
- **packaging**: jar

## 规划功能

| 功能 | 说明 |
|------|------|
| DDL → Entity | 从数据库表结构反向生成 `@Entity` 类 |
| Entity → DDL | 从 `@Entity` 注解类生成建表 SQL |
| Migration 生成 | 自动生成 Migration 迁移脚本 |
| 模板引擎 | 可定制代码生成模板 |

## 当前状态

- **POM**: 已建，依赖 JavaPoet
- **源代码**: 无
- **预编译 jar**: 存在（`target/holo-horm-codegen-1.0.0-SNAPSHOT.jar`）

## 相关文件清单

| 文件路径 | 说明 |
|---------|------|
| `holo-horm/holo-horm-codegen/pom.xml` | 模块 POM |
| `holo-horm/holo-horm-codegen/target/holo-horm-codegen-1.0.0-SNAPSHOT.jar` | 预编译 jar（无源码） |

## 变更记录

| 日期 | 变更 | 说明 |
|------|------|------|
| 2026-07-06 | 初始文档生成 | 架构扫描 |