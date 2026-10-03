import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;
import javax.lang.model.element.*;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.tools.*;
import com.sun.source.util.JavacTask;

/**
 * 把项目 {@code src/main/java} 下的真实类型清单导出为 JSON，供
 * {@code scripts/generate-docs.mjs} 生成 docs/classes.md、docs/class-diagrams.md 与
 * docs/source-inventory.json。
 *
 * <p>实现方式是用 JDK 自带的编译器语法树（{@code com.sun.source.util.JavacTask}）解析源码，
 * 只报告源码里实际声明过的类型，不把第三方模型或数据库表伪写成本项目 Java 类。
 *
 * <p>用法：{@code java scripts/SourceInventory.java <项目根目录>}，结果以 JSON 数组写到标准输出：
 *
 * <pre>
 * [{"name":"Models.User","package":"edu.campus.business","kind":"RECORD",
 *   "file":"business-service/src/main/java/edu/campus/business/Models.java",
 *   "fields":["id : String"],"methods":["&lt;init&gt;(String,String) : constructor"]}]
 * </pre>
 *
 * <p>只依赖 JDK，无第三方依赖；解析失败时把编译器诊断写到标准错误并以非 0 退出，
 * 便于在构建脚本中直接当校验使用。
 */
public final class SourceInventory {

