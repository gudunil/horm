# HORM 框架风险缓解计划

> **创建日期**: 2026-07-13
> **版本**: M10 性能优化后续风险修复
> **负责人**: Development Team
> **状态**: 待实施

---

## 1. 执行摘要

### 1.1 背景

M10 性能优化（Repository 缓存、SQL 模板预生成、JDBC Batch、SingleFlight）已成功实施并通过性能测试，但在代码审查中发现5个潜在风险点。这些风险在开发和测试阶段不易暴露，但在生产环境（多租户、数据库迁移、高并发）下可能引发难以排查的问题。

### 1.2 风险矩阵

| ID | 风险项 | 严重程度 | 发生概率 | 影响范围 | 优先级 |
|----|--------|----------|----------|----------|--------|
| R1 | Repository 缓存 Context 切换 | 高 | 高 | 多租户/并行测试 | **P0** |
| R2 | SingleFlight Checked Exception | 高 | 中 | 数据库异常场景 | **P0** |
| R3 | JDBC Batch 生成键顺序 | 中 | 低 | 数据库迁移 | **P1** |
| R4 | SqlTemplates 手动 ID 限制 | 中 | 低 | 手动 ID 场景 | **P1** |
| R5 | 资源管理健壮性 | 低 | 低 | 连接池异常 | **P2** |

### 1.3 修复目标

- ✅ 消除高优先级风险（R1、R2）
- ✅ 缓解中优先级风险（R3、R4）
- ✅ 验证低优先级风险（R5）
- ✅ 确保所有修复不影响现有功能
- ✅ 为未来版本提供改进方向

---

## 2. 详细风险分析

### 2.1 风险 R1：Repository 缓存 Context 切换

**问题描述：**

Horm.repository() 使用全局缓存机制，当 HormContext 切换时会清空整个缓存。设计假设全局只有一个 HormContext，但在多租户或并行测试场景下会频繁切换 context。

**风险场景：**

```java
// 场景 1：多租户切换
Horm.install("tenantA", dataSourceA);
Repository<User> repoA1 = Horm.repository(User.class);  // 创建并缓存

Horm.install("tenantB", dataSourceB);  // 清空缓存
Repository<User> repoB = Horm.repository(User.class);  // 重新创建

Horm.install("tenantA", dataSourceA);  // 再次清空缓存
Repository<User> repoA2 = Horm.repository(User.class);  // ⚠️ repoA2 != repoA1

// 场景 2：并行测试
// Test1: install(ctx1) → repository() → cache hit ✅
// Test2: install(ctx2) → repository() → cache clear ⚠️
// Test3: install(ctx3) → repository() → cache clear ⚠️
// 结果：缓存失效率 66%
```

**影响范围：**

- 多租户应用：频繁 context 切换导致缓存失效
- 并行测试：测试性能下降
- 数据源切换：Repository 实例不一致

**根本原因：**

- 设计假设：全局单 context 模式
- 缓存策略：context 切换时清空整个缓存（过于激进）
- 缺少文档：用户不知道这个设计约束

---

### 2.2 风险 R2：SingleFlightLoader Checked Exception 约束

**问题描述：**

DefaultCacheChain.get() 使用 Supplier<V> 作为 loader 参数，但 Supplier 不允许抛出 checked exception。如果用户的 loader 抛出 SQLException/IOException，异常会被吞掉而不传播。

**风险场景：**

```java
// 错误示例：loader 抛出 checked exception
Supplier<String> loader = () -> {
    throw new IOException("database connection failed");  // ⚠️ checked exception
};

// 内部处理：
try {
    return loader.get();  // ⚠️ IOException 不是 RuntimeException/Error
} catch (RuntimeException | Error ex) {
    throw ex;  // ⚠️ IOException 被吞掉
}

// 结果：
// - 等待线程收到 null 或空值
// - 异常未传播到监控系统
// - 缓存事件未发布
```

**影响范围：**

- 数据库异常场景：SQLException 被吞掉
- 网络异常：IOException 被吞掉
- 文件异常：FileNotFoundException 被吞掉

**根本原因：**

- Java Supplier 限制：不允许抛出 checked exception
- 异常处理：只捕获 RuntimeException/Error
- 缺少文档：用户不知道这个约束

---

### 2.3 风险 R3：JDBC Batch Insert 生成键顺序依赖

**问题描述：**

JdbcOperations.batchInsert() 假设 PreparedStatement.getGeneratedKeys() 返回的生成键顺序与插入顺序一致，但这个假设并非所有数据库驱动都满足。

**风险场景：**

