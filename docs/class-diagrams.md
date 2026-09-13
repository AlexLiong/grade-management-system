# 与源码对应的完整类型图

本文件由 JDK 语法树生成。每个节点对应一个真实命名类型；字段引用关系只表示代码依赖，不假造继承。完整方法签名见 classes.md。

## edu.campus.audit

```mermaid
classDiagram
    class AuditApplication["AuditApplication"]
    class AuditController["AuditController"]
    class LedgerService["LedgerService"]
    class LedgerService_Block["LedgerService.Block"]
    <<record>> LedgerService_Block
    LedgerService *-- LedgerService_Block
    AuditController --> LedgerService
```

## edu.campus.business

```mermaid
classDiagram
    class AdminService["AdminService"]
    class AnalyticsService["AnalyticsService"]
    class ApiController["ApiController"]
    class AuthService["AuthService"]
    class BusinessApplication["BusinessApplication"]
    class CourseService["CourseService"]
    class DemoSeeder["DemoSeeder"]
    class GradeService["GradeService"]
    class Models["Models"]
    class Models_User["Models.User"]
    <<record>> Models_User
    Models *-- Models_User
    class RemoteRepository["RemoteRepository"]
    AdminService --> RemoteRepository
    AnalyticsService --> CourseService
    AnalyticsService --> RemoteRepository
    ApiController --> AdminService
    ApiController --> AnalyticsService
    ApiController --> AuthService
    ApiController --> CourseService
    ApiController --> GradeService
    ApiController --> RemoteRepository
    AuthService --> RemoteRepository
    CourseService --> RemoteRepository
    DemoSeeder --> RemoteRepository
    GradeService --> AnalyticsService
    GradeService --> CourseService
    GradeService --> RemoteRepository
```

## edu.campus.common

```mermaid
classDiagram
    class ApiException["ApiException"]
    class Crypto["Crypto"]
    class ErrorAdvice["ErrorAdvice"]
    class InternalSecurity["InternalSecurity"]
    class Protocol["Protocol"]
    class Protocol_Selection["Protocol.Selection"]
    <<record>> Protocol_Selection
    Protocol *-- Protocol_Selection
    class Protocol_Operation["Protocol.Operation"]
    <<record>> Protocol_Operation
    Protocol *-- Protocol_Operation
    class Protocol_Mutation["Protocol.Mutation"]
    <<record>> Protocol_Mutation
    Protocol *-- Protocol_Mutation
    class Protocol_Registration["Protocol.Registration"]
    <<record>> Protocol_Registration
    Protocol *-- Protocol_Registration
    class Protocol_AuditEvent["Protocol.AuditEvent"]
    <<record>> Protocol_AuditEvent
    Protocol *-- Protocol_AuditEvent
    class Protocol_SelectInterface["Protocol.SelectInterface"]
    <<interface>> Protocol_SelectInterface
    Protocol *-- Protocol_SelectInterface
    class Protocol_ManipulationInterface["Protocol.ManipulationInterface"]
    <<interface>> Protocol_ManipulationInterface
    Protocol *-- Protocol_ManipulationInterface
    class RpcClient["RpcClient"]
    class ServiceHeartbeat["ServiceHeartbeat"]
    class Settings["Settings"]
```

## edu.campus.data

```mermaid
classDiagram
    class DataApplication["DataApplication"]
    class DataRpcController["DataRpcController"]
    class SchemaCatalog["SchemaCatalog"]
    class SqlCompiler["SqlCompiler"]
    class SqlCompiler_Statement["SqlCompiler.Statement"]
    <<record>> SqlCompiler_Statement
    SqlCompiler *-- SqlCompiler_Statement
    class TransactionService["TransactionService"]
    DataRpcController --> TransactionService
    SqlCompiler --> SchemaCatalog
    TransactionService --> SchemaCatalog
    TransactionService --> SqlCompiler
```

## edu.campus.gateway

```mermaid
classDiagram
    class GatewayApplication["GatewayApplication"]
    class GatewayController["GatewayController"]
    class RegistryController["RegistryController"]
    class RegistryController_Entry["RegistryController.Entry"]
    <<record>> RegistryController_Entry
    RegistryController *-- RegistryController_Entry
    class WebConfiguration["WebConfiguration"]
    class WebConfiguration_Headers["WebConfiguration.Headers"]
    WebConfiguration *-- WebConfiguration_Headers
    GatewayController --> RegistryController
```

