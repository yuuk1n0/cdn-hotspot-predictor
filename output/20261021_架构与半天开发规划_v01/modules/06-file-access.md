# 06 CStore信息、预览与下载

版本：20261021 v01。任务：T06 Java → T07 Vue。本模块业务API为**拟议**；CStore上游协议尚未读取，不能据此认定已接通。

## 1. 功能目标

对应考核1.2、3.3.1。用记录所属AppID和已确认fileKey按需取实际大小、访问URL，支持浏览器预览/下载；临时URL不长期保存，文件信息请求不超过100QPS。

## 2. 页面与交互

从榜单打开轻量文件详情，展示App、原始身份、运行、预测窗口及已知属性。点击“查询大小”调用info；点击“预览/下载”才调用access。视频/图片/音频按contentType与浏览器能力显示，其他类型提供下载链接。过期后重新点击获取，不把URL写localStorage。失败显示原因，不露内部凭证。

## 3. 数据契约

| Method + Path | Java请求 | 响应字段与类型 |
|---|---|---|
| GET `/api/v1/files/info` | appId:string、fileKey:string必填 | appId/fileKey:string，fileSizeBytes:string（非负整数），contentType:string/null，queriedAt:timestamp，identityStatus=`VERIFIED` |
| GET `/api/v1/files/access` | appId/fileKey:string必填；purpose=`PREVIEW/DOWNLOAD` | appId/fileKey/purpose:string，url:string，expiresAt:timestamp/null，contentType:string/null，queriedAt:timestamp |

响应使用`Cache-Control: no-store`。所有fileKey在query中URL编码，不放到路径，不修改其原文。

```json
{
  "appId":"10350","fileKey":"example/a.mp4","fileSizeBytes":"100000000",
  "contentType":"video/mp4","queriedAt":"2026-10-21T01:00:00Z","identityStatus":"VERIFIED"
}
```

```json
{
  "appId":"10350","fileKey":"example/a.mp4","purpose":"PREVIEW",
  "url":"https://files.example.invalid/signed-example","expiresAt":null,
  "contentType":"video/mp4","queriedAt":"2026-10-21T01:00:01Z"
}
```

示例URL不可用，不是实际CStore响应。若上游不提供有效期，expiresAt=null，不杜撰TTL。

**上游映射开工门槛：**提供实际信息/URL接口的Method、Path、鉴权方式、App/fileKey字段、大小单位、成功/失败结构及一条可验证文件。将脱敏字段映射补入本模块后再编码适配。用户表示资料可提供，不等于本轮已经获得。协议不明时返回未接入，不能猜接口、安装SDK或直接访问CSV URL替代CStore。

## 4. 服务职责

| 端 | 单卡范围 |
|---|---|
| Java／T06 | files控制器、CStore薄HTTP适配器、同一全局限流入口；凭证从进程环境读；不打印签名URL/令牌 |
| Vue／T07 | 详情/预览组件和表格动作槽；新URL仅当前会话使用，错误可重试 |
| Python | **不修改**，不把观测身份擅自转换为CStore key |
| MySQL | **不新增文件表**，不存URL；压缩登记可保存经过查询的大小、时间和来源，仍不得保存URL |

## 5. 最小实现方式

复用Java现有HTTP客户端超时设置思路，连接3秒、读取5秒。单实例内所有CStore信息请求走同一入口，滑动一秒窗口最多50次、同时在途最多2个；没有等待容量则429，不建立无界队列。原文件及压缩版本共用限额，保证不超过题目100QPS。无需Redis、限流框架或长期缓存。

App与fileKey必须来自当前业务上下文，服务端核对调用上下文；CSV中的完整URL不等于CStore key，只有映射已确认才可请求。只接受上游返回的HTTP(S)访问URL。下载优先使用上游用途参数；浏览器是否强制保存取决于上游Content-Disposition及跨域能力，不编造已支持Range或所有编码播放。无预览能力时仍提供合法访问/下载操作与说明。

## 6. Mock与真实实现边界

CStore不生成业务假成功。离线测试可使用明确的测试HTTP响应，不能作为真实接入证据。Mock预测不影响真实文件访问，但身份必须验证。未接入时大小/类型保持null；人工TEST登记与真实查询分开。后续算法不改变文件访问契约。

## 7. 异常处理

422 IDENTITY_UNRESOLVED/APP_MISMATCH；404 FILE_NOT_FOUND（仅上游明确不存在时）；429 CSTORE_RATE_LIMITED；503 CSTORE_NOT_CONFIGURED/CSTORE_UNAVAILABLE；502 CSTORE_INVALID_RESPONSE。凭证错误对用户只显示脱敏业务码。过期URL重新获取，不承诺旧链接可用；存储或查询失败不返回0大小。

## 8. 验收标准

1. 按实际协议，用至少一条真实App/key从页面取得大小和URL，大小与上游一致，保留脱敏调用证据。
2. 连续点击预览/下载每次向Java获取URL；数据库、浏览器持久存储、应用日志均不含临时URL或凭证。
3. 测试入口统计任意滑动1秒信息请求≤50，含两个端点相关的原/后文件大小查询，超过容量返回429。
4. 无映射、错误App、上游断网、错误响应、URL过期和浏览器无法播放均有明确反馈，不用其他文件替代。