```java
// 批量插入 3 个实体
entities = [e1, e2, e3]
JdbcOperations.batchInsert(ctx, ds, sql, bindings, generatedId -> {
    mapper.setId(entities.get(index[0]++), generatedId);  // ⚠️ 依赖顺序
}, ...);

// 数据库驱动返回顺序不一致（如 Oracle）
generatedKeys = [id3, id1, id2]  // ⚠️ 非插入顺序

// 结果：
// e1 获得错误的 id3
// e2 获得错误的 id1
// e3 获得错误的 id2
// → 数据污染
```

**影响范围：**

- 数据库迁移：从 MySQL/H2 迁移到 Oracle/SQL Server
- 新驱动：使用未测试的 JDBC 驱动版本
- 数据不一致：主键回填错误

**根本原因：**

- JDBC 规范：没有明确要求顺序一致
- 驱动差异：不同数据库驱动实现不同
- 缺少文档：用户不知道数据库兼容性限制

---

### 2.4 风险 R4：SqlTemplates 手动 ID 限制

**问题描述：**

SqlTemplates 构造函数硬编码排除所有 ID 字段，假设主键由数据库自增生成。不支持手动设置主键的场景（如 GenerationType.MANUAL）。

**风险场景：**

```java
@Entity
@Table(name = "users")
public class User extends Model<User> {
    @Id(strategy = GenerationType.MANUAL)  // ⚠️ 手动设置 ID
    private Long id;
}

// 用户尝试手动设置 ID
User user = new User();
user.setId(100L);  // 手动设置主键
repo.save(user);

// SqlTemplates 生成的 INSERT SQL：
// INSERT INTO users (name, email) VALUES (?, ?)  // ⚠️ 缺少 id 列

// 结果：
// - INSERT 缺少 ID 列，可能失败
// - save() 判断错误（id != null → update 而非 insert）
```

**影响范围：**

- 手动 ID 场景：数据导入、预分配 ID
- 多租户场景：跨租户 ID 迁移
- 数据迁移：从其他系统导入数据

**根本原因：**

- 设计假设：主键由数据库生成
- 过滤逻辑：硬编码 `!f.isId()`
- 缺少文档：用户不知道这个限制

---

### 2.5 风险 R5：资源管理健壮性

**问题描述：**

所有 JdbcOperations 方法使用 try-catch-finally 释放连接，但如果 releaseConnection() 本身抛出异常，可能导致原始异常被吞掉。

**风险场景：**

```java
try {
    // 数据库操作抛出异常
    ps.executeUpdate();  // throw SQLException("constraint violation")
} catch (SQLException e) {
    throw new HormException("Failed to insert", e);  // ⚠️ 原始异常
} finally {
    TransactionManager.releaseConnection(ctx, ds, conn);  // ⚠️ 可能抛出异常
    // 如果 releaseConnection 失败：
    // - 连接池满
    // - 网络断开
    // - 连接已关闭
    // 原始异常可能被吞掉
}
```

**影响范围：**

- 连接池异常：连接池满导致释放失败
- 网络异常：网络断开导致释放失败
- 资源泄漏：连接未正确释放

**根本原因：**

- 异常屏蔽：finally 块异常可能吞掉原始异常
- 缺少防御：没有 try-catch in finally
- 低风险：发生概率较低

---

## 3. 修复策略

### 3.1 修复原则

1. **文档优先**：通过清晰的文档说明设计约束和限制
2. **向后兼容**：修复不影响现有 API 和行为
3. **最小改动**：只修改必要的部分，避免过度设计
4. **验证充分**：所有修复必须通过测试验证

### 3.2 修复方案

#### R1：Repository 缓存 Context 切换

**方案：** 文档修复（短期）+ 架构改进（长期）

**短期修复（M10）：**
- ✅ 在 Horm.repository() Javadoc 中添加设计约束说明
- ✅ 说明单 context 假设和多租户影响
- ✅ 说明并行测试场景的性能影响
- ✅ 提供未来改进方向（per-context 缓存）

**长期改进（M11+）：**
- 📐 实现 per-context Repository 缓存
- 📐 使用 `Map<HormContext, ConcurrentHashMap<Class<?>, JdbcRepository<?>>>` 结构
- 📐 支持 ThreadLocal context 绑定

---

#### R2：SingleFlightLoader Checked Exception

**方案：** 文档修复（短期）+ 防御性编程（可选）

**短期修复（M10）：**
- ✅ 在 DefaultCacheChain.get() Javadoc 中添加 checked exception 约束
- ✅ 说明 Supplier 不允许 checked exception 的原因
- ✅ 提供两种处理方案：
  - Wrap in RuntimeException/CacheLoadException
  - Handle internally and return default/null
