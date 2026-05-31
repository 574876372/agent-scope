# agent-scope 项目架构与开发规范

## 1. 模块职责定义 (Module Responsibilities)

| 模块名 | 职责描述 | 核心内容 |
| :--- | :--- | :--- |
| `agent-scope-common` | **基础设施层**：存放与业务无关的通用工具、枚举、常量、基础异常类。 | Utils, Enums, Constants, BizException |
| `agent-scope-model` | **领域模型层**：定义数据载体。不允许包含业务逻辑。 | POJO, Entity, DTO, VO |
| `agent-scope-dao` | **数据访问层**：负责与数据库直接交互。 | Mapper 接口, SQL 定义, MyBatis/JPA 配置 |
| `agent-scope-service` | **基础服务层**：封装对 DAO 的原子操作，提供基础的 CRUD。 | Service 接口及实现 (数据导向) |
| `agent-scope-biz` | **业务逻辑层**：核心层。负责业务流程编排、多服务组合、AI SDK (AgentScope) 交互。 | Biz 接口及实现 (业务导向)；`biz/sql/` 宿主 SPI 实现 |
| `agent-scope-config` | **通用配置层**：存放全局配置、AOP 切面、拦截器、全局异常处理器。 | WebMvcConfig, LogAspect, GlobalExceptionHandler |
| `agent-scope-tool-spring-boot-starter` | **工具插件 Starter**：Agent 工具注册、反射调用、内置工具自动装配。 | `@AgentToolDef`, AgentToolRegistry, AutoConfiguration |
| `agent-scope-sql-spring-boot-starter` | **SQL Agent Starter（SPI 瘦 starter）**：多数据源 Text-to-SQL + HITL 审批工具包。框架/守卫/令牌/加密/三个 `@AgentToolDef` 工具全部下沉；宿主仅实现 `DatasourceProvider` + `SqlAuditPublisher` 两个 SPI。 | `SqlGuardEngine`, `SqlConfirmExecutor`, `list_datasources` / `get_table_schema` / `query_database` |
| `agent-scope-web` | **接入层/启动层**：API 路由定义，项目启动入口。 | Controller, Application Starter |

## 2. 依赖关系规范 (Dependency Rules)

遵循单向依赖原则，严禁循环依赖：
*   **业务调用链**：**`web` -> `biz` -> `service` -> `dao` -> `model` -> `common`**
*   **配置依赖链**：**`web` / `biz` -> `config` -> `model` -> `common`**
*   **Starter 插件链**：**`web` / `biz` -> `agent-scope-tool-spring-boot-starter` / `agent-scope-sql-spring-boot-starter` -> `common`**（starter 之间互不依赖）

*   **原则 1**：禁止跨层调用（例如：Controller 禁止直接调用 Service 或 DAO，必须经过 Biz 层）。
*   **原则 2**：`common` 和 `model` 应保持轻量，严禁依赖业务模块。
*   **原则 3**：所有第三方库（如 AgentScope SDK）应尽量在 `biz` 层封装，不要渗透到 `model` 或 `common`。
*   **原则 4（SQL Starter SPI）**：`agent-scope-sql-spring-boot-starter` **不依赖** model/dao/service/biz/web；宿主在 `biz/sql/` 实现 `DatasourceProvider` 与 `SqlAuditPublisher`，在 `service` 层管理 `t_datasource` / `t_sql_audit` 持久化。

## 3. 编码准则与设计规范 (Coding & Design Standards)

### 3.1 包结构与包命名规范 (Package Naming & Grouping)

*   **统一前缀**：项目包名统一以 `com.cl.agent` 为前缀。
*   **各层包命名**：
    - 控制层 (Controller)：`com.cl.agent.controller`
    - 业务逻辑层 (Biz)：`com.cl.agent.biz`
    - 基础服务层 (Service)：`com.cl.agent.service`
    - 数据访问层 (DAO)：`com.cl.agent.dao`
    - 领域模型/实体层 (Model)：`com.cl.agent.model`
    - 数据传输对象 (DTO)：`com.cl.agent.dto`
    - 配置层 (Config)：`com.cl.agent.config`
    - 流处理助手 (Stream Helpers)：`com.cl.agent.stream`
*   **包聚合划分原则**：
    - **`com.cl.agent.dto`**：仅存放用于跨层数据传输的 DTO（如 `SendMessageRequest`、`ChatStreamEvent`）。禁止存放任何带有业务逻辑的处理工具类。
    - **`com.cl.agent.stream`**：存放所有流式/SSE 推送的处理助手类（如 `StreamContext`、`StreamAccumulator`）。
    - **按功能内聚分组**：应当按照**功能凝聚性**对类进行分组，而不是仅按技术类型划分。如果一组类共同服务于某项特定特性，应将它们抽离到专门的子包中，避免混杂在通用包中。
    - **`com.cl.agent.biz.sql`**：SQL Agent 宿主侧 SPI 实现（`HostDatasourceProvider`、`HostSqlAuditPublisher`）及 HITL SSE 编排（`SqlAgentBizImpl`）所在子包。
    - **禁止滥用内部类**：禁止在 `@Service` 或 `@Component` 中定义复杂的非微型辅助内部类。所有非平凡辅助类均需抽取为独立文件并放在 `agent-scope-model` 或相应包中。

### 3.2 模块职责与逻辑定位

*   **模型纯净度**：`agent-scope-model` 模块**绝对禁止**依赖任何 AI SDK（例如 AgentScope），保持实体类与 DTO 的纯粹性。
*   **逻辑存放定位**：
    - **AI 交互与流程编排**（创建 Agent、调用大模型等）必须封装在 `agent-scope-biz` 的实现类中。
    - **数据持久化、缓存读取**等基础数据操作隶属于 `agent-scope-service` 模块。
    - **全局配置、拦截器、AOP 切面及全局异常处理器**统一放置在 `agent-scope-config` 模块中。
