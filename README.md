# 知序 · 高校成绩管理系统

依据《软件开发综合能力实践（网络软件与安全）要求说明》（长安大学信息工程学院，2026 年 8 月）实现的课程实践工程。采用 Java 17、Spring Boot、Vue 3，提供独立网关、业务服务、数据访问服务、审计服务，以及本机以太坊测试链与 LSTM 工具进程。

原始 PDF 和提示文件在 `docs/requirements/`。本项目不包含答辩 PPT、个人报告或虚构的组员信息。课程报告、类说明和模型均为 Markdown。

## 快速运行

安装 JDK 17、Maven 3.9+、Node.js 22 LTS（也在 Node 26 上验证）。将 `java`、`keytool`、`mvn`、`node`、`npm` 加入 PATH。首次构建需要联网下载依赖。

首次启动先执行
```bash
node scripts/setup.mjs
```
在 .runtime 下生成证书

执行构建打包
```bash
mvn -q -DskipTests clean package
```
启动服务
```bash
./script/start.sh
```


浏览器须信任 `.runtime/localhost.crt` 或对本地测试站点确认例外。正式部署必须换用组织 CA 签发证书，不能关闭证书验证。

首次运行时由 `DemoSeeder` 自动向数据库注入演示账号（`admin` / `t1101` / `t1102` / `20231530`–`20231541`）及三年历史成绩，密码统一为 `passwd`，并记账、上链、签署合约。所有数据落在各模块的 `.runtime/database/` 中。


## 工程目录

|目录|职责|
|---|---|
|`common`|值传递协议、远程接口、安全签名、加密、注册心跳|
|`gateway`|HTTPS 统一入口、服务租约注册和轮询发现、静态页面|
|`business-service`|认证、权限、课程成绩流程、统计和预测；不依赖 JDBC|
|`data-service`|SQL 编译、参数绑定、事务、版本检查、成绩加密、审计发件箱|
|`audit-service`|独立加密账本、哈希链、EVM 锚定与原始证据|
|`chain-worker`|Ganache 本机 EVM、交易回执校验、TensorFlow.js LSTM|
|`frontend`|Vue 操作界面、浏览器本地 OCR、打印与浏览器测试|
|`scripts`|跨平台初始化、启停、集成测试、文档生成|
|`docs`|需求、架构、安全、类说明、部署、测试和模型|

## 已实现的业务

- 教师：六项百分制成绩系数、批量暂存、整课提交和撤回、正考与补考、往年课程搜索、统计与教学分析、OCR 辅助录入、打印。
- 学生：本学期与在校成绩、正考/补考并列、补考最高 60 分、本人课程排名、未通过课程统计、本人学业预测。
- 管理员：小撤销、大撤销、人员和组织维护、按角色分配功能权限、停用账号、重置密码、课程及选课维护、审计复核、独立原始成绩查询。
- 智能功能：均值/3σ/百分位/历史波动检测，Weka 线性回归和决策树，真实 TensorFlow.js LSTM 序列分类。预测仅临时计算，不进入正式成绩表。

## IDEA 与前端开发

在 IDEA 打开根 `pom.xml`，选择 JDK 17。先执行初始化与构建，再运行四个 Application 主类。各 Run Configuration 的工作目录设为项目根目录，VM options 配置见 [部署说明](docs/deployment.md)。`chain-worker` 独立运行 `npm start`。
依次启动服务：

    Chain-Worker
    GateWay
    Budiness-Service
    Audit-Service
    Data-Service

前端开发服务器

```bash
npm run --prefix frontedn dev
```
前端调试运行 `npm --prefix frontend run dev`，访问 https://localhost:5173；请求由 Vite 代理到 8443。最终交付页面由网关直接提供，无需同时启动 Vite。

## 验证

```text
mvn verify
node scripts/api-test.mjs
node scripts/workflow-test.mjs
node scripts/tamper-test.mjs
node scripts/scale-test.mjs
npm --prefix frontend run test:e2e
node scripts/generate-docs.mjs --check
```

运行集成测试需要后台已启动，依次执行，不要并发。Playwright 首次在 frontend 目录执行 `npx playwright install chromium`。测试新增的数据使用专用测试课程与账号，不对真实校园数据运行这些脚本。具体结果和未验证项见 [测试报告](docs/testing.md)。

## 文档入口

1. [需求分析与逐项追踪](docs/requirements.md)
2. [整体架构与分布式设计](docs/architecture.md)
3. [类职责与方法说明](docs/classes.md)
4. [UML、用例、状态、时序、数据库模型](docs/models.md)
5. [安全设计与应急处理](docs/security.md)
6. [运行部署与扩缩容](docs/deployment.md)
7. [API 与命名约定](docs/api.md)
8. [测试报告](docs/testing.md)
9. [开发过程与设计决策](docs/decisions.md)