- ✅ 说明异常传播规则和事件发布机制

**可选改进：**
- 📐 在 SingleFlightLoader.load() 中捕获 Throwable 并包装
- 📐 将 checked exception 转换为 CacheLoadException
- 📐 确保 finally 块的正确执行

---

#### R3：JDBC Batch Insert 数据库兼容性

**方案：** 文档修复（短期）+ 数据库适配器（长期）

**短期修复（M10）：**
- ✅ 在 JdbcOperations.batchInsert() Javadoc 中添加数据库兼容性清单
- ✅ 列出主流数据库的支持情况（MySQL、PostgreSQL、H2、Oracle、SQL Server）
- ✅ 提供三种替代方案：
  - Use single-row insert operations
  - Manually set primary keys before batch
  - Implement database-specific batch strategy
- ✅ 说明错误处理验证机制

**长期改进（M11+）：**
- 📐 实现 DatabaseDialect 接口
- 📐 为不同数据库提供 BatchInsertStrategy
- 📐 使用数据库特定的生成键查询方法

---

#### R4：SqlTemplates 手动 ID 限制

**方案：** 文档修复（短期）+ 功能增强（长期）

**短期修复（M10）：**
- ✅ 在 SqlTemplates 类 Javadoc 中添加限制说明
- ✅ 说明当前不支持 GenerationType.MANUAL
- ✅ 提供三种临时解决方案：
  - Use raw JDBC with custom INSERT
  - Mark ID column as insertable = false (not recommended)
  - Wait for future HORM versions
- ✅ 说明未来改进计划

**长期改进（M11+）：**
- 📐 检查 @Id 字段的 generation strategy
- 📐 当 GenerationType.MANUAL 时包含 ID 列
- 📐 支持 @Column(insertable = true) 覆盖默认行为

---

#### R5：资源管理健壮性

**方案：** 审查验证（已完成）

**当前状态：**
- ✅ 所有方法在 finally 块中释放连接（符合最佳实践）
- ✅ 遵循标准 JDBC 资源管理规范
- ✅ 低风险点，当前实现已足够健壮

**可选改进：**
- 📐 在 finally 块中添加防御性 try-catch
- 📐 记录连接释放失败的日志
- 📐 使用连接池的 abandonConnection 回调

---

## 4. 实施计划

### 4.1 修复步骤

#### Phase 1：文档修复（优先级 P0）

**任务 1.1：Repository 缓存文档**
- 文件：`holo-horm-core/src/main/java/com/holo/framework/horm/core/Horm.java`
- 位置：`repository(Class<T> entityType)` 方法 Javadoc
- 内容：添加设计约束、多租户影响、并行测试影响、未来改进
- 验证：Javadoc 编译无错误

**任务 1.2：SingleFlight 文档**
- 文件：`holo-horm-cache/src/main/java/com/holo/framework/horm/cache/DefaultCacheChain.java`
- 位置：`get(K key, TypeReference<V> type, Supplier<V> loader, CachePolicy policy)` 方法 Javadoc
- 内容：添加 checked exception 约束、解决方案、异常传播规则
- 验证：Javadoc 编译无错误

---

#### Phase 2：文档修复（优先级 P1）

**任务 2.1：JDBC Batch 文档**
- 文件：`holo-horm-core/src/main/java/com/holo/framework/horm/core/JdbcOperations.java`
- 位置：`batchInsert()` 方法 Javadoc
- 内容：添加数据库兼容性清单、替代方案、错误处理验证
- 验证：Javadoc 编译无错误

**任务 2.2：SqlTemplates 文档**
- 文件：`holo-horm-core/src/main/java/com/holo/framework/horm/core/SqlTemplates.java`
- 位置：类 Javadoc
- 内容：添加手动 ID 限制说明、临时解决方案、未来改进
- 验证：Javadoc 编译无错误

---

#### Phase 3：测试验证（优先级 P0）

**任务 3.1：运行测试**
- 命令：`mvn verify -Pskip-enforcer`
- 范围：所有模块（core、cache、meta）
- 目标：确保修复不影响现有功能
- 验证：测试通过、无回归问题

**任务 3.2：Javadoc 验证**
- 命令：`mvn javadoc:javadoc`
- 范围：修改的文件
- 目标：确保文档格式正确
- 验证：无警告、无错误

---

#### Phase 4：代码提交

**任务 4.1：创建 commit**
- 分支：`feature/m10-performance-optimization`
- Commit 格式：`docs(core/cache): add design constraint documentation for [组件名]`
- 内容：所有文档修复
- 验证：`git status` 显示正确修改

