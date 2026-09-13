# 测试报告与验证证据

本报告依据实际运行输出生成，不把尚未执行的平台或安全扫描写成通过。原始摘要保存在 `docs/evidence/`，完整本机运行日志在 `.runtime/logs/`，浏览器执行结果在 `test-results/`。

## 环境

macOS ARM64、JDK 17.0.13、Maven、Node.js v26.0.0、Chromium、HTTPS 本机证书、加密 H2 文件库、独立 Java 服务与 Ganache EVM。演示数据为合成数据，不含真实学生信息。

## 汇总

|测试层|通过|失败|证据|
|---|---:|---:|---|
|Java 单元、事务与异常检测|20|0|[JUnit 原始报告汇总](evidence/junit-summary.json)|
|api|18|0|[api.json](evidence/api.json)|
|workflow|17|0|[workflow.json](evidence/workflow.json)|
|tamper|5|0|[tamper.json](evidence/tamper.json)|
|scale|4|0|[scale.json](evidence/scale.json)|
|Playwright 浏览器|6|0|[browser-summary.json](evidence/browser-summary.json)|

合计 70 项/组检查通过。不同层级包含多条断言，这个数量不等同于穷举所有输入组合。

## api · 2026-09-08T16:40:00.312Z

|检查|结果|耗时 ms|
|---|---|---:|
|HTTPS and registered independent services|PASS|23|
|Anonymous access is rejected|PASS|110|
|Three roles can authenticate; secure cookies|PASS|824|
|CSRF and cross-origin writes are rejected|PASS|10|
|Teachers cannot read other teachers courses|PASS|120|
|Students see only own submitted grades and rank|PASS|82|
|History filtering and pagination|PASS|38|
|ML predicts with three years and respects privacy|PASS|156|
|Grades validation, optimistic concurrency and full transaction|PASS|229|
|Incomplete submissions are rejected|PASS|31|
|Analysis is safely saved as text|PASS|82|
|Internal services reject unsigned, expired and replayed requests|PASS|24|
|SQL identifier injection rejected at remote server|PASS|1|
|Registry blocks SSRF targets|PASS|1|
|Independent ledger verifies original grades and EVM receipts|PASS|144|
|LSTM operation sequence classifier runs|PASS|18|
|Administrator review is independently recorded|PASS|23|
|Repeated login failures lock the account key|PASS|52|

## workflow · 2026-09-08T16:46:36.789Z

|检查|结果|耗时 ms|
|---|---|---:|
|Create student and second administrator with assigned permissions|PASS|1235|
|Administrator creates course and enrollment|PASS|160|
|Invalid coefficient total is rejected; valid six component weights saved|PASS|122|
|Partial draft is hidden from student|PASS|131|
|Batch validation rejects unknown student without partial changes|PASS|64|
|Complete grades submit; student sees 58/57 and class rank|PASS|140|
|Submitted grades and coefficients cannot be edited|PASS|55|
|Teacher withdrawal enables change and caps makeup at 60|PASS|239|
|Other authorized administrator can perform small revocation|PASS|120|
|Large revocation requires explicit confirmation and preserves evidence|PASS|123|
|After large revocation teacher can fully re-enter grades|PASS|138|
|Statistics and analysis version conflicts|PASS|83|
|Permissions updates invalidate sessions and take effect on next login|PASS|305|
|Password change revokes all prior sessions|PASS|923|
|Disabled accounts cannot log in and users cannot remove own management access|PASS|59|
|All workflow changes verify against independent encrypted ledger|PASS|135|
|Cleanup leaves explicit disabled test accounts and removes test course grades|PASS|131|

## tamper · 2026-09-08T16:49:40.448Z

|检查|结果|耗时 ms|
|---|---|---:|
|Database ciphertext corruption is detected and original score recovered from independent ledger|PASS|8484|
|Restoring the exact original ciphertext restores database integrity|PASS|8196|
|Ledger content modification is rejected|PASS|19|
|Ledger tail deletion is detected by independently persisted EVM anchors|PASS|20|
|Tamper fixtures fully restored; audit and outbox healthy|PASS|194|

