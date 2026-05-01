# 项目结构规则

这是一个标准的 Spring Boot 项目，各层职责如下：

- commons：公共服务模块，存放通用工具类、常量、公共配置等
- config：框架级配置类，如 MyBatis、Redis、线程池等 Spring 配置
- controller：对外提供接口，负责接收 HTTP 请求并返回响应
- dao：数据库连接层，负责与数据库交互
- mapper：MyBatis 模型映射，存放 Mapper 接口及对应的 XML 映射文件
- service：业务处理层，封装核心业务逻辑。所有 service 必须基于接口开发，接口定义放在 service 目录下，实现类放在 service/impl 子目录中，命名规范为 `XxxService`（接口）和 `XxxServiceImpl`（实现类）
- model：数据库实体类（POJO），与数据库表一一对应
- exception：自定义异常类及全局异常处理器（@ControllerAdvice），统一错误响应格式
- enums：业务枚举类，如状态码、类型定义等，避免魔法值

开发时请遵循各层职责，不要跨层调用或混用逻辑。

---

# 代码注释规范

所有 Java 类必须严格遵守以下注释规范：

## 类注释
- 每个类必须在类声明上方添加 Javadoc 注释，说明该类的职责和用途

## 字段注释
- 每个字段必须添加中文注释，使用 `/** 注释内容 */` 格式
- 注释需明确说明字段的含义、取值范围或业务含义，不得使用无意义的描述

## 方法注释
- 每个方法必须添加 Javadoc 注释，包含：
  - 方法功能描述（中文）
  - `@param` 参数说明（每个参数都需要）
  - `@return` 返回值说明（void 方法除外）
  - `@throws` 异常说明（有受检异常时必填）

## 示例
```java
/**
 * 用户信息响应 DTO
 * 用于返回用户的基本信息
 */
@Data
public class UserResponse implements Serializable {

    /** 用户唯一标识 */
    private String id;

    /** 用户昵称 */
    private String nickname;

    /**
     * 判断用户是否处于激活状态
     *
     * @return true 表示已激活，false 表示未激活
     */
    public boolean isActive() {
        return "ACTIVE".equals(status);
    }
}
```

## JSON 序列化规范
- 项目统一使用阿里 **Fastjson2** 进行 JSON 序列化与反序列化
- 所有 controller 层的请求参数类（Request）和响应类（Response/DTO）必须实现 `Serializable` 接口并声明 `serialVersionUID`
- 禁止在项目中混用 Jackson、Gson 等其他 JSON 库