*   **异常处理**：业务流程中的错误统一抛出 `BizException`（定义于 `common` 模块），由 `config` 模块的 `GlobalExceptionHandler` 统一捕获并响应给前端。
*   **响应格式**：Controller 接口必须遵循 RESTful API 设计规范，统一返回 `ResponseEntity<T>`（包括支持 `Flux` / `Mono` 等反应式推送类型）。

### 3.3 注解与工具使用

*   **注解规范**：
    - 统一使用 **Lombok** 简化代码，推荐使用 `@Data`、`@Slf4j`、`@Builder`、`@AllArgsConstructor`、`@NoArgsConstructor`。
    - Service 实现类使用 `@Service`，Biz 实现类统一使用 `@Service`。

### 3.4 注释强制规范 (Mandatory Javadoc Standards)

为保障团队协作效率，新增或修改代码时若缺失如下规范注释，则视为“未完成”，不得提交：

1.  **类注释 (Class Javadoc)**：每个类定义前必须写明 Javadoc 注释，包含 `@author`（可选）以及该类对应的职责、使用场景及所属分层说明。
2.  **方法注释 (Method Javadoc)**：所有接口和公共/私有方法（包括接口默认方法）必须使用 Javadoc，且包含以下结构：
    -   **功能说明**：用 1～3 句简述该方法所做的工作及在业务链中的角色。必须说明**使用说明**，包含何时调用、调用前置条件、副作用（如写库、触发 SSE）、与同类方法的区别。
    -   **入参说明 (`@param`)**：每个参数单独写一行。格式：`@param 参数名 类型/语义 - 含义、是否必填、格式限制、为空时的处理行为`。
        - *Controller 方法*：必须说明对应的路径变量/查询参数、请求头（如 `X-User-Id`）的业务逻辑。
        - *Service/Biz 方法*：说明参数的来源与业务层约束。
    -   **返回参数说明 (`@return`)**：说明返回值的业务含义和结构，集合需要说明是否可能返回空列表。
        - `void`：必须写明 `@return 无`，并说明该方法带来的副作用（如已持久化）。
        - 包装类型（如 `ResponseEntity`、`Flux`、`Optional`）：说明其状态码语义、流结束条件或空值含义。
        - 反应式流方法 (`Flux` / `Mono`)：说明流中具体的元素类型、推送事件名（如 `reasoning` / `message`）及结束帧标记（如 `[DONE]`）。
    -   **异常说明 (`@throws`)**：列出方法可能抛出的业务异常 `BizException` 的类型、触发条件及建议调用方的处理方式。

    *方法注释范例：*
    ```java
    /**
     * 以 SSE 流式方式处理用户消息，将 Agent 推理过程与最终回复推送给前端。
     * <p>使用说明：由 {@code ChatController#sendMessageStream} 调用；调用前会话须已存在或可由本方法创建；
     * 流结束后会将完整助手消息写入会话历史。</p>
     *
     * @param request 发送消息请求，{@code conversationId} 可为空（将自动建会话），{@code content} 必填且非空
     * @return {@link ChatStreamEvent} 流；{@code event} 为 reasoning/tool_result/message 时表示内容块，
     *         无 event 且 data 为 {@code [CONV_ID]} / {@code [DONE]} 时为控制帧
     * @throws BizException 会话不存在时抛出，code=404
     */
    ```

3.  **字段与局部变量注释 (Field & Variable Comments)**：
    - 类成员变量定义上方，必须使用 `/** ... */` 进行 Javadoc 注释，说明含义、单位或取值约束。
    - 局部变量：若变量名本身不能直接体现含义，必须加行内单行注释说明。
    - 常量 (`static final`)：必须注释说明其设计用途及特定格式规范。
4.  **禁止无意义注释**：注释必须解释“为什么做”或“代表什么”，禁止只单纯重复变量名或代码内容（例如 `// i++` 属于无效注释）。

### 3.5 数据库变更与 SQL 脚本规范 (SQL & Database Standards)

所有的数据库变更和 SQL 初始化脚本必须遵循以下规范：

1.  **文件保存位置与命名**：
    - 脚本统一保存在项目根目录下的 `docx/` 文件夹内。
    - 文件名格式统一使用：`YYYY-MM-DD_简要功能描述.sql`（例如：`2026-05-28_init_memory_tables.sql`）。
2.  **SQL 脚本格式规范（按 init_memory_tables.sql 格式保存）**：
    -   **头部框状注释**：每个 SQL 脚本开头必须包含一个框状头部注释，说明模块职责、脚本用途以及目标数据库确认。
    -   **步骤区块注释**：每个操作区块或变更步骤前，必须加上带序号的清晰单行注释说明（如 `-- 1. ...`，`-- 2. ...`）。
    -   **大小写约定**：SQL 关键字（如 `CREATE TABLE`、`ALTER TABLE`、`VARCHAR`、`DEFAULT`、`COMMENT` 等）必须使用 **大写**；表名、字段名、索引名等必须使用 **小写** 并使用反引号 `` ` `` 包裹。
    -   **排版对齐**：`CREATE TABLE` 语句中，字段名、类型、约束及注释需要通过空格进行垂直对齐，以增强可读性。
    -   **注释强制性**：所有新建表和字段都必须包含明确的 `COMMENT` 描述。
    -   **审计与状态字段**：所有新建表必须包含统一的审计字段：`` `create_by` ``、`` `create_time` ``、`` `update_by` ``、`` `update_time` `` 以及 `` `del_flag` ``。
    -   **表尾定义**：建表语句末尾统一指定引擎、字符集及表注释，如 `ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='表描述'`。