## scale · 2026-09-08T16:50:43.724Z

|检查|结果|耗时 ms|
|---|---|---:|
|Second business instance registers independently|PASS|3019|
|Registry round-robin discovers two endpoints|PASS|9|
|Shared session remains valid across business replicas|PASS|247|
|Stopped replica lease expires and traffic remains available|PASS|21437|

## 浏览器与视觉验证

- course filters load matching history and saved drafts survive reload：通过
- teacher desktop grades, weights, analysis, prediction and print：通过
- student mobile private transcript and prediction fit viewport：通过
- administrator audit verification and XSS escape：通过
- local OCR recognizes numeric grade sheet without uploading image：通过
- login page desktop and mobile image renders：通过

截图经人工查看：桌面成绩表、移动端成绩单均无页面级横向溢出；宽表在自身容器内滚动。登录校园图片正常加载；图表来自真实 API 数据；教师分析 PDF 使用打印媒体生成。

- [teacher-desktop.png](evidence/teacher-desktop.png)
- [teacher-analysis.png](evidence/teacher-analysis.png)
- [student-mobile.png](evidence/student-mobile.png)
- [prediction-mobile.png](evidence/prediction-mobile.png)
- [admin-users.png](evidence/admin-users.png)
- [admin-audit.png](evidence/admin-audit.png)
- [login-desktop.png](evidence/login-desktop.png)
- [login-mobile.png](evidence/login-mobile.png)
- [analysis-print.pdf](evidence/analysis-print.pdf)

## 安全审计结果

- frontend：0 个已知漏洞（critical 0 / high 0 / moderate 0 / low 0），详见 [原始审计](evidence/frontend-audit.json)。
- chain-worker：33 个已知漏洞（critical 5 / high 22 / moderate 5 / low 1），详见 [原始审计](evidence/chain-worker-audit.json)。

Ganache 7.9.2 捆绑部分依赖，兼容修复后仍有 elliptic、secp256k1、ws 等上游风险。节点仅作为回环地址上的教学 EVM，通过 HMAC 端点访问，不连接真实资产；不能视为生产安全验收通过。Java 依赖未完成 OWASP Dependency-Check/NVD 扫描，未运行 ZAP、SonarQube 或 OpenVAS，未声称已经执行这些扫描。

## 已发现并修复的问题

注册发现 URL 序列化、TLS 信任库、EVM 重启账户、OCR WebAssembly CSP、停用学生退选、加载期间课程切换竞态，均在回归前修复。开发过程详见 decisions.md。

## 覆盖边界

- Windows、MySQL、SQL Server、Oracle、真实多机 TLS、防火墙和外部生产链未在本机实测。
- 未进行大规模负载/长稳/网络分区压力测试；测试对象是教学规模。
- 控制篡改测试修改本项目合成数据后完整恢复，当前业务库、独立账本和链摘要重新校验通过。
- 预测和日志模型实测包括训练与推理，不代表真实学校数据上的泛化准确率。
- 当前未实现分布式全局 nonce/限流、审计单写故障自动恢复、生产自动密钥轮换。

## 复现顺序

```text
mvn verify
node scripts/api-test.mjs
node scripts/workflow-test.mjs
node scripts/tamper-test.mjs
node scripts/scale-test.mjs
npm --prefix frontend run test:e2e
node scripts/generate-docs.mjs --check
```

测试共享同一演示数据库，应按顺序执行。tamper-test 会停止并重新启动服务，scale-test 会临时启动第二业务副本，不能与写入或浏览器测试并发。Playwright 首次需安装浏览器：在 frontend 目录执行 `npx playwright install chromium`，或设置 BROWSER_EXECUTABLE 指向已安装的 Chromium。
