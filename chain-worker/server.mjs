import https from "node:https";
import fs from "node:fs";
import path from "node:path";
import crypto from "node:crypto";
import ganache from "ganache";
import * as tf from "@tensorflow/tfjs";

const runtime = path.resolve(process.env.CAMPUS_RUNTIME || "../.runtime");
const config = {
    LEDGER_KEY:"KEY",
    AUDIT_KEY:"KEY"
}
const provider = ganache.provider({
    database: { dbPath: path.join(runtime, "ethereum") },
    wallet: {
        totalAccounts: 1,
        seed: crypto
            .createHash("sha256")
            .update(config.LEDGER_KEY)
            .digest("hex"),
    },
    chain: { chainId: 1337 },
    logging: { quiet: true },
});
const [account] = await provider.request({
    method: "eth_accounts",
    params: [],
});
// Ganache is a private teaching chain; retain a stable signer and replenish test gas after restarts.
const balance = await provider.request({
    method: "eth_getBalance",
    params: [account, "latest"],
});
if (BigInt(balance) < 10n ** 18n)
    await provider.request({
        method: "evm_setAccountBalance",
        params: [account, "0x3635c9adc5dea00000"],
    });
const anchorFile = path.join(runtime, "anchors.json");
let anchors = fs.existsSync(anchorFile)
    ? JSON.parse(fs.readFileSync(anchorFile))
    : [];

// Seeded synthetic sequences make the demonstration repeatable; they are not real incident labels.
let seed = 731;
function random() {
    seed = (1664525 * seed + 1013904223) >>> 0;
    return seed / 4294967296;
}
const xs = [],
    ys = [];
for (let i = 0; i < 384; i++) {
    const bad = i % 2 === 0;
    xs.push(
        Array.from({ length: 12 }, () => [
            bad ? random() * 0.2 : 0.35 + random() * 0.5,
            bad ? 0.65 + random() * 0.35 : random() * 0.25,
            bad && random() > 0.4 ? 1 : 0,
        ]),
    );
    ys.push([bad ? 1 : 0]);
}
const model = tf.sequential();
model.add(
    tf.layers.lstm({
        units: 12,
        inputShape: [12, 3],
        kernelInitializer: tf.initializers.glorotUniform({ seed: 31 }),
        recurrentInitializer: tf.initializers.orthogonal({ seed: 32 }),
    }),
);
model.add(
    tf.layers.dense({
        units: 1,
        activation: "sigmoid",
        kernelInitializer: tf.initializers.glorotUniform({ seed: 33 }),
    }),
);
model.compile({
    optimizer: tf.train.adam(0.02),
    loss: "binaryCrossentropy",
    metrics: ["accuracy"],
});
const xt = tf.tensor3d(xs.slice(0, 320)),
    yt = tf.tensor2d(ys.slice(0, 320));
await model.fit(xt, yt, {
    epochs: 8,
    batchSize: 32,
    shuffle: false,
    verbose: 0,
});
xt.dispose();
yt.dispose();
const vx = tf.tensor3d(xs.slice(320)),
    vy = tf.tensor2d(ys.slice(320));
