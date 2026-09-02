# 基金数据 HTTP Provider 契约

当 `fund.provider.type=real` 时，系统按以下标准契约调用配置的 `base-url`。真实供应商字段不同，应在 `HttpExternalFundDataProvider` 内转换，不得修改 Domain 模型迎合供应商。

```text
GET /funds/{fundCode}
GET /funds/{fundCode}/nav?startDate=YYYY-MM-DD&endDate=YYYY-MM-DD
Authorization: Bearer ${FUND_DATA_API_KEY}    # 可选
```

基金详情响应：

```json
{
  "code": "000001",
  "name": "示例基金",
  "fundType": "混合型",
  "managementCompany": "示例基金公司",
  "fundManager": "示例经理",
  "establishedDate": "2001-12-18",
  "sourceUpdatedAt": "2026-08-21T12:00:00Z"
}
```

净值响应：

```json
{
  "items": [
    {
      "navDate": "2026-08-20",
      "unitNav": 1.234567,
      "accumulatedNav": 2.345678,
      "sourceUpdatedAt": "2026-08-21T12:00:00Z"
    }
  ]
}
```

自动化测试使用 MockWebServer 实现该契约。不要在测试中调用真实供应商。

