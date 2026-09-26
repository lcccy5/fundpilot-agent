# fund-web 与测试支撑可读性审计

本次只改注释和排版，不改导出名称，也不改请求、校验和分支。fund-web 没有组件级测试运行器（`test:ci` 只做 `tsc --noEmit`，`test:e2e` 要活着的前后端），所以没有新增查询、对比、自选、组合导入的失败态测试，也没有引入新的测试框架。

## 已加注释的文件

### fund-web

- `fund-web/vite.config.ts`：开发服务器与 `/api`、`/internal` 代理。
- `fund-web/next.config.ts`：未配置 `API_PROXY` 时不转发。
- `fund-web/lib/session.ts`：会话、本机自选、JSON 读取和带刷新的请求。
- `fund-web/components/AppShell.tsx`：顶栏、登录态和页面提示。
- `fund-web/app/layout.tsx`：文档外壳。
- `fund-web/app/page.tsx`：基金查询、净值区间、走势图、研究助手和加入自选。
- `fund-web/app/login/page.tsx`：登录。
- `fund-web/app/register/page.tsx`：注册。
- `fund-web/app/watchlists/page.tsx`：自选分组的加载、新增、删除和本机合并。
- `fund-web/app/portfolios/page.tsx`：组合、持仓、手工买入、导入预检和提交。
- `fund-web/app/runs/page.tsx`：异步研究、进度、取消、确认和报告。
- `fund-web/app/reports/page.tsx`：月报列表和创建。
- `fund-web/app/notifications/page.tsx`：通知列表。
- `fund-web/app/account/page.tsx`：风险问卷和画像。
- `fund-web/app/mcp/page.tsx`：管理员能力清单。

### fund-test-support

- `fund-test-support/src/main/java/com/jijing/fund/testsupport/rag/RagEvaluationDataset.java`
- `fund-test-support/src/main/java/com/jijing/fund/testsupport/rag/RagEvaluationCase.java`
- `fund-test-support/src/main/java/com/jijing/fund/testsupport/rag/RagEvaluationRunner.java`
- `fund-test-support/src/main/java/com/jijing/fund/testsupport/rag/RetrievalMetricCalculator.java`
- `fund-test-support/src/main/java/com/jijing/fund/testsupport/rag/CitationMetricCalculator.java`
- `fund-test-support/src/main/java/com/jijing/fund/testsupport/rag/RagEvaluationReportWriter.java`
- `fund-test-support/src/test/java/com/jijing/fund/testsupport/rag/RagEvaluationRunnerTest.java`

记录类型上由编译器生成的访问器没有单独写注释，失败方式写在记录本身的说明里。

### scripts

这些脚本没有函数定义，只在文件头说明了做什么、以及缺环境、超时和非 0 退出时会怎样。

- `scripts/verify-v2-real-env.ps1`
- `scripts/verify-v3-user-portfolio.ps1`
- `scripts/verify-v4-agent-runtime.ps1`
- `scripts/verify-v5-proactive-multi-agent.ps1`
- `scripts/start-mysql-it.ps1`
- `scripts/run-v3-browser-e2e.ps1`
- `scripts/init-local-mysql.sql`

## 查询、对比、自选、组合导入的空态和错误态

没有组件测试可挂这些失败路径，下面只记录界面现在怎么表现。

### 基金查询

- 加载：顶栏写“正在读取基金资料与历史净值…”，按钮变成“查询中…”。走势图点数不足时写“正在加载区间净值…”。
- 空：还没查询时，图是“查询基金后，这里会展示真实单位净值走势。”资料字段用横线。净值没有 `items` 时也是这块空图，没有单独的“该区间没有净值”。
- 4xx/5xx：资料非 2xx 用正文 `message`/`msg`，否则“基金资料暂不可用”。净值非 2xx 类似，默认“历史净值暂不可用”。非 JSON 提示前后端是否都已启动。失败只改顶栏，上一只基金的名称和曲线留在页面上，容易看成仍是当前结果。
- 校验：输入框只留数字并截到 6 位。提交时左侧补零，空输入会变成 `000000` 再请求，没有本地“代码不合法”的空态。区间切换失败会退回上一区间，图上没有错误块。

### 基金对比

- 没有对比页面，也没有并排空表。
- 首页研究助手把 `compare_fund_metrics` 显示成“基金比较”。工具失败只在该轮步骤里标红，回答气泡写“本次请求未完成”。
- 研究任务页把 `FUND_COMPARE` 显示成“基金比较”。任务失败用状态“未完成”，事件里的 `reason` 出现在“本次研究未完成”。没有对比结果为空时的专用面板。
- 加载和轮询失败只改顶栏，已显示的进度保留。

### 自选

- 加载：整页“正在加载自选基金…”。单张卡片在资料返回前写“基金资料加载中”。
- 空：没有任何分组时写“你的自选还是空的”。分组里没有基金时写“这个分组还没有基金”。
- 4xx/5xx：列表失败只改顶栏（未登录或接口文案）。加载结束后分组仍是空数组，正文继续显示“你的自选还是空的”，和真正的空列表分不开。单只资料失败被吞掉，卡片会一直停在“基金资料加载中”。
- 校验：分组名为空白、代码不是 6 位时不发请求，只改顶栏。首页“加入自选”在云端任意失败（含 4xx 校验）时改存本机，顶栏不显示接口错误。

### 组合导入

- 加载：持仓区写“正在更新持仓…”。
- 空：没有持仓时写“暂无可展示的持仓”。没选组合时收益是横线。
- 4xx/5xx：预检失败顶栏为接口文案或“导入检查失败”，不保留批次。提交失败顶栏为“提交导入失败”或接口文案，批次还在。持仓或收益任一失败时顶栏报错，表格仍可能是上一次的持仓。
- 校验：没选组合或文件时不上传。预检只显示有效行数和错误行数，不列出哪一行、哪个字段没过。错误行大于 0 时“确认导入”禁用。页面没有行级错误表。

### 其它容易看错的空态

- 通知请求失败后，顶栏报错，正文仍是“暂时没有通知”。
- 月报列表失败时有序列表是空的，和没有任务一样。
- 问卷加载失败时题目区仍写“正在加载问卷…”，保存按钮保持禁用。
- MCP 未返回数据时，无权、还在加载、请求失败都是同一句“无权查看或尚未加载。”

## 仍在的可读性问题

- `fund-web/app/page.tsx` 里首页的多个 `useState`、走势图的坐标计算，以及运行步骤面板的局部变量，仍写在同一条 `const` 声明里。
- 首页的标签类型含有 `watchlist` 和 `agent`，页面上没有对应面板。
- 顶栏当前页高亮在渲染时读 `window.location`，客户端切换路由后不一定更新，要整页刷新才稳定。
- 自选页首次加载和 `load` 各写了一遍列表请求，合并本机自选时才会走到 `load`。
- 月报页的 `load` 没有放进效果依赖。组合页首次加载另写了一遍列表请求，并关掉了依赖检查。
- 金额无法转成数字时，组合页会显示 `NaN`，没有单独的错误样式。
- 走势图的渐变 `id` 固定为 `nav-area`。当前一次只挂一张图；若总览和洞察同时挂载，填充会串。
- 评测报告写入失败时，临时文件不会被删掉。
- 检索折损增益会把排序里的重复命中再加一次；召回对重复 id 去重。两套口径不一致，注释里已经写明，计算没有改。
- `fund-test-support/pom.xml` 仍是一行。它不是 Java 源文件，这次没有排版。
- `fund-web/e2e/*.mjs` 和 `eslint.config.mjs` 不在本次 TypeScript 源文件范围内，没有加注释。
