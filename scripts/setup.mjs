#!/usr/bin/env node
/**
 * 环境初始化脚本：生成开发用 TLS 证书和信任库。
 * 用法：node scripts/setup.mjs [--reset]
 */
import fs from "node:fs";
import path from "node:path";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const runtime = path.join(root, ".runtime");
fs.mkdirSync(runtime, { recursive: true, mode: 0o700 });
for (const name of ["database", "ledger", "logs"])
    fs.mkdirSync(path.join(runtime, name), { recursive: true, mode: 0o700 });

const TLS_PASSWORD = "campus-dev-tls-2024";
const reset = process.argv.includes("--reset");

function run(command, args) {
    const r = spawnSync(command, args, {
        stdio: "inherit",
        cwd: root,
        shell: false,
    });
    if (r.status !== 0) throw new Error(`${command} failed`);
}

const p12File = path.join(runtime, "localhost.p12");
if (!fs.existsSync(p12File) || reset) {
    if (reset && fs.existsSync(p12File))
        fs.unlinkSync(p12File);
    console.log("生成自签名 TLS 证书（localhost）...");
    run("keytool", [
        "-genkeypair",
        "-alias",
        "campus",
        "-keyalg",
        "RSA",
        "-keysize",
        "3072",
        "-validity",
        "825",
        "-storetype",
        "PKCS12",
        "-keystore",
        p12File,
        "-storepass",
        TLS_PASSWORD,
        "-dname",
        "CN=localhost,OU=Teaching,O=Campus,L=Xian,ST=Shaanxi,C=CN",
        "-ext",
        "SAN=dns:localhost,ip:127.0.0.1",
    ]);
}

// node-forge reads PKCS12 without an OpenSSL executable, including on Windows.
const forge = (await import("../frontend/node_modules/node-forge/lib/index.js"))
    .default;
const der = fs
    .readFileSync(p12File)
    .toString("binary");
const p12 = forge.pkcs12.pkcs12FromAsn1(
    forge.asn1.fromDer(der),
    TLS_PASSWORD,
);
const key = p12.getBags({ bagType: forge.pki.oids.pkcs8ShroudedKeyBag })[
    forge.pki.oids.pkcs8ShroudedKeyBag
][0].key;
const cert = p12.getBags({ bagType: forge.pki.oids.certBag })[
    forge.pki.oids.certBag
][0].cert;
fs.writeFileSync(
    path.join(runtime, "localhost.key"),
    forge.pki.privateKeyToPem(key),
    { mode: 0o600 },
);
fs.writeFileSync(
    path.join(runtime, "localhost.crt"),
    forge.pki.certificateToPem(cert),
);
const truststoreFile = path.join(runtime, "truststore.p12");
if (!fs.existsSync(truststoreFile) || reset) {
    if (reset && fs.existsSync(truststoreFile))
        fs.unlinkSync(truststoreFile);
    run("keytool", [
        "-importcert",
        "-noprompt",
        "-alias",
        "campus-ca",
        "-file",
        path.join(runtime, "localhost.crt"),
        "-keystore",
        truststoreFile,
        "-storetype",
        "PKCS12",
        "-storepass",
        TLS_PASSWORD,
    ]);
}

console.log("✓ TLS 证书就绪（.runtime/localhost.p12，密码 campus-dev-tls-2024）");
