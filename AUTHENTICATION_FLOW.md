# Spring Boot 认证流程完整梳理

## 目录
1. [注册流程](#1-注册流程)
2. [登录流程](#2-登录流程)
3. [JWT 生成机制](#3-jwt-生成机制)
4. [Refresh Token 刷新流程](#4-refresh-token-刷新流程)
5. [权限校验流程](#5-权限校验流程)
6. [关键类和方法清单](#6-关键类和方法清单)
7. [改进方案](#7-改进方案)

---

## 1. 注册流程

### 入口
- **URL**: `POST /api/users`
- **Controller**: `UsersController.save()`

### 调用链
```
UsersController.save(CreateUserProps)
  └── CreateUserService.create(CreateUserProps)
        ├── EmailValidations.validateEmailUniqueness(props)  # 验证邮箱唯一性
        ├── RoleRepository.findOptionalByShortName("USER")   # 获取默认 USER 角色
        └── UserRepository.save(User)                         # 保存用户（密码自动 BCrypt 加密）
```

### 涉及的类和方法

| 类 | 方法 | 作用 |
|---|---|---|
| `UsersController` | `save(CreateUserProps)` | 接收注册请求，返回创建的用户 |
| `CreateUserService` | `create(CreateUserProps)` | 执行业务逻辑，创建用户 |
| `RoleRepository` | `findOptionalByShortName(String)` | 查找角色 |
| `UserRepository` | `save(User)` | 保存用户到数据库 |
| `User` | `created()` [@PrePersist] | 密码 BCrypt 加密（Entity 生命周期回调） |

### 数据流程
1. 客户端发送 `POST /api/users`，携带 `name`, `email`, `password`
2. 验证邮箱是否已存在
3. 为新用户分配默认 `USER` 角色
4. 创建 `User` 实体，`@PrePersist` 触发密码加密
5. 保存到数据库
6. 返回 `UserInformation` DTO

---

## 2. 登录流程

### 入口
- **URL**: `POST /api/authentication`
- **Controller**: `AuthenticationController.create()`

### 调用链
```
AuthenticationController.create(CreateAuthenticationWithEmailAndPassword)
  └── CreateAuthenticationService.create(CreateAuthenticationWithEmailAndPassword)
        ├── UserRepository.findByEmail(email)           # 查找用户
        ├── User.validatePassword(password)             # 验证密码 (BCrypt.matches)
        ├── JsonWebToken.encode(id, roles, expiresAt)  # 生成 Access Token
        ├── new RefreshToken(user, daysToExpire)        # 创建 Refresh Token 实体
        ├── RefreshTokenRepository.disableOldRefreshTokens(userId)  # 禁用旧 token
        ├── RefreshTokenRepository.save(refreshToken)   # 保存新 token
        └── return Authentication(userInfo, accessToken, refreshToken, expiresAt)
```

### 涉及的类和方法

| 类 | 方法 | 作用 |
|---|---|---|
| `AuthenticationController` | `create(CreateAuthenticationWithEmailAndPassword)` | 接收登录请求 |
| `CreateAuthenticationService` | `create(CreateAuthenticationWithEmailAndPassword)` | 执行登录认证逻辑 |
| `UserRepository` | `findByEmail(String)` | 根据邮箱查找用户 |
| `User` | `validatePassword(String)` | 验证密码（BCrypt 比对） |
| `JsonWebToken` | `encode(id, roles, expiration, secret)` | 生成 JWT |
| `RefreshToken` | 构造函数 | 生成 UUID code，设置过期时间 |
| `RefreshTokenRepository` | `disableOldRefreshTokens(Long)` | 禁用用户旧的 refresh token |
| `RefreshTokenRepository` | `save(RefreshToken)` | 保存新的 refresh token |

### 数据流程
1. 客户端发送 `POST /api/authentication`，携带 `email`, `password`
2. 根据邮箱查找用户
3. 使用 `BCrypt.matches` 验证密码
4. 计算过期时间（当前时间 + 24 小时）
5. 调用 `JsonWebToken.encode()` 生成 Access Token
6. 创建 `RefreshToken` 实体，生成 UUID code，过期时间 7 天
7. 禁用该用户所有旧的 refresh token（`available = false`）
8. 保存新的 refresh token 到数据库
9. 返回 `Authentication` 对象，包含：
   - `user`: 用户信息
   - `accessToken`: JWT
   - `refreshToken`: UUID
   - `expiresAt`: 过期时间

---

## 3. JWT 生成机制

### 核心类
- **类**: `JsonWebToken`
- **路径**: `modules/authentication/services/JsonWebToken.java`

### 编码 (encode) 方法
```java
public String encode(
    String id,              // 用户 ID（已通过 HashIds 编码）
    List<String> roles,     // 角色列表：["ADM", "USER"]
    LocalDateTime expiration,// 过期时间
    String secret           // 密钥
)
```

### JWT Payload 结构
| Claim | 来源 | 说明 |
|---|---|---|
| `sub` (subject) | `encode(user.getId())` | 用户 ID（HashIds 编码） |
| `roles` | `String.join(",", roles)` | 角色列表，逗号分隔 |
| `exp` (expiration) | `expiration.atZone(systemDefault()).toInstant()` | 过期时间戳 |

### 签名算法
- **算法**: `HS256` (HMAC SHA-256)
- **密钥**: `SecurityEnvironments.TOKEN_SECRET`

### 解码 (decode) 方法
```java
public Authorized decode(String token, String secret)
```
返回 `Authorized` 对象，包含：
- `id`: 解码后的用户 ID（Long）
- `authorities`: `List<SimpleGrantedAuthority>` 权限列表

---

## 4. Refresh Token 刷新流程

### 入口
- **URL**: `POST /api/authentication/refresh`
- **Controller**: `AuthenticationController.refresh()`

### 调用链
```
AuthenticationController.refresh(CreateAuthenticationWithRefreshToken)
  └── CreateAuthenticationService.create(CreateAuthenticationWithRefreshToken)
        ├── RefreshTokenRepository.findOptionalByCodeAndAvailableIsTrue(code)  # 查找 token
        ├── RefreshToken.nonExpired()                                           # 验证是否过期
        ├── JsonWebToken.encode(userId, roles, expiresAt)                       # 生成新 Access Token
        ├── RefreshTokenRepository.disableOldRefreshTokens(userId)              # 禁用旧 token
        ├── new RefreshToken(user, daysToExpire)                                 # 创建新 Refresh Token
        ├── RefreshTokenRepository.save(newRefreshToken)                         # 保存
        └── return Authentication(userInfo, accessToken, refreshToken, expiresAt)
```

### 涉及的类和方法

| 类 | 方法 | 作用 |
|---|---|---|
| `AuthenticationController` | `refresh(CreateAuthenticationWithRefreshToken)` | 接收刷新请求 |
| `CreateAuthenticationService` | `create(CreateAuthenticationWithRefreshToken)` | 执行刷新逻辑 |
| `RefreshTokenRepository` | `findOptionalByCodeAndAvailableIsTrue(String)` | 查找有效的 refresh token |
| `RefreshToken` | `nonExpired()` | 检查是否过期 |
| `RefreshToken` | `getUser()` | 获取关联的用户 |
| `JsonWebToken` | `encode(...)` | 生成新的 Access Token |
| `RefreshTokenRepository` | `disableOldRefreshTokens(Long)` | 禁用旧 token |
| `RefreshTokenRepository` | `save(RefreshToken)` | 保存新 token |

### 数据流程
1. 客户端发送 `POST /api/authentication/refresh`，携带 `refreshToken` (UUID)
2. 根据 code 查找 `available = true` 的 refresh token
3. 检查 `expiresAt` 是否在当前时间之后
4. 获取关联的 `User` 实体
5. 生成新的 Access Token（过期时间 24 小时）
6. 禁用该用户所有旧的 refresh token
7. 创建新的 Refresh Token（**注意：这里用的是 `TOKEN_EXPIRATION_IN_HOURS`，可能是个 bug**）
8. 保存新的 refresh token
9. 返回新的 `Authentication` 对象

### ⚠️ 发现的潜在 Bug
在 `CreateAuthenticationService.create(CreateAuthenticationWithRefreshToken)` 方法第 91 行：
```java
var refreshToken = new RefreshToken(user, TOKEN_EXPIRATION_IN_HOURS);
```
这里使用的是 `TOKEN_EXPIRATION_IN_HOURS`（24 小时），而登录时用的是 `REFRESH_TOKEN_EXPIRATION_IN_DAYS`（7 天）。这导致刷新后的 token 有效期变短。

---

## 5. 权限校验流程

### 入口
- **过滤器**: `AuthenticationMiddleware`
- **顺序**: `@Order(1)`
- **类型**: `OncePerRequestFilter`

### 调用链
```
AuthenticationMiddleware.doFilterInternal(request, response, filterChain)
  └── RequestAuthorizer.tryAuthorizeRequest(request, response)
        ├── publicRoutes().anyMatch(request)           # 检查是否是公开路由
        ├── Authorization.extract(request)              # 从 Header 提取 Token
        ├── JsonWebToken.decode(token, TOKEN_SECRET)   # 解码 JWT
        ├── Authorized.getAuthentication()              # 创建 Spring Security 认证对象
        └── SecurityContextHolder.getContext().setAuthentication(auth)  # 设置上下文
```

### 涉及的类和方法

| 类 | 方法 | 作用 |
|---|---|---|
| `AuthenticationMiddleware` | `doFilterInternal()` | 认证过滤器入口 |
| `RequestAuthorizer` | `tryAuthorizeRequest()` | 执行授权逻辑 |
| `PublicRoutes` | `anyMatch(HttpServletRequest)` | 检查是否是公开路由 |
| `Authorization` | `extract(HttpServletRequest)` | 从 Authorization Header 提取 Bearer Token |
| `JsonWebToken` | `decode(token, secret)` | 解码 JWT，返回 `Authorized` |
| `Authorized` | `getAuthentication()` | 创建 `UsernamePasswordAuthenticationToken` |
| `SecurityContextHolder` | `getContext().setAuthentication()` | 设置认证上下文 |

### 公开路由配置
在 `SecurityConfiguration.api()` 中配置的公开路由：

| HTTP 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api` | API 根路径 |
| POST | `/api/users` | 用户注册 |
| POST | `/api/authentication/**` | 登录、刷新 token |
| POST | `/api/recoveries/**` | 密码恢复 |

### 数据流程
1. 请求到达 `AuthenticationMiddleware`
2. 调用 `RequestAuthorizer.tryAuthorizeRequest()`
3. 检查是否是公开路由：
   - 如果是，直接返回，不进行认证
   - 如果不是，继续
4. 从 `Authorization` Header 提取 Token：
   - Header 格式：`Authorization: Bearer <token>`
   - 提取 `<token>` 部分
5. 如果没有 token，直接返回（后续 Spring Security 会处理）
6. 调用 `JsonWebToken.decode()` 解码 JWT：
   - 验证签名
   - 验证过期时间
   - 解析 payload
7. 创建 `Authorized` 对象，包含：
   - `id`: 用户 ID（Long）
   - `authorities`: 权限列表
8. 调用 `Authorized.getAuthentication()` 创建 `UsernamePasswordAuthenticationToken`
9. 设置到 `SecurityContextHolder`：
   ```java
   SecurityContextHolder.getContext().setAuthentication(authorized.getAuthentication());
   ```
10. 如果解码失败（过期、签名错误等），调用 `expired(response)` 返回 401

### 方法级权限校验
使用 `@PreAuthorize` 注解：

```java
@GetMapping
@PreAuthorize("hasAnyAuthority('ADM')")  // 只有管理员可以访问
public ResponseEntity<Page<UserInformation>> index(...)

@GetMapping("/{user_id}")
@PreAuthorize("hasAnyAuthority('ADM', 'USER')")  // 管理员和用户都可以访问
public ResponseEntity<UserInformation> show(...)
```

---

## 6. 关键类和方法清单

### 6.1 控制器层

#### AuthenticationController
**路径**: `modules/authentication/controllers/AuthenticationController.java`

| 方法 | HTTP | 路径 | 作用 |
|---|---|---|---|
| `create(CreateAuthenticationWithEmailAndPassword)` | POST | `/api/authentication` | 用户名密码登录 |
| `refresh(CreateAuthenticationWithRefreshToken)` | POST | `/api/authentication/refresh` | Refresh Token 刷新 |

#### UsersController
**路径**: `modules/users/controllers/UsersController.java`

| 方法 | HTTP | 路径 | 权限 | 作用 |
|---|---|---|---|---|
| `index(page, size)` | GET | `/api/users` | `hasAnyAuthority('ADM')` | 获取用户列表 |
| `show(id)` | GET | `/api/users/{id}` | `hasAnyAuthority('ADM', 'USER')` | 获取单个用户 |
| `save(CreateUserProps)` | POST | `/api/users` | 公开 | 用户注册 |
| `update(id, UpdateUserProps)` | PUT | `/api/users/{id}` | `hasAnyAuthority('ADM', 'USER')` | 更新用户 |
| `destroy(id)` | DELETE | `/api/users/{id}` | `hasAnyAuthority('ADM')` | 删除用户 |

### 6.2 服务层

#### CreateAuthenticationService
**路径**: `modules/authentication/services/CreateAuthenticationService.java`

| 方法 | 参数 | 返回值 | 作用 |
|---|---|---|---|
| `create()` | `CreateAuthenticationWithEmailAndPassword` | `Authentication` | 用户名密码登录 |
| `create()` | `CreateAuthenticationWithRefreshToken` | `Authentication` | Refresh Token 刷新 |

#### JsonWebToken
**路径**: `modules/authentication/services/JsonWebToken.java`

| 方法 | 参数 | 返回值 | 作用 |
|---|---|---|---|
| `encode()` | `id, roles, expiration, secret` | `String` (JWT) | 生成 JWT |
| `decode()` | `token, secret` | `Authorized` | 解码 JWT |

#### RequestAuthorizer
**路径**: `modules/authentication/services/RequestAuthorizer.java`

| 方法 | 参数 | 返回值 | 作用 |
|---|---|---|---|
| `tryAuthorizeRequest()` | `request, response` | `void` | 尝试授权请求 |

#### PublicRoutes
**路径**: `modules/authentication/services/PublicRoutes.java`

| 方法 | 参数 | 返回值 | 作用 |
|---|---|---|---|
| `create()` | - | `PublicRoutes` | 静态工厂方法 |
| `add()` | `method, routes...` | `PublicRoutes` | 添加公开路由 |
| `anyMatch()` | `request` | `boolean` | 检查是否匹配公开路由 |
| `injectOn()` | `http` | `void` | 注入到 HttpSecurity |

### 6.3 实体层

#### RefreshToken
**路径**: `modules/authentication/entities/RefreshToken.java`

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | `Long` | 主键 |
| `code` | `String` | UUID（refresh token 值） |
| `expiresAt` | `LocalDateTime` | 过期时间 |
| `available` | `Boolean` | 是否可用 |
| `user` | `User` | 关联用户 |

| 方法 | 返回值 | 作用 |
|---|---|---|
| `RefreshToken(user, daysToExpire)` | - | 构造函数，生成 UUID |
| `nonExpired()` | `Boolean` | 检查是否过期 |

#### User
**路径**: `modules/users/entities/User.java`

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | `Long` | 主键 |
| `name` | `String` | 姓名 |
| `email` | `String` | 邮箱（唯一） |
| `password` | `String` | 密码（BCrypt 加密） |
| `roles` | `List<Role>` | 角色列表 |

| 方法 | 返回值 | 作用 |
|---|---|---|
| `getAuthorities()` | `List<String>` | 获取角色名称列表 |
| `validatePassword(password)` | `Boolean` | 验证密码 |
| `updatePassword(newPassword)` | `void` | 更新密码（自动加密） |
| `created()` | `void` | `@PrePersist`，密码加密 |

### 6.4 数据访问层

#### RefreshTokenRepository
**路径**: `modules/authentication/repositories/RefreshTokenRepository.java`

| 方法 | 参数 | 返回值 | SQL/作用 |
|---|---|---|---|
| `disableOldRefreshTokens()` | `Long id` | `void` | `UPDATE refresh_token SET available = false WHERE user_id = ?` |
| `findOptionalByCodeAndAvailableIsTrue()` | `String code` | `Optional<RefreshToken>` | `SELECT ... WHERE code = ? AND available = true`（FETCH JOIN user 和 roles） |

#### UserRepository
**路径**: `modules/users/repositories/UserRepository.java`

| 方法 | 参数 | 返回值 | 作用 |
|---|---|---|---|
| `findByEmail()` | `String email` | `Optional<User>` | 根据邮箱查找用户 |
| `save()` | `User` | `User` | 保存用户 |

### 6.5 中间件和配置

#### AuthenticationMiddleware
**路径**: `modules/infra/middlewares/AuthenticationMiddleware.java`

| 方法 | 参数 | 作用 |
|---|---|---|
| `doFilterInternal()` | `request, response, filterChain` | 过滤器入口 |

#### SecurityConfiguration
**路径**: `modules/infra/configurations/SecurityConfiguration.java`

| Bean | 顺序 | 作用 |
|---|---|---|
| `api()` | `@Order(1)` | API 安全配置（JWT 认证） |
| `app()` | `@Order(2)` | SSR 应用安全配置（Session 认证） |
| `swagger()` | `@Order(4)` | Swagger 安全配置 |

#### SecurityEnvironments
**路径**: `modules/infra/environments/SecurityEnvironments.java`

| 常量 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| `TOKEN_SECRET` | `String` | `"secret"` | JWT 签名密钥 |
| `TOKEN_EXPIRATION_IN_HOURS` | `Integer` | `24` | Access Token 过期时间（小时） |
| `REFRESH_TOKEN_EXPIRATION_IN_DAYS` | `Integer` | `7` | Refresh Token 过期时间（天） |
| `JWT` | `JsonWebToken` | `new JsonWebToken()` | JWT 工具单例 |
| `ENCODER` | `BCryptPasswordEncoder` | - | BCrypt 编码器 |
| `ROLES_KEY_ON_JWT` | `String` | `"roles"` | JWT 中 roles 的 key |
| `AUTHORIZATION_HEADER` | `String` | `"Authorization"` | Header 名称 |
| `SECURITY_TYPE` | `String` | `"Bearer"` | Token 类型 |

### 6.6 DTO 层

#### CreateAuthenticationWithEmailAndPassword
**路径**: `modules/authentication/dtos/CreateAuthenticationWithEmailAndPassword.java`

| 字段 | 验证 | 说明 |
|---|---|---|
| `email` | `@Email`, `@NotBlank` | 邮箱 |
| `password` | `@NotBlank`, `@Size(min=6, max=255)` | 密码 |

#### CreateAuthenticationWithRefreshToken
**路径**: `modules/authentication/dtos/CreateAuthenticationWithRefreshToken.java`

| 字段 | 验证 | 说明 |
|---|---|---|
| `refreshToken` | `@NotBlank` | Refresh Token（UUID） |

#### Authentication
**路径**: `modules/authentication/models/Authentication.java`

| 字段 | 类型 | 说明 |
|---|---|---|
| `user` | `UserInformation` | 用户信息 |
| `accessToken` | `String` | JWT |
| `refreshToken` | `String` | UUID |
| `expiresAt` | `LocalDateTime` | Access Token 过期时间 |

#### Authorized
**路径**: `modules/authentication/models/Authorized.java`

继承自 `org.springframework.security.core.userdetails.User`

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | `Long` | 用户 ID |
| `name` | `String` | 用户名/邮箱 |

| 方法 | 返回值 | 作用 |
|---|---|---|
| `getAuthentication()` | `UsernamePasswordAuthenticationToken` | 创建 Spring Security 认证对象 |
| `current()` | `Optional<Authorized>` | 获取当前登录用户（静态方法） |

---

## 7. 改进方案

### 7.1 当前问题分析

#### 问题 1: Refresh Token 存储
**现状**: Refresh Token 存储在 PostgreSQL 数据库中（通过 JPA）
- 优点：持久化，重启不丢失
- 缺点：数据库查询相对较慢，不适合高频访问

**用户需求**: 改为存储到 Redis
- 优点：高性能，自动过期机制
- 需要：添加 Redis 依赖，修改存储逻辑

#### 问题 2: 无 Logout 接口
**现状**: 
- SSR 应用有 `/app/logout`（Session 登出）
- API 没有 `/api/auth/logout` 接口
- 登出后 JWT 仍然有效，直到过期

**用户需求**:
- 添加 `/api/auth/logout` 接口
- 登出时删除当前用户的 Refresh Token
- 将 Access Token 加入黑名单

#### 问题 3: 无 Token 黑名单机制
**现状**: JWT 是无状态的，服务端无法主动使 Token 失效

**用户需求**:
- 使用 Redis 存储黑名单 Token
- 过期时间 = Token 剩余有效期
- 认证时检查黑名单

#### 问题 4: Rate Limit 基于 IP
**现状**: 使用 bucket4j，配置基于 IP 限流
```properties
bucket4j.filters[0].rate-limits[0].expression=getRemoteAddr()
```

**用户需求**:
- 基于用户限流（同一个用户每分钟最多 60 次）
- 未登录用户继续基于 IP

### 7.2 技术方案

#### 方案架构
```
┌─────────────────────────────────────────────────────────────┐
│                        Client                                │
└──────────────────────────┬──────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────┐
│                   Spring Boot Application                     │
│  ┌───────────────────────────────────────────────────────┐  │
│  │                   Filters (Order)                      │  │
│  │  1. RateLimitFilter          (用户/IP 限流)           │  │
│  │  2. AuthenticationMiddleware (JWT 认证 + 黑名单检查)  │  │
│  └───────────────────────────────────────────────────────┘  │
│                           │                                   │
│                           ▼                                   │
│  ┌───────────────────────────────────────────────────────┐  │
│  │                   Controllers                           │  │
│  │  - AuthenticationController (登录、刷新、登出)          │  │
│  │  - UsersController        (注册、用户管理)              │  │
│  └───────────────────────────────────────────────────────┘  │
│                           │                                   │
│                           ▼                                   │
│  ┌───────────────────────────────────────────────────────┐  │
│  │                     Services                            │  │
│  │  - RedisService              (Redis 操作封装)          │  │
│  │  - CreateAuthenticationService (认证逻辑)              │  │
│  │  - LogoutService             (登出逻辑)                │  │
│  └───────────────────────────────────────────────────────┘  │
└──────────────────────────┬──────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────┐
│                        Redis                                  │
│  ┌───────────────────────────────────────────────────────┐  │
│  │  Keys:                                                  │  │
│  │  - refresh_token:{userId}     (Hash, field=tokenCode) │  │
│  │  - blacklist:{token}          (String, TTL=剩余时间)  │  │
│  │  - rate_limit:{userId/IP}      (String, TTL=60秒)    │  │
│  └───────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────┘
```

#### Redis Key 设计

| Key Pattern | Type | Value | TTL | 说明 |
|---|---|---|---|---|
| `refresh_token:{userId}` | Hash | `{tokenCode: expiresAt}` | 7 天 | 用户的 refresh token |
| `blacklist:{jti}` | String | `"true"` | Token 剩余时间 | 黑名单 Token（使用 JWT ID） |
| `rate_limit:{userId}` | String | 请求计数 | 60 秒 | 用户请求计数 |
| `rate_limit:{ip}` | String | 请求计数 | 60 秒 | 未登录用户 IP 计数 |

#### 改进后的认证流程

##### 登录流程（改进后）
```
1. 验证邮箱密码
2. 生成 JWT（添加 jti claim）
3. 生成 Refresh Token (UUID)
4. 存储到 Redis: HSET refresh_token:{userId} {tokenCode} {expiresAt}
5. 删除数据库中旧的 Refresh Token（或保留）
6. 返回 Authentication
```

##### 刷新 Token 流程（改进后）
```
1. 从 Redis 检查 refresh token 是否存在: HGET refresh_token:{userId} {tokenCode}
2. 验证过期时间
3. 生成新的 JWT
4. 删除旧的 refresh token: HDEL refresh_token:{userId} {oldTokenCode}
5. 生成新的 refresh token
6. 存储新的到 Redis
7. 返回新的 Authentication
```

##### 登出流程（新增）
```
1. 从 Authorization Header 提取 JWT
2. 解码 JWT 获取 userId 和 jti
3. 删除用户的所有 refresh token: DEL refresh_token:{userId}
4. 将 jti 加入黑名单: SET blacklist:{jti} "true" EX {剩余秒数}
5. 返回 200 OK
```

##### 认证中间件（改进后）
```
1. 检查是否是公开路由
2. 提取 JWT
3. 解码 JWT 获取 jti
4. 检查黑名单: EXISTS blacklist:{jti}
   - 如果存在，返回 401 Unauthorized
5. 设置 SecurityContext
6. 继续处理
```

##### 限流流程（新增）
```
1. 判断用户是否登录：
   - 已登录：使用 userId 作为 key
   - 未登录：使用 IP 作为 key
2. 获取当前计数: GET rate_limit:{key}
3. 如果计数 >= 60:
   - 返回 429 Too Many Requests
4. 否则:
   - 计数 +1: INCR rate_limit:{key}
   - 如果是第一次: EXPIRE rate_limit:{key} 60
5. 继续处理
```

### 7.3 代码改动清单

#### 新增文件

| 文件路径 | 说明 |
|---|---|
| `modules/authentication/services/RedisService.java` | Redis 操作封装 |
| `modules/authentication/services/LogoutService.java` | 登出逻辑 |
| `modules/authentication/controllers/LogoutController.java` | 登出 API |
| `modules/infra/middlewares/RateLimitFilter.java` | 限流过滤器 |
| `modules/infra/configurations/RedisConfiguration.java` | Redis 配置 |

#### 修改文件

| 文件路径 | 修改内容 |
|---|---|
| `pom.xml` | 添加 Redis 依赖 |
| `application.properties` | 添加 Redis 配置 |
| `CreateAuthenticationService.java` | 改用 Redis 存储 refresh token |
| `RequestAuthorizer.java` | 添加黑名单检查 |
| `JsonWebToken.java` | 添加 jti (JWT ID) |
| `SecurityConfiguration.java` | 添加 Logout 路由到公开路由？ |
| `AuthenticationMiddleware.java` | 可能需要调整顺序 |

#### 可选修改

| 文件路径 | 修改内容 |
|---|---|
| `RefreshToken.java` | 可以保留（用于兼容或迁移） |
| `RefreshTokenRepository.java` | 可以保留 |

---

## 附录

### A. 数据库表结构

#### user 表
```sql
CREATE TABLE "user" (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    email VARCHAR(255) UNIQUE,
    password VARCHAR(255) NOT NULL,
    deleted_email VARCHAR(255),
    active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP,
    updated_at TIMESTAMP,
    deleted_at TIMESTAMP
);
```

#### refresh_token 表
```sql
CREATE TABLE refresh_token (
    id BIGSERIAL PRIMARY KEY,
    code VARCHAR(255),
    expires_at TIMESTAMP,
    available BOOLEAN DEFAULT TRUE,
    user_id BIGINT REFERENCES "user"(id)
);
```

#### role 表
```sql
CREATE TABLE role (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(255),
    short_name VARCHAR(255)
);
```

#### user_role 关联表
```sql
CREATE TABLE user_role (
    user_id BIGINT REFERENCES "user"(id),
    role_id BIGINT REFERENCES role(id),
    PRIMARY KEY (user_id, role_id)
);
```

### B. 配置项

#### application.properties 安全相关配置
```properties
# JWT
token.expiration-in-hours=${TOKEN_EXPIRATION_IN_HOURS:24}
token.refresh.expiration-in-days=${REFRESH_TOKEN_EXPIRATION_IN_DAYS:7}
token.secret=${TOKEN_SECRET:secret}

# HashIds
hashid.secret=${HASHID_SECRET:secret}

# Cookie
cookie.secret=${COOKIE_SECRET:secret}
server.servlet.session.cookie.name=${COOKIE_NAME:CONSESSIONARIA_SESSION_ID}
```

### C. 参考源码位置

| 模块 | 路径 |
|---|---|
| 认证控制器 | `modules/authentication/controllers/` |
| 认证服务 | `modules/authentication/services/` |
| 认证实体 | `modules/authentication/entities/` |
| 认证 DTO | `modules/authentication/dtos/` |
| 认证模型 | `modules/authentication/models/` |
| 中间件 | `modules/infra/middlewares/` |
| 安全配置 | `modules/infra/configurations/SecurityConfiguration.java` |
| 环境变量 | `modules/infra/environments/SecurityEnvironments.java` |
| 用户模块 | `modules/users/` |