const evaluation = model.evaluate(vx, vy);
const accuracy = evaluation[1].dataSync()[0];
evaluation.forEach((t) => t.dispose());
vx.dispose();
vy.dispose();
const usedNonces = new Map();
let serial = Promise.resolve();
async function handle(url, body) {
    if (url === "/anchor") {
        if (!/^[a-f0-9]{64}$/.test(body.hash) || !Number.isInteger(body.index))
            throw new Error("Invalid anchor");
        if (anchors[body.index]) {
            if (anchors[body.index].hash !== body.hash)
                throw new Error("Anchor conflict");
            return anchors[body.index];
        }
        if (body.index !== anchors.length) throw new Error("Invalid sequence");
        const transaction = await provider.request({
            method: "eth_sendTransaction",
            params: [
                {
                    from: account,
                    to: "0x00000000000000000000000000000000000000a1",
                    data: "0x" + body.hash,
                    gas: "0x186a0",
                },
            ],
        });
        const receipt = await provider.request({
            method: "eth_getTransactionReceipt",
            params: [transaction],
        });
        if (receipt?.status !== "0x1") throw new Error("Transaction not mined");
        anchors.push({ hash: body.hash, transaction });
        const fd = fs.openSync(anchorFile + ".tmp", "w");
        fs.writeFileSync(fd, JSON.stringify(anchors));
        fs.fsyncSync(fd);
        fs.closeSync(fd);
        fs.renameSync(anchorFile + ".tmp", anchorFile);
        return { transaction };
    }
    if (url === "/verify") {
        if (body.blocks.length !== anchors.length)
            throw new Error("Ledger length differs from independent EVM anchors");
        for (let i = 0; i < anchors.length; i++) {
            const b = body.blocks[i],
                a = anchors[i];
            const transaction = await provider.request({
                method: "eth_getTransactionByHash",
                params: [a.transaction],
            });
            const receipt = await provider.request({
                method: "eth_getTransactionReceipt",
                params: [a.transaction],
            });
            if (
                b.hash !== a.hash ||
                b.transaction !== a.transaction ||
                transaction?.input !== "0x" + b.hash ||
                receipt?.status !== "0x1"
            )
                throw new Error("EVM integrity failure");
        }
        return { verified: true, chainId: 1337, blocks: anchors.length };
    }
    if (url === "/classify") {
        const events = body.events.filter((e) => e.action !== "SEED").slice(-120);
        const features = events.map((e, i) => {
            const time = new Date(e.time);
            const hour = (time.getUTCHours() + 8) % 24;
            const recent = events
                .slice(0, i + 1)
                .filter(
                    (p) => p.actor === e.actor && time - new Date(p.time) < 300000,
                ).length;
            return [
                hour / 24,
                Math.min(1, (recent + e.count) / 30),
                e.action.includes("DENIED") ? 1 : 0,
            ];
        });
        if (!features.length)
            return {
                model: "LSTM",
                dataset: "synthetic-demo-v1",
                validationAccuracy: accuracy,
                results: [],
            };
        const windows = features.map((_, i) => {
            let w = features.slice(Math.max(0, i - 11), i + 1);
            while (w.length < 12) w.unshift([0.5, 0, 0]);
            return w;
        });
        const scores = tf.tidy(() =>
            Array.from(model.predict(tf.tensor3d(windows)).dataSync()),
        );
        return {
            model: "LSTM",
            dataset: "synthetic-demo-v1",
            validationAccuracy: accuracy,
            results: events.map((e, i) => ({
                ...e,
                probability: scores[i],
                review:
                    scores[i] > 0.65 || (features[i][0] < 0.25 && features[i][1] > 0.5),
            })),
        };
    }
    throw new Error("Unknown endpoint");
}
https
    .createServer(
        {
            key: fs.readFileSync(path.join(runtime, "localhost.key")),
            cert: fs.readFileSync(path.join(runtime, "localhost.crt")),
            minVersion: "TLSv1.2",
        },
        async (req, res) => {
            res.setHeader("Content-Type", "application/json");
            try {
                let raw = "";
                for await (const chunk of req) {
                    raw += chunk;
                    if (raw.length > 2_000_000) throw new Error("Too large");
                }
                const time = req.headers["x-time"],
                    nonce = req.headers["x-nonce"],
                    signature = req.headers["x-signature"];
                const message = `${req.method}\n${req.url}\n${time}\n${nonce}\n${crypto.createHash("sha256").update(raw).digest("hex")}`;
                const expected = crypto
                    .createHmac("sha256", Buffer.from(config.AUDIT_KEY, "utf8"))
                    .update(message)
                    .digest("hex");
                if (
                    req.headers["x-service"] !== "audit" ||
                    Math.abs(Date.now() / 1000 - Number(time)) > 30 ||
                    typeof nonce !== "string" ||
                    typeof signature !== "string" ||
                    signature.length !== 64 ||
                    !crypto.timingSafeEqual(
                        Buffer.from(expected),
                        Buffer.from(signature),
                    ) ||
                    usedNonces.has(nonce)
                ) {
                    res.statusCode = 401;
                    res.end("{}");
                    return;
                }
                usedNonces.set(nonce, Date.now());
                for (const [n, t] of usedNonces)
                    if (Date.now() - t > 60000) usedNonces.delete(n);
                const next = serial.then(() => handle(req.url, JSON.parse(raw)));
                serial = next.catch(() => {});
                res.end(JSON.stringify(await next));
            } catch (e) {
                res.statusCode = 409;
                res.end(JSON.stringify({ error: "CHAIN_FAILURE", message: e.message }));
            }
        },
    )
    .listen(9545, "127.0.0.1", () =>
        console.log(
            `Chain and LSTM worker ready; synthetic validation accuracy ${accuracy.toFixed(3)}`,
        ),
    );