**任务 4.2：更新文档**
- 文件：`docs/PROGRESS.md`
- 内容：添加 M10 风险修复记录
- 验证：文档格式正确

---

### 4.2 时间规划

| 阶段 | 任务 | 预计时间 | 负责人 |
|------|------|----------|--------|
| Phase 1 | Repository 缓存文档 | 30 分钟 | Dev Team |
| Phase 1 | SingleFlight 文档 | 30 分钟 | Dev Team |
| Phase 2 | JDBC Batch 文档 | 30 分钟 | Dev Team |
| Phase 2 | SqlTemplates 文档 | 30 分钟 | Dev Team |
| Phase 3 | 测试验证 | 15 分钟 | CI/CD |
| Phase 4 | 代码提交 | 15 分钟 | Dev Team |

**总预计时间：** 2.5 小时

---

### 4.3 回滚策略

如果修复引发问题，采用以下回滚策略：

1. **文档回滚：**
   ```bash
   git revert <commit-hash>
   ```

2. **部分回滚：**
   ```bash
   git checkout HEAD~1 -- holo-horm-core/src/main/java/.../Horm.java
   git commit -m "rollback: revert Horm.java documentation"
   ```

3. **紧急修复：**
   - 保持文档修复（不影响功能）
   - 添加临时注释说明已知问题
   - 在下个版本完全解决

---

## 5. 验证清单

### 5.1 功能验证

- [ ] 所有单元测试通过（`mvn test`）
- [ ] 所有集成测试通过（`mvn verify`）
- [ ] Javadoc 编译无错误（`mvn javadoc:javadoc`）
- [ ] 代码格式检查通过（`mvn spotless:check`）
- [ ] 代码静态分析通过（`mvn checkstyle:check`）

### 5.2 文档验证

- [ ] Repository 缓存文档包含设计约束说明
- [ ] SingleFlight 文档包含 checked exception 约束
- [ ] JDBC Batch 文档包含数据库兼容性清单
- [ ] SqlTemplates 文档包含手动 ID 限制说明
- [ ] 所有文档使用清晰的格式和示例

### 5.3 测试场景验证

- [ ] 多租户场景：context 切换不影响功能正确性
- [ ] 数据库异常：loader 异常正确传播
- [ ] Batch Insert：MySQL/H2 正常工作
- [ ] 手动 ID：用户了解限制并选择替代方案

---

## 6. 长期改进计划

### 6.1 M11+ 里程碑

**架构改进：**
- 📐 Per-context Repository 缓存（支持多租户）
- 📐 手动 ID 场景支持（GenerationType.MANUAL）
- 📐 数据库适配器模式（BatchInsertStrategy）

**功能增强：**
- 📐 SingleFlight 异常包装改进
- 📐 资源管理健壮性增强
- 📐 连接池监控和告警

### 6.2 技术债务

**已知限制：**
- Repository 缓存：单 context 设计（M10 文档已说明）
- SqlTemplates：不支持手动 ID（M10 文档已说明）
- JDBC Batch：依赖数据库驱动顺序（M10 文档已说明）

**计划解决：**
- M11：Per-context 缓存架构重构
- M12：手动 ID 场景完整支持
- M13：数据库方言抽象层

---

## 7. 风险接受声明

经过充分的风险评估和修复计划制定，团队同意以下事项：

1. **接受当前修复方案**：文档修复优先，架构改进长期规划
2. **接受已知限制**：单 context、手动 ID、数据库兼容性
3. **承诺长期改进**：在后续里程碑中逐步解决架构问题
4. **承诺监控**：在生产环境中监控这些风险点的实际表现

**签字确认：**

- 开发团队：_________________ 日期：_________
- 架构团队：_________________ 日期：_________
- 测试团队：_________________ 日期：_________

---

## 8. 附录

### 8.1 参考文档

- [HORM 架构设计](./01-architecture.md)
- [M10 性能优化报告](./13-benchmark-results.md)
- [JDBC 批处理最佳实践](https://docs.oracle.com/javase/tutorial/jdbc/batch.html)
- [Java Supplier 文档](https://docs.oracle.com/javase/8/docs/api/java/util/function/Supplier.html)

### 8.2 相关 Issue

- Issue #1: Repository 缓存在多租户场景下的性能问题
- Issue #2: SingleFlight 异常传播不完整
- Issue #3: Batch Insert 数据库兼容性问题
- Issue #4: SqlTemplates 不支持手动 ID

### 8.3 变更历史

| 日期 | 版本 | 变更内容 | 作者 |
|------|------|----------|------|
| 2026-07-13 | 1.0 | 初始版本，风险识别和修复计划 | Dev Team |

---

**文档结束**