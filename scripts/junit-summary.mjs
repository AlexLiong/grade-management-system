import fs from "node:fs";
import path from "node:path";
let total = 0, failures = 0, errors = 0, skipped = 0;
const rows = [];
for (const m of ["common", "gateway", "data-service", "business-service", "audit-service"]) {
  const dir = path.join(m, "target", "surefire-reports");
  if (!fs.existsSync(dir)) continue;
  let mt = 0, mf = 0, me = 0, ms = 0;
  const classes = [];
  for (const file of fs.readdirSync(dir).filter((x) => x.endsWith(".xml"))) {
    const xml = fs.readFileSync(path.join(dir, file), "utf8");
    const head = (xml.match(/<testsuite[^>]*>/) || [""])[0];
    const g = (k) => Number((head.match(new RegExp(k + '="(\\d+)"')) || [0, 0])[1]);
    const name = (head.match(/name="([^"]+)"/) || [, file])[1];
    classes.push({ name: name.split(".").pop(), tests: g("tests") });
    mt += g("tests"); mf += g("failures"); me += g("errors"); ms += g("skipped");
  }
  rows.push({ module: m, tests: mt, failures: mf, errors: me, classes });
  total += mt; failures += mf; errors += me; skipped += ms;
}
for (const r of rows) console.log(`${r.module.padEnd(18)} tests=${r.tests} failures=${r.failures} errors=${r.errors}`);
console.log(`TOTAL tests=${total} failures=${failures} errors=${errors} skipped=${skipped}`);
fs.mkdirSync(".runtime/logs", { recursive: true });
fs.writeFileSync(".runtime/logs/junit-summary.json", JSON.stringify({ modules: rows, total, failures, errors, skipped, generatedAt: new Date().toISOString() }, null, 2) + "\n");
console.log("written .runtime/logs/junit-summary.json");
