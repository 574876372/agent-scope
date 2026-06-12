# 文档修订记录

| 版本号 | 修改日期 | 修改人 | 修订内容 |
| :--- | :--- | :--- | :--- |
| V1.0 | 2026-04-30 | Codex | 初始版本，新增 `directPayout` 独立代发接口文档 |

---

# 一、概述

本文档面向接入 FlexiPay 代发能力的业务系统，说明 `PayoutController#directPayout` 独立代发接口的调用方式、请求参数、响应参数、状态含义与错误码约定。

该接口用于发起**独立代发**，无需关联原支付订单号，适用于已完成代发账户注册并具备代发权限的渠道商。

## 1. 通用说明

### 1.1 统一请求格式

- **请求方式**: `POST`
- **Content-Type**: `application/json`

### 1.2 统一响应格式

所有接口均返回统一的 JSON 结构，格式如下：

```json
{
  "code": "SUCCESS",
  "msg": "网关已成功处理请求",
  "data": {
    "bizSuccess": true,
    "bizCode": "000000",
    "bizMsg": "Success",
    "bizData": {
      "//": "具体业务响应数据"
    }
  }
}
```

- `code`: 网关响应码。`SUCCESS` 表示请求成功到达业务层。
- `msg`: 网关响应描述。
- `data.bizSuccess`: 业务是否成功。
- `data.bizCode`: 业务响应码。
- `data.bizMsg`: 业务响应描述。
- `data.bizData`: 具体业务返回数据。

---

# 二、API 接口定义

## 1. 独立代发接口

用于向已注册的代发账户发起一笔独立代发，不需要提供原支付订单号。

### 1.1 请求说明

- **请求方式**: `POST`
- **Content-Type**: `application/json`
- **请求路径**: `/aqf/direct-payout`

### 1.2 业务约束说明

1. 该接口仅用于**独立代发**，无需传入原支付订单号。
2. `partnerId` 必须命中系统白名单，否则接口返回业务失败。
3. `partnerOutBizNo` 为渠道商代发流水号，具备幂等防重语义；同一 `partnerId` 下重复提交会返回业务异常。
4. `userId` 必须是已注册的代发账户唯一 ID，否则接口返回业务失败。
5. `partnerRequestTime` 必须满足格式 `yyyy-MM-dd HH:mm:ss.SSS`。
6. `payoutAmount` 单位为元，必须大于 `0`，且最多保留两位小数。

### 1.3 输入参数（Request）

| 参数名称 | 类型 | 必填 | 说明 |
| :--- | :--- | :--- | :--- |
| `partnerId` | string | 是 | 渠道商 ID |
| `partnerOutBizNo` | string | 是 | 渠道商代发流水号，长度 1-64，同一渠道商下必须唯一 |
| `paymentType` | string | 是 | 代发类型。当前模型支持如 `ALIPAY_ACCOUNT`、`BANK_CARD` |
| `partnerRequestTime` | string | 是 | 渠道商请求时间，格式 `yyyy-MM-dd HH:mm:ss.SSS` |
| `chargeMode` | string | 否 | 收费模式，如 `BY_RATE`、`BY_PENNY` |
| `partnerExchangeRate` | string | 否 | 渠道商上送费率或单笔费用，具体含义取决于 `chargeMode` |
| `payoutAmount` | string | 是 | 实际代发金额，单位为元，必须大于 0，最多两位小数 |
| `remark` | string | 否 | 代发备注 |
| `orderTitle` | string | 是 | 订单标题 |
| `payerAccountBookId` | string | 是 | 付款记账本 ID |
| `userId` | string | 是 | 代发账户唯一 ID，必须已完成注册 |

#### 1.3.1 枚举与字段补充说明

- `paymentType`
  - `ALIPAY_ACCOUNT`: 代发到支付宝账户
  - `BANK_CARD`: 代发到银行卡
- `chargeMode`
  - `BY_RATE`: 按费率收费
  - `BY_PENNY`: 按单笔收费

> 当 `paymentType` 为 `BANK_CARD` 时，前提是该 `userId` 已绑定银行卡信息，否则代发会失败。

#### 1.3.2 请求样例

```json
{
  "partnerId": "PC2025120310081993346200037685829",
  "partnerOutBizNo": "DP20260430000001",
  "paymentType": "ALIPAY_ACCOUNT",
  "partnerRequestTime": "2026-04-30 14:30:15.123",
  "chargeMode": "BY_RATE",
  "partnerExchangeRate": "0.35",
  "payoutAmount": "100.00",
  "remark": "四月奖励发放",
  "orderTitle": "独立代发测试",
  "payerAccountBookId": "AB2026043000000001",
  "userId": "U2026043000000001"
}
```

### 1.4 输出参数（Response）

`bizData` 中会返回请求透传字段及代发结果字段。响应对象基于 `PayoutResp`，会包含请求对象中的主要业务字段，并新增如下返回参数：

