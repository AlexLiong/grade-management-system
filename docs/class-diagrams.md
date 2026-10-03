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
```

## edu.campus.business

```mermaid
classDiagram
    class AdminService["AdminService"]
    class AnalyticsService["AnalyticsService"]
    class ApiController["ApiController"]
    class AuthService["AuthService"]
    class BusinessApplication["BusinessApplication"]
    class CoreRoutes["CoreRoutes"]
    class CourseService["CourseService"]
    class GradeService["GradeService"]
    class Models["Models"]
    class Models_Level["Models.Level"]
    Models *-- Models_Level
    class Models_User["Models.User"]
    <<record>> Models_User
    Models *-- Models_User
    class OrganizationService["OrganizationService"]
    class OrganizeRoutes["OrganizeRoutes"]
    class RemoteRepository["RemoteRepository"]
    class Routes["Routes"]
    <<interface>> Routes
    class Routes_Request["Routes.Request"]
    <<record>> Routes_Request
    Routes *-- Routes_Request
    class SelectionRoutes["SelectionRoutes"]
    class SelectionService["SelectionService"]
    class SelectionService_FailedCodes["SelectionService.FailedCodes"]
    SelectionService *-- SelectionService_FailedCodes
    class SelectionService_CodeHistory["SelectionService.CodeHistory"]
    <<record>> SelectionService_CodeHistory
    SelectionService *-- SelectionService_CodeHistory
    class SelectionService_StudentState["SelectionService.StudentState"]
    SelectionService *-- SelectionService_StudentState
    class SelectionService_Names["SelectionService.Names"]
    SelectionService *-- SelectionService_Names
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
    class Settings_Loader["Settings.Loader"]
    Settings *-- Settings_Loader
```

## edu.campus.data

```mermaid
classDiagram
    class DataApplication["DataApplication"]
    class DataRpcController["DataRpcController"]
    class DemoInitializer["DemoInitializer"]
    class DemoInitializer_Org["DemoInitializer.Org"]
    <<record>> DemoInitializer_Org
    DemoInitializer *-- DemoInitializer_Org
    class DemoInitializer_Person["DemoInitializer.Person"]
    <<record>> DemoInitializer_Person
    DemoInitializer *-- DemoInitializer_Person
    class DemoInitializer_Course["DemoInitializer.Course"]
    <<record>> DemoInitializer_Course
    DemoInitializer *-- DemoInitializer_Course
    class DemoInitializer_SampleLayer["DemoInitializer.SampleLayer"]
    <<record>> DemoInitializer_SampleLayer
    DemoInitializer *-- DemoInitializer_SampleLayer
    class DemoInitializer_Retake["DemoInitializer.Retake"]
    <<record>> DemoInitializer_Retake
    DemoInitializer *-- DemoInitializer_Retake
    class DemoInitializer_Attempt["DemoInitializer.Attempt"]
    <<record>> DemoInitializer_Attempt
    DemoInitializer *-- DemoInitializer_Attempt
    class DemoInitializer_GradeRow["DemoInitializer.GradeRow"]
    <<record>> DemoInitializer_GradeRow
    DemoInitializer *-- DemoInitializer_GradeRow
    class SchemaCatalog["SchemaCatalog"]
    class SqlCompiler["SqlCompiler"]
    class SqlCompiler_Statement["SqlCompiler.Statement"]
    <<record>> SqlCompiler_Statement
    SqlCompiler *-- SqlCompiler_Statement
    class TransactionService["TransactionService"]
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
    class SystemController["SystemController"]
    class WebConfiguration["WebConfiguration"]
    class WebConfiguration_Headers["WebConfiguration.Headers"]
    WebConfiguration *-- WebConfiguration_Headers
```

