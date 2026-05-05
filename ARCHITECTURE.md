# agent-scope 项目架构与开发规范

## 1. 模块职责定义 (Module Responsibilities)

| 模块名 | 职责描述 | 核心内容 |
| :--- | :--- | :--- |
| `agent-scope-common` | **基础设施层**：存放与业务无关的通用工具、枚举、常量、基础异常类。 | Utils, Enums, Constants, BizException |
| `agent-scope-model` | **领域模型层**：定义数据载体。不允许包含业务逻辑。 | POJO, Entity, DTO, VO |
| `agent-scope-dao` | **数据访问层**：负责与数据库直接交互。 | Mapper 接口, SQL 定义, MyBatis/JPA 配置 |
| `agent-scope-service` | **基础服务层**：封装对 DAO 的原子操作，提供基础的 CRUD。 | Service 接口及实现 (数据导向) |
| `agent-scope-biz` | **业务逻辑层**：核心层。负责业务流程编排、多服务组合、AI SDK (AgentScope) 交互。 | Biz 接口及实现 (业务导向) |
| `agent-scope-config` | **通用配置层**：存放全局配置、AOP 切面、拦截器、全局异常处理器。 | WebMvcConfig, LogAspect, GlobalExceptionHandler |
| `agent-scope-web` | **接入层/启动层**：API 路由定义，项目启动入口。 | Controller, Application Starter |

## 2. 依赖关系规范 (Dependency Rules)

遵循单向依赖原则，严禁循环依赖：
**`web` -> `config` -> `biz` -> `service` -> `dao` -> `model` -> `common`**

*   **原则 1**：禁止跨层调用（例如：Controller 禁止直接调用 Service 或 DAO，必须经过 Biz 层）。
*   **原则 2**：`common` 和 `model` 应保持轻量，严禁依赖业务模块。
*   **原则 3**：所有第三方库（如 AgentScope SDK）应尽量在 `biz` 层封装，不要渗透到 `model` 或 `common`。

## 3. 编码准则 (Coding Standards)

*   **包名规范**：统一以 `com.cl.agent` 为前缀。
*   **异常处理**：业务错误统一抛出 `BizException`，由 `config` 模块的 `GlobalExceptionHandler` 捕获。
*   **响应格式**：Controller 统一返回 `ResponseEntity<T>` 或自定义的 `Result<T>`。
*   **注解使用**：
    *   使用 Lombok 的 `@Data`, `@Slf4j`, `@Builder` 简化代码。
    *   Service 实现类使用 `@Service`，Biz 实现类使用 `@Service`（或自定义 `@BizService`）。
*   **注释规范 (Commenting)**：
    *   **类注释**：所有类必须包含 `@author`（可选）和职责描述。
    *   **方法注释**：所有接口和公共方法必须包含标准 Javadoc（描述、`@param`, `@return`, `@throws`）。
    *   **属性注释**：所有成员变量（包括 DTO 字段）必须包含单行或多行注释说明含义。
*   **Agent 开发**：所有的 Agent 实例构建和对话逻辑必须封装在 `agent-scope-biz` 模块中。