| 参数名称 | 类型 | 说明 |
| :--- | :--- | :--- |
| `partnerId` | string | 渠道商 ID |
| `partnerOutBizNo` | string | 渠道商代发流水号 |
| `paymentType` | string | 代发类型 |
| `partnerRequestTime` | string | 渠道商请求时间 |
| `chargeMode` | string | 收费模式 |
| `partnerExchangeRate` | string | 渠道商费率或费用参数 |
| `payoutAmount` | string | 代发金额，单位为元 |
| `remark` | string | 代发备注 |
| `orderTitle` | string | 订单标题 |
| `payerAccountBookId` | string | 付款记账本 ID |
| `userId` | string | 代发账户唯一 ID |
| `platformBizNo` | string | 平台代发流水号 |
| `payeeIdentityType` | string | 收款方标识类型 |
| `payeeIdentity` | string | 收款方标识值 |
| `payeeName` | string | 收款方姓名 |
| `orderId` | string | 支付宝转账单据号 |
| `status` | string | 代发状态 |
| `failReason` | string | 失败原因；成功时通常为空 |
| `payDate` | string | 支付完成时间，格式 `yyyy-MM-dd HH:mm:ss.SSS` |
| `payFundOrderId` | string | 支付宝支付资金流水号，成功时返回 |
| `platformRequestTime` | string | 平台请求支付时间 |

### 1.5 状态说明

`status` 为对外返回的代发状态，当前实现至少覆盖以下几类：

| 状态值 | 说明 |
| :--- | :--- |
| `SUCCESS` | 代发成功 |
| `FAILED` | 代发失败 |
| `PROCESSING` | 代发处理中，需后续查询最终结果 |

### 1.6 响应样例

#### 1.6.1 成功样例

```json
{
  "code": "SUCCESS",
  "msg": "网关已成功处理请求",
  "data": {
    "bizSuccess": true,
    "bizCode": "000000",
    "bizMsg": "Success",
    "bizData": {
      "partnerId": "PC2025120310081993346200037685829",
      "partnerOutBizNo": "DP20260430000001",
      "paymentType": "ALIPAY_ACCOUNT",
      "partnerRequestTime": "2026-04-30 14:30:15.123",
      "chargeMode": "BY_RATE",
      "partnerExchangeRate": "0.35",
      "payoutAmount": "100.00",
      "remark": "四月奖励发放",
      "orderTitle": "独立代发测试",
      "payerAccountBookId": "AB2026043000000001",
      "userId": "U2026043000000001",
      "platformBizNo": "BP202604301430151234560001",
      "payeeIdentityType": "ALIPAY_LOGON_ID",
      "payeeIdentity": "demo_user@example.com",
      "payeeName": "张三",
      "orderId": "2026043022001400000000000001",
      "status": "SUCCESS",
      "failReason": null,
      "payDate": "2026-04-30 14:30:18.456",
      "payFundOrderId": "2026043021001000000000000001",
      "platformRequestTime": "2026-04-30 14:30:16.000"
    }
  }
}
```

#### 1.6.2 处理中样例

```json
{
  "code": "SUCCESS",
  "msg": "网关已成功处理请求",
  "data": {
    "bizSuccess": true,
    "bizCode": "000000",
    "bizMsg": "Success",
    "bizData": {
      "partnerId": "PC2025120310081993346200037685829",
      "partnerOutBizNo": "DP20260430000001",
      "paymentType": "ALIPAY_ACCOUNT",
      "partnerRequestTime": "2026-04-30 14:30:15.123",
      "payoutAmount": "100.00",
      "orderTitle": "独立代发测试",
      "payerAccountBookId": "AB2026043000000001",
      "userId": "U2026043000000001",
      "platformBizNo": "BP202604301430151234560001",
      "payeeIdentityType": "ALIPAY_LOGON_ID",
      "payeeIdentity": "demo_user@example.com",
      "payeeName": "张三",
      "orderId": null,
      "status": "PROCESSING",
      "failReason": "代发处理中",
      "payDate": null,
      "payFundOrderId": null,
      "platformRequestTime": "2026-04-30 14:30:16.000"
    }
  }
}
```

---

# 三、错误码说明

以下仅列出与独立代发接口强相关的业务错误码。

| 错误码 | 说明 |
| :--- | :--- |
| `PARAM_ERROR` | 参数错误，如 `partnerId` 不在白名单内、参数格式非法等 |
| `BIZ_ERROR` | 业务异常，如白名单配置解析异常 |
| `EXECUTION_CONDITION_NOT_MET` | 执行条件不满足，如渠道商不存在、重复提交代发流水号等 |
| `USER_NOT_EXIST` | 代发账户不存在，`userId` 未注册或不属于当前渠道商 |

---

# 四、接入建议

1. 渠道商应保证 `partnerOutBizNo` 在自身系统内全局唯一，用于幂等控制。
2. 对返回 `PROCESSING` 的请求，建议调用方通过后续查询接口轮询最终状态。
3. 对 `FAILED` 状态，应结合 `failReason` 排查具体失败原因。
4. 如需稳定接入，建议在联调前先确认 `partnerId` 已加入系统白名单。