  public static void main(String[] args) throws Exception {
    if (args.length < 1) {
      System.err.println("用法: java scripts/SourceInventory.java <项目根目录>");
      System.exit(2);
    }
    Path root = Path.of(args[0]).toAbsolutePath().normalize();
    List<Path> sources = collect(root);
    if (sources.isEmpty()) {
      System.err.println("未找到任何 Java 源文件：" + root);
      System.exit(1);
    }

    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    if (compiler == null) {
      System.err.println("当前运行环境没有 javac，需要完整 JDK 而不是 JRE。");
      System.exit(1);
    }
    DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
    List<Map<String, Object>> inventory = new ArrayList<>();
    try (StandardJavaFileManager files = compiler.getStandardFileManager(diagnostics, null, null)) {
      Iterable<? extends JavaFileObject> units = files.getJavaFileObjectsFromPaths(sources);
      JavacTask task =
          (JavacTask)
              compiler.getTask(
                  null, files, diagnostics, List.of("-proc:none", "-encoding", "UTF-8", "-nowarn"), null, units);
      var parsed = task.parse();
      task.analyze();
      var trees = com.sun.source.util.Trees.instance(task);
      for (var unit : parsed) {
        var path = Path.of(unit.getSourceFile().toUri());
        String file = root.relativize(path).toString().replace('\\', '/');
        for (var declaration : unit.getTypeDecls()) {
          if (!(declaration instanceof com.sun.source.tree.ClassTree classTree)) continue;
          Element element = trees.getElement(com.sun.source.util.TreePath.getPath(unit, classTree));
          if (element instanceof TypeElement type) collect(type, file, inventory);
        }
      }
      // 统一按 UTF-8 写字节，避免 Windows 默认代码页把中文写成乱码，
      // 也避免生成脚本读到无法解析的 JSON。
      var stdout = new java.io.PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out), true, "UTF-8");
      stdout.println(toJson(inventory));
      stdout.flush();
    }

    // 本工具只关心「源码里声明了哪些类型、字段与方法签名」，不需要解析依赖。
    // 不传 classpath 时，第三方符号无法解析会产生 ERROR 级诊断（缺少符号），
    // 这类诊断不代表源码有语法错误，因此不用它们判定失败。
    // 真正的失败判据是：某个源文件连一个类型都没能解析出来——语法错误会表现为该单元
    // 没有任何类型声明。这样既容忍缺少依赖，又能发现源码写坏了。
    Set<String> filesWithTypes = new LinkedHashSet<>();
    for (var entry : inventory) filesWithTypes.add(String.valueOf(entry.get("file")));
    List<String> unparsed = new ArrayList<>();
    for (Path source : sources) {
      String relative = root.relativize(source).toString().replace('\\', '/');
      if (!filesWithTypes.contains(relative)) unparsed.add(relative);
    }
    if (!unparsed.isEmpty()) {
      unparsed.forEach(f -> System.err.println("[SourceInventory] 无法解析任何类型：" + f));
      System.exit(1);
    }
    if (System.getenv("SOURCE_INVENTORY_DEBUG") != null)
      for (var diagnostic : diagnostics.getDiagnostics())
        System.err.println(
            "[SourceInventory][debug] "
                + diagnostic.getKind()
                + " @"
                + diagnostic.getLineNumber()
                + " "
                + diagnostic.getMessage(null));
    // javac 的编译任务会保留非守护线程，显式结束进程，保证脚本调用方拿到确定的退出码。
    Runtime.getRuntime().halt(0);
  }

  /** 递归收集各模块 {@code src/main/java} 下的全部 .java 源文件。 */
  private static List<Path> collect(Path root) throws IOException {
    List<Path> sources = new ArrayList<>();
    try (Stream<Path> modules = Files.list(root)) {
      for (Path module : modules.filter(Files::isDirectory).sorted().toList()) {
        Path sourceRoot = module.resolve("src/main/java");
        if (!Files.isDirectory(sourceRoot)) continue;
        try (Stream<Path> walk = Files.walk(sourceRoot)) {
          walk.filter(p -> p.toString().endsWith(".java")).sorted().forEach(sources::add);
        }
      }
    }
    return sources;
  }

  /** 记录一个类型及其嵌套类型。 */
  private static void collect(
      TypeElement type, String file, List<Map<String, Object>> inventory) {
    if (type.getKind() == ElementKind.ANNOTATION_TYPE) return;
    String packageName = nameOf(type);
    List<String> fields = new ArrayList<>();
    for (VariableElement field : ElementFilter.fieldsIn(type.getEnclosedElements())) {
      if (field.getKind() == ElementKind.ENUM_CONSTANT) continue;
      fields.add(field.getSimpleName() + " : " + simple(field.asType()));
    }
    List<String> methods = new ArrayList<>();
    for (ExecutableElement method : ElementFilter.methodsIn(type.getEnclosedElements())) {
      if (method.getKind() == ElementKind.STATIC_INIT) continue;
      boolean constructor = method.getKind() == ElementKind.CONSTRUCTOR;
      String name = constructor ? "<init>" : method.getSimpleName().toString();
      List<String> parameters = new ArrayList<>();
      for (VariableElement parameter : method.getParameters()) parameters.add(simple(parameter.asType()));
      String signature = name + "(" + String.join(",", parameters) + ")";
      methods.add(constructor ? signature + " : constructor" : signature + " : " + simple(method.getReturnType()));
    }
    String kind =
        switch (type.getKind()) {
          case INTERFACE -> "INTERFACE";
          case ENUM -> "ENUM";
          case RECORD -> "RECORD";
          default -> type.getKind() == ElementKind.RECORD ? "RECORD" : "CLASS";
        };
    // 嵌套类型在外层类型之后输出，名字用 Outer.Inner，与生成脚本的展示一致。
    var typeName = new TreeMap<String, Object>();
    typeName.put("name", simpleNameOf(type));
    typeName.put("package", packageName);
    typeName.put("kind", kind);
    typeName.put("file", file);
    typeName.put("fields", fields);
    typeName.put("methods", methods);
    // 若为嵌套类型，其限定名需要带上外层类型。
    typeName.put("name", nestedName(type));
    inventory.add(typeName);
    for (TypeElement nested : ElementFilter.typesIn(type.getEnclosedElements()))
      collect(nested, file, inventory);
  }

  private static String nestedName(TypeElement type) {
    var names = new ArrayDeque<String>();
    Element current = type;
    while (current instanceof TypeElement element) {
      names.addFirst(element.getSimpleName().toString());
      current = element.getEnclosingElement();
    }
    return String.join(".", names);
  }

  /** 取包名：沿外层元素向上找到最近的一个 PackageElement。 */
  private static String nameOf(TypeElement type) {
    Element current = type.getEnclosingElement();
    while (current != null && !(current instanceof PackageElement)) current = current.getEnclosingElement();
    return current instanceof PackageElement element ? element.getQualifiedName().toString() : "";
  }

  private static String simpleNameOf(TypeElement type) {
    return type.getSimpleName().toString();
  }

  /** 类型显示名：去掉 java.lang. 前缀，其余保持编译器给出的可读形式。 */
  private static String simple(TypeMirror mirror) {
    String text = mirror.toString();
    return text.startsWith("java.lang.") ? text.substring("java.lang.".length()) : text;
  }

  // ------------------------------------------------------------------ JSON

  private static String toJson(List<Map<String, Object>> inventory) {
    StringBuilder out = new StringBuilder("[\n");
    for (int i = 0; i < inventory.size(); i++) {
      var type = inventory.get(i);
      out.append("  {\n");
      out.append("    \"name\": ").append(quote(type.get("name"))).append(",\n");
      out.append("    \"package\": ").append(quote(type.get("package"))).append(",\n");
      out.append("    \"kind\": ").append(quote(type.get("kind"))).append(",\n");
      out.append("    \"file\": ").append(quote(type.get("file"))).append(",\n");
      out.append("    \"fields\": ").append(array(type.get("fields"))).append(",\n");
      out.append("    \"methods\": ").append(array(type.get("methods"))).append("\n");
      out.append("  }").append(i + 1 < inventory.size() ? "," : "").append("\n");
    }
    return out.append("]").toString();
  }

  @SuppressWarnings("unchecked")
  private static String array(Object value) {
    var list = (List<String>) value;
    StringBuilder out = new StringBuilder("[");
    for (int i = 0; i < list.size(); i++) {
      if (i > 0) out.append(", ");
      out.append(quote(list.get(i)));
    }
    return out.append("]").toString();
  }

  private static String quote(Object value) {
    if (value == null) return "null";
    String text = value.toString();
    StringBuilder out = new StringBuilder("\"");
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      switch (c) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        default -> {
          if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
          else out.append(c);
        }
      }
    }
    return out.append("\"").toString();
  }
}
