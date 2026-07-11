[根目录](../../../CLAUDE.md) > [holo-horm](../CLAUDE.md) > **holo-horm-bom**

# Holo :: HORM :: BOM

## 模块职责

HORM 内部模块版本清单。业务方通过 `import` scope 引入可锁定所有 HORM 子模块版本。

- **parent**: `holo-horm` (1.0.0-SNAPSHOT)
- **artifactId**: `holo-horm-bom`
- **packaging**: pom

## 对外接口

此模块为 POM 类型，提供 HORM 内部模块版本号，包含：

- `holo-horm-meta`
- `holo-horm-core`
- `holo-horm-datasource`
- `holo-horm-cache`
- `holo-horm-codegen`
- `holo-horm-migration`
- `holo-horm-spring-boot-starter`

## 数据模型

无数据模型。纯 POM 版本管理。

## 相关文件清单

| 文件路径 | 说明 |
|---------|------|
| `holo-horm/holo-horm-bom/pom.xml` | BOM 配置 |

## 变更记录

| 日期 | 变更 | 说明 |
|------|------|------|
| 2026-07-06 | 初始文档生成 | 架构扫描 |